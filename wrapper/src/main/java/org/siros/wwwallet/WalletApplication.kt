package org.siros.wwwallet

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import org.siros.wwwallet.util.FileLoggingTree
import timber.log.Timber

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(FileLoggingTree(this))

        setupCrashHandler()
    }

    @SuppressLint("LogNotTimber")
    private fun setupCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e(throwable, "FATAL CRASH in thread ${thread.name}: ${throwable.message}")
            } catch (tr: Throwable) {
                Log.e("CrashHandler", "Error in crash handler", tr)
            } finally {
                original?.uncaughtException(thread, throwable)
            }
        }
    }
}
