package com.example.chessreviewer

import android.app.Application

class ChessReviewerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ShizukuPermissionHelper.register()
    }

    override fun onTerminate() {
        ShizukuPermissionHelper.unregister()
        super.onTerminate()
    }
}
