(() => {
const $ = s => document.querySelector(s);
// Inside the Android app the page talks to the on-phone engine through window.CSNative instead of a server.
const NATIVE = !!window.CSNative;
const screens = ['lock','paste','load','opts','prog','done'];
const show = n => {
  screens.forEach(k => $('#s-'+k).classList.toggle('on', k===n));
  $('#stage').classList.toggle('wide', n==='opts');
  window.scrollTo({top:0});
};

const AUDIO = [
  {id:'mp3',  name:'MP3',  sub:'Universal',      lossy:true,  cover:true},
  {id:'m4a',  name:'M4A',  sub:'AAC · Apple',    lossy:true,  cover:true},
  {id:'flac', name:'FLAC', sub:'Lossless',       lossy:false, cover:true},
  {id:'wav',  name:'WAV',  sub:'Uncompressed',   lossy:false, cover:false},
  {id:'aac',  name:'AAC',  sub:'Raw stream',     lossy:true,  cover:false},
  {id:'opus', name:'Opus', sub:'Efficient',      lossy:true,  cover:true},
  {id:'ogg',  name:'OGG',  sub:'Vorbis',         lossy:true,  cover:true},
  {id:'alac', name:'ALAC', sub:'Apple Lossless', lossy:false, cover:true},
];
// The Android engine has no mutagen, so only formats ffmpeg can tag itself get cover art.
if(NATIVE) AUDIO.forEach(a => { if(['flac','opus','ogg'].includes(a.id)) a.cover = false; });
const VIDEO = [
  {id:'mp4',  name:'MP4',  sub:'Best compatibility'},
  {id:'mkv',  name:'MKV',  sub:'Any codec'},
  {id:'webm', name:'WebM', sub:'VP9 · Opus'},
];
const BITRATES = [320,256,192,128];
const PL_CAPS = [null,2160,1440,1080,720,480,360];
const PLAT = {youtube:'YouTube', x:'X', instagram:'Instagram'};

const S = {info:null, mode:'single', kind:'video', height:null, plHeight:null, vfmt:'mp4', afmt:'mp3', br:320,
           cover:true, trim:false, picked:new Set(), jobId:null, timer:null, maxPlaylist:25};

const fmtBytes = n => !n ? '' : n>=1e9 ? (n/1e9).toFixed(1)+' GB' : n>=1e6 ? Math.round(n/1e6)+' MB' : Math.round(n/1e3)+' KB';
const fmtDur = s => { if(!s) return ''; s=Math.round(s); const h=Math.floor(s/3600), m=Math.floor(s%3600/60), x=s%60; return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(x).padStart(2,'0'); };
const qLabel = h => !h?'Best':h>=4320?'8K':h>=2160?'4K':h>=1440?'1440p':h+'p';
const esc = t => String(t||'').replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const LINK_RE = /youtu|twitter\.com|x\.com|instagram|instagr\.am/i;

function setMsg(t, err){ const m=$('#msg'); m.textContent=t||''; m.className='msg'+(err?' err':''); }
function reset(){ clearInterval(S.timer); S.info=null; S.jobId=null; $('#url').value=''; setMsg(''); show('paste'); if(!IOS && !NATIVE) $('#url').focus(); }
function setSeg(seg, idx, attr, val){
  seg.dataset.pos = idx;
  seg.querySelectorAll('button').forEach(b=>b.setAttribute('aria-selected', b.dataset[attr]===val));
}
// native bridge: Kotlin answers asynchronously by calling window.__csResolve(id, status, jsonText)
const pendingCalls = {}; let callSeq = 0;
window.__csResolve = (id, status, text) => { const p = pendingCalls[id]; delete pendingCalls[id]; if(p) p(status, text); };
const nativeCall = (path, body) => new Promise(res => {
  const id = ++callSeq;
  pendingCalls[id] = (status, text) => { let d = {}; try{ d = JSON.parse(text); }catch{} res({status, d}); };
  window.CSNative.call(id, path, body ? JSON.stringify(body) : '');
});
async function api(path, body){
  let status, d = {};
  if(NATIVE){ ({status, d} = await nativeCall(path, body)); }
  else {
    const r = await fetch(path, body ? {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)} : undefined);
    status = r.status; try{ d = await r.json(); }catch{}
  }
  if(status===401 && d.locked){ show('lock'); throw new Error(d.error); }
  if(status<200 || status>=300) throw new Error(d.error || 'Something went wrong.');
  return d;
}

/* ---- start-up: access code (if the server uses one) ---- */
(async ()=>{
  try{
    const c = await api('/api/config');
    S.maxPlaylist = c.maxPlaylist || 25;
    if(c.locked && !c.authed){ show('lock'); $('#code').focus(); }
    else if(!IOS && !NATIVE) $('#url').focus();
  }catch{}
})();
$('#lockForm').addEventListener('submit', async e=>{
  e.preventDefault();
  try{ await api('/api/login', {code:$('#code').value}); $('#code').value=''; $('#lockMsg').textContent=''; show('paste'); }
  catch(err){ $('#lockMsg').textContent = err.message; $('#lockMsg').className='msg err'; }
});

/* ---- fetch info ---- */
async function fetchInfo(){
  const url = $('#url').value.trim();
  if(!url){ setMsg('Paste a link first.', true); return; }
  setMsg(''); show('load');
  try{
    const d = await api('/api/info', {url});
    S.info = d; S.height = d.videos[0]?.height || null; S.plHeight = null;
    S.mode = d.kind==='playlist' ? 'playlist' : 'single';
    S.picked = new Set((d.playlist?.entries||[]).slice(0, S.maxPlaylist).map(e=>e.index));
    S.trim = false; $('#trim').setAttribute('aria-checked','false'); $('#times').hidden = true;
    $('#t-start').value = ''; $('#t-end').value = '';
    renderOpts(); show('opts');
  }catch(e){ if(!$('#s-lock').classList.contains('on')){ show('paste'); setMsg(e.message, true); } }
}
$('#form').addEventListener('submit', e=>{ e.preventDefault(); fetchInfo(); });
$('#url').addEventListener('paste', ()=> setTimeout(()=>{ if(LINK_RE.test($('#url').value)) fetchInfo(); }, 30));
$('#pasteBtn').addEventListener('click', async ()=>{
  if(NATIVE){ const t = (window.CSNative.paste() || '').trim(); if(t){ $('#url').value = t; fetchInfo(); } else setMsg('Copy a link first, then tap Paste.'); return; }
  try{ const t = await navigator.clipboard.readText(); $('#url').value = t.trim(); if(t.trim()) fetchInfo(); }
  catch{ $('#url').focus(); setMsg('Press ' + (/Mac|iPhone|iPad/.test(navigator.platform)?'⌘V':'Ctrl+V') + ' to paste.'); }
});
$('#back').addEventListener('click', reset);

// Mac/PC: paste a link anywhere on the first screen, Esc to start over
document.addEventListener('paste', e=>{
  if(!$('#s-paste').classList.contains('on') || document.activeElement===$('#url')) return;
  const t = (e.clipboardData?.getData('text')||'').trim();
  if(t){ $('#url').value = t; if(LINK_RE.test(t)) fetchInfo(); }
});
document.addEventListener('keydown', e=>{ if(e.key==='Escape' && $('#s-opts').classList.contains('on')) reset(); });

/* ---- options ---- */
function chip(label, sub, pressed, onClick){
  const b = document.createElement('button');
  b.className='chip glass'; b.setAttribute('aria-pressed', pressed);
  b.innerHTML = `<span>${label}</span><small>${sub||'&nbsp;'}</small>`;
  b.onclick = onClick; return b;
}
const isPl = () => S.mode==='playlist';
const curH = () => isPl() ? S.plHeight : S.height;

function renderOpts(){
  const d = S.info, pl = d.playlist;
  $('#thumb').src = d.thumbnail || '';
  $('#title').textContent = d.title;
  $('#chan').textContent = (d.kind==='playlist' ? [PLAT[d.platform], d.channel, pl.count+' items'] : [PLAT[d.platform], d.channel, fmtDur(d.duration)]).filter(Boolean).join(' · ');

  // single / playlist switch (only when a video also belongs to a playlist)
  const both = d.kind==='video' && !!pl;
  $('#mode').hidden = !both;
  if(both){ $('#mode-pl').textContent = `Whole playlist (${pl.count})`; setSeg($('#mode'), isPl()?1:0, 'mode', S.mode); }

  $('#pl-wrap').hidden = !isPl();
  if(isPl()){
    $('#pl-list').innerHTML = pl.entries.map(e=>`
      <label class="pl-row"><input type="checkbox" data-i="${e.index}" ${S.picked.has(e.index)?'checked':''}>
        <img src="${esc(e.thumbnail)}" alt="" loading="lazy" referrerpolicy="no-referrer"><span>${esc(e.title)}</span><small>${fmtDur(e.duration)}</small></label>`).join('');
    updatePlCount();
  } else { $('#go').disabled = false; }
  $('#trim-wrap').hidden = isPl();
  renderOpts2();
}
function updatePlCount(){
  const n = S.picked.size, t = S.info.playlist.entries.length, cap = S.maxPlaylist;
  $('#pl-count').textContent = `${n} of ${t} selected` + (t>cap ? ` (max ${cap})` : '');
  const all = Math.min(t, cap);
  $('#pl-all').textContent = n>=all ? 'Clear' : (t>cap ? `Select first ${cap}` : 'Select all');
  $('#pl-list').querySelectorAll('input').forEach(c=>{ c.disabled = !c.checked && n>=cap; });
  $('#go').disabled = n===0;
}
$('#pl-list').addEventListener('change', e=>{
  const i = +e.target.dataset.i; e.target.checked ? S.picked.add(i) : S.picked.delete(i); updatePlCount();
});
$('#pl-all').addEventListener('click', ()=>{
  const first = S.info.playlist.entries.slice(0, S.maxPlaylist).map(e=>e.index);
  S.picked = S.picked.size>=first.length ? new Set() : new Set(first);
  $('#pl-list').querySelectorAll('input').forEach(c=>c.checked = S.picked.has(+c.dataset.i));
  updatePlCount();
});
$('#mode').addEventListener('click', e=>{
  const b = e.target.closest('button'); if(!b) return;
  S.mode = b.dataset.mode; renderOpts();
});

function renderOpts2(){
  const d = S.info;

  const qv = $('#q-video'); qv.innerHTML='';
  if(isPl() || !d.videos.length){
    const caps = isPl() ? PL_CAPS : [null];
    caps.forEach(h=>qv.append(chip(h?qLabel(h):'Best', h?(isPl()?'up to':''):(isPl()?'Highest available':'Original'), curH()===h, ()=>{ S.plHeight=h; renderOpts2(); })));
  } else {
    d.videos.forEach((v,i)=>{
      const sub = [v.fps>30?v.fps+' fps':'', v.hdr?'HDR':'', fmtBytes(v.size)].filter(Boolean).join(' · ') || (i===0?'Best':'');
      qv.append(chip(qLabel(v.height), sub, v.height===S.height, ()=>{ S.height=v.height; renderOpts2(); }));
    });
  }

  const fv = $('#f-video'); fv.innerHTML='';
  VIDEO.forEach(f=>fv.append(chip(f.name,f.sub,f.id===S.vfmt,()=>{S.vfmt=f.id;renderOpts2();})));

  const fa = $('#f-audio'); fa.innerHTML='';
  AUDIO.forEach(f=>fa.append(chip(f.name,f.sub,f.id===S.afmt,()=>{S.afmt=f.id;renderOpts2();})));

  const cur = AUDIO.find(a=>a.id===S.afmt);
  $('#br-wrap').hidden = !cur.lossy;
  $('#cover-row').hidden = !cur.cover;
  const qa = $('#q-audio'); qa.innerHTML='';
  BITRATES.forEach(b=>qa.append(chip(b+' kbps', b===320?'Highest':b===128?'Smallest':'', b===S.br, ()=>{S.br=b;renderOpts2();})));
  $('#a-note').textContent = cur.lossy
    ? (d.audio_bitrate ? `The best audio stream here is ~${d.audio_bitrate} kbps.` : '')
    : `${cur.name} is lossless, but the source audio is already compressed, so the file is larger without sounding better.`;

  const est = (S.kind==='video' && !isPl()) ? fmtBytes(d.videos.find(v=>v.height===S.height)?.size) : '';
  $('#est').textContent = est ? '≈ '+est : '';
  $('#t-end').placeholder = d.duration ? fmtDur(d.duration) : 'end';
}
$('#seg').addEventListener('click', e=>{
  const b = e.target.closest('button'); if(!b) return;
  S.kind = b.dataset.kind;
  setSeg($('#seg'), S.kind==='video'?0:1, 'kind', S.kind);
  $('#p-video').hidden = S.kind!=='video'; $('#p-audio').hidden = S.kind!=='audio';
  renderOpts2();
});
$('#cover').addEventListener('click', e=>{ S.cover=!S.cover; e.currentTarget.setAttribute('aria-checked', S.cover); });
$('#trim').addEventListener('click', e=>{
  S.trim=!S.trim; e.currentTarget.setAttribute('aria-checked', S.trim); $('#times').hidden=!S.trim;
  if(S.trim) $('#t-start').focus();
});

/* ---- download ---- */
$('#go').addEventListener('click', async ()=>{
  const body = {url:S.info.url, kind:S.kind,
    format: S.kind==='video'?S.vfmt:S.afmt,
    height: S.kind==='video'?curH():null,
    bitrate: S.kind==='audio'?S.br:null,
    cover: S.kind==='audio' && S.cover};
  if(isPl()){
    body.playlist = {url:S.info.playlist.url, title:S.info.playlist.title, items:[...S.picked]};
  } else if(S.trim){
    body.start = $('#t-start').value.trim(); body.end = $('#t-end').value.trim();
    if(!body.start && !body.end){ $('#t-start').focus(); return; }
  }
  setRing(0,true); $('#ptitle').textContent='Starting…'; $('#psub').textContent=''; show('prog');
  try{
    const d = await api('/api/download', body);
    S.jobId = d.id; S.timer = setInterval(poll, 450);
  }catch(e){ show('opts'); alert(e.message); }
});
function setRing(p, indet){
  $('#ring').classList.toggle('indet', !!indet);
  $('#bar').style.strokeDashoffset = 326.7*(indet?0.75:1-p);
  $('#pct').textContent = indet ? '' : Math.round(p*100)+'%';
}
let failures = 0;
async function poll(){
  try{
    const d = await api('/api/progress/'+S.jobId); failures = 0;
    const item = d.items ? `Item ${d.item} of ${d.items}` : '';
    if(d.phase==='downloading'){
      if(d.indet) setRing(1,true); else setRing(d.pct||0);
      $('#ptitle').textContent = 'Downloading';
      $('#psub').textContent = [item, d.speed?fmtBytes(d.speed)+'/s':'', d.eta?d.eta+'s left':''].filter(Boolean).join(' · ');
    } else if(d.phase==='converting'){
      if(d.indet){ setRing(1,true); } else { setRing(d.pct||0); }
      $('#ptitle').textContent = d.items ? (d.indet?'Packing your zip':'Converting') : S.kind==='audio' ? 'Converting' : 'Merging in full quality';
      $('#psub').textContent = [item, d.indet?'Almost there…':''].filter(Boolean).join(' · ');
    } else if(d.phase==='error'){
      clearInterval(S.timer); show('paste'); setMsg(d.error||'Download failed.', true);
    } else if(d.phase==='done'){
      clearInterval(S.timer); finish(d);
    }
  }catch(e){
    if(++failures>=8){ clearInterval(S.timer); show('paste'); setMsg(e.message || 'Lost connection to the server.', true); }
  }
}

/* ---- saving ---- */
const IOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform==='MacIntel' && navigator.maxTouchPoints>1);
const meta = d => [d.name, d.count>1?d.count+' files':'', fmtBytes(d.size)].filter(Boolean).join(' · ');

function finish(d){
  $('#dsub').textContent = meta(d);
  if(NATIVE){  // the app already saved the file into Downloads/ClearSave
    $('#dtitle').textContent = 'Saved'; $('#dsub').textContent = meta(d) + ' · Downloads/ClearSave';
    $('#saveBtn').hidden = true; $('#skipBtn').hidden = true; show('done'); setTimeout(reset, 3200); return;
  }
  if(IOS && navigator.share) return finishIOS(d);
  // Mac/PC/Android: the browser saves straight to Downloads, then we go back to the start
  const a = document.createElement('a'); a.href='/api/file/'+S.jobId; a.download=d.name; document.body.append(a); a.click(); a.remove();
  $('#dtitle').textContent = 'Saved'; $('#saveBtn').hidden = true; $('#skipBtn').hidden = true;
  show('done');
  setTimeout(reset, 2600);
}

// iPhone/iPad: Safari can't drop files into Downloads silently, so hand the finished file to the
// share sheet (Save Video / Save to Files / AirDrop). Fetch first so the tap still counts as a user gesture.
async function finishIOS(d){
  const btn = $('#saveBtn'), skip = $('#skipBtn');
  $('#dtitle').textContent = 'Preparing…'; btn.hidden = true; skip.hidden = true; show('done');
  let file;
  try{
    const r = await fetch('/api/file/'+S.jobId);
    if(!r.ok) throw new Error('fetch');
    const blob = await r.blob();
    file = new File([blob], d.name, {type: blob.type || 'application/octet-stream'});
  }catch{ return iosFallback(d); }
  if(navigator.canShare && !navigator.canShare({files:[file]})) return iosShareUnsupported(file, d);
  $('#dtitle').textContent = 'Ready';
  btn.hidden = false; skip.hidden = false;
  btn.onclick = async ()=>{
    try{ await navigator.share({files:[file]}); reset(); }
    catch(e){ if(e.name!=='AbortError') iosShareUnsupported(file, d); }  // AbortError: they closed the sheet; stay so they can retry
  };
  skip.onclick = reset;
}
function iosShareUnsupported(file, d){
  const url = URL.createObjectURL(file), a = document.createElement('a'); a.href=url; a.download=d.name; a.click();
  $('#dtitle').textContent = 'Saved'; $('#saveBtn').hidden = true; $('#skipBtn').hidden = false; $('#skipBtn').onclick = reset;
}
function iosFallback(d){
  const a = document.createElement('a'); a.href='/api/file/'+S.jobId; a.download=d.name; a.click();
  $('#dtitle').textContent = 'Saved'; $('#skipBtn').hidden = false; $('#skipBtn').onclick = reset;
}

/* ---- hooks for the Android app: Back button, and links shared into the app from YouTube/X/Instagram ---- */
window.__csBack = () => { if($('#s-opts').classList.contains('on')){ reset(); return true; } return false; };
window.__csShared = text => {
  const m = String(text||'').match(/https?:\/\/\S+/);
  if(!m || !$('#s-paste').classList.contains('on') && !$('#s-opts').classList.contains('on')) return;
  $('#url').value = m[0]; fetchInfo();
};
if(NATIVE) $('.foot').innerHTML = 'For personal use. You\'re responsible for what you download.<br>Everything runs on your phone. Nothing is uploaded.';
})();
