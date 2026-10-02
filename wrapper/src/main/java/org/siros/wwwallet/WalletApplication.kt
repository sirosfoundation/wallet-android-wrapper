package org.siros.wwwallet

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.siros.wwwallet.credentials.dcApi.DigitalCredentials
import org.siros.wwwallet.storage.Settings
import org.siros.wwwallet.util.FileLoggingTree
import timber.log.Timber

class WalletApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Timber.plant(FileLoggingTree(this))

        runBlocking {
            Settings.init(this@WalletApplication)
        }

        setupCrashHandler()

        applicationScope.launch {
            try {
                DigitalCredentials.registerStoredCredentials(this@WalletApplication)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Could not restore DC-API registration")
            }
        }
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
