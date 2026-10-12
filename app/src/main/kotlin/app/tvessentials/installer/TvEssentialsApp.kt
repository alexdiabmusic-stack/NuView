package app.tvessentials.installer

import android.app.Application

class TvEssentialsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
