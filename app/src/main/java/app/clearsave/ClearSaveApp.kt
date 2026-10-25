package app.clearsave

import android.app.Application

class ClearSaveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.start(this)
    }
}
