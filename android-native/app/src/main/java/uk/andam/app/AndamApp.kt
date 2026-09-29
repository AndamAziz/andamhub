package uk.andam.app

import android.app.Application
import uk.andam.app.auth.Session
import uk.andam.app.net.Api

class AndamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        Api.init(this)
    }
}
