package com.morgan.wakepc

import android.app.Application

/**
 * Exists so the networking layer can read the device's DNS search domains,
 * which needs a Context and is not otherwise reachable from [WakeApi].
 */
class WakePcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WakeApi.attach(applicationContext)
    }
}
