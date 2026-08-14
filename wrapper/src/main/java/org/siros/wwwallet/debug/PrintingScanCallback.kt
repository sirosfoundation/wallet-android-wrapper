package org.siros.wwwallet.debug

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import timber.log.Timber

open class PrintingScanCallback : ScanCallback() {
    override fun onScanResult(
        callbackType: Int,
        result: ScanResult?,
    ) {
        Timber.d("onScanResult: callbackType=$callbackType result=$result")

        super.onScanResult(callbackType, result)
    }

    override fun onBatchScanResults(results: List<ScanResult?>?) {
        Timber.d("onBatchScanResults: results=$results")

        super.onBatchScanResults(results)
    }

    override fun onScanFailed(errorCode: Int) {
        Timber.d("onScanFailed: errorCode=$errorCode")

        super.onScanFailed(errorCode)
    }
}
