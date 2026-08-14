package org.siros.wwwallet.debug

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import timber.log.Timber

open class PrintingAdvertiseCallback : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        Timber.d("onStartSuccess: settingsInEffect=$settingsInEffect")

        super.onStartSuccess(settingsInEffect)
    }

    override fun onStartFailure(errorCode: Int) {
        Timber.d("onStartFailure: errorCode=$errorCode")

        super.onStartFailure(errorCode)
    }
}
