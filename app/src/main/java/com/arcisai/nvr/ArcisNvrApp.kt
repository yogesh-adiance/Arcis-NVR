package com.arcisai.nvr

import android.app.Application

class ArcisNvrApp : Application() {
    companion object {
        @Volatile var instance: ArcisNvrApp? = null; private set
        init { System.loadLibrary("juicejni") }
    }
    override fun onCreate() { super.onCreate(); instance = this }
}
