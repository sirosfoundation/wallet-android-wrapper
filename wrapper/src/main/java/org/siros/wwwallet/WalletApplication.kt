package org.siros.wwwallet

import android.app.Application
import org.siros.wwwallet.util.FileLoggingTree
import timber.log.Timber

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(FileLoggingTree(this))
    }
}
