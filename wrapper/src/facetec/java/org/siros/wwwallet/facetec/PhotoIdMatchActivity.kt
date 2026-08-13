package org.siros.wwwallet.facetec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.facetec.sdk.FaceTecInitializationError
import com.facetec.sdk.FaceTecSDK
import com.facetec.sdk.FaceTecSDKInstance
import org.siros.wwwallet.R
import org.siros.wwwallet.tagForLog
import org.siros.wwwallet.util.YOLOLogger

/**
 * Headless host for the FaceTec SDK's 3D-liveness-then-3D:2D-Photo-ID-Match flow.
 * Launched by [org.siros.wwwallet.bridging.WalletJsBridge.startScanPhysicalId] from the wallet
 * web app's "Scan Physical ID" page. The scan starts immediately on create — there is no
 * separate native "Start Scan" screen — and this Activity finishes as soon as the FaceTec
 * session returns.
 *
 * The FaceTec session itself runs in an Activity the SDK launches via the legacy
 * startActivityForResult API, so its result is collected in [onActivityResult] rather than
 * through an [androidx.activity.result.ActivityResultLauncher].
 */
class PhotoIdMatchActivity : ComponentActivity() {
    private var sdkInstance: FaceTecSDKInstance? = null
    private var capturedCredentialOfferURI: String? = null

    private val processor =
        PhotoIdMatchSessionRequestProcessor(
            onCredentialOfferReceived = { capturedCredentialOfferURI = it },
        )

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSession()
            } else {
                Toast.makeText(this, getString(R.string.photo_id_match_camera_permission_denied), Toast.LENGTH_LONG).show()
                finishWithoutOffer()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FaceTecSDK.preload(this)
        FaceTecConfig.configureOCRLocalization(this)
        FaceTecSDK.setCustomization(FaceTecConfig.customization())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startSession() {
        val instance = sdkInstance
        if (instance != null) {
            instance.start3DLivenessThen3D2DPhotoIDMatch(this, processor)
            return
        }

        FaceTecSDK.initializeWithSessionRequest(
            this,
            FaceTecConfig.DEVICE_KEY_IDENTIFIER,
            processor,
            object : FaceTecSDK.InitializeCallback {
                override fun onSuccess(newSdkInstance: FaceTecSDKInstance) {
                    sdkInstance = newSdkInstance
                    newSdkInstance.start3DLivenessThen3D2DPhotoIDMatch(this@PhotoIdMatchActivity, processor)
                }

                override fun onError(error: FaceTecInitializationError) {
                    // FaceTec invokes onError from a background thread (an AsyncTask
                    // worker), not the main thread. Toast requires a Looper on the
                    // calling thread, so showing it here directly crashes with
                    // "Can't toast on a thread that has not called Looper.prepare()" --
                    // runOnUiThread hops back to the main thread first.
                    // `tagForLog` is read from the outer Activity (this@PhotoIdMatchActivity)
                    // rather than the anonymous callback object itself, whose
                    // javaClass.simpleName is empty and would log with a blank tag.
                    YOLOLogger.e(this@PhotoIdMatchActivity.tagForLog, "FaceTec SDK initialization failed: $error")
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@PhotoIdMatchActivity,
                                getString(R.string.photo_id_match_initialization_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    finishWithoutOffer()
                }
            },
        )
    }

    // Required by FaceTec SDK's startActivityForResult-based session API.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        val result = FaceTecSDK.getActivitySessionResult(requestCode, resultCode, data) ?: return

        YOLOLogger.i(tagForLog, "FaceTec session finished with status ${result.status}.")

        finishWithOfferIfAvailable()
    }

    private fun finishWithOfferIfAvailable() {
        YOLOLogger.i(tagForLog, "Finishing with credentialOfferURI=$capturedCredentialOfferURI")

        val resultIntent =
            Intent().apply {
                capturedCredentialOfferURI?.let { putExtra(FaceTecManager.EXTRA_CREDENTIAL_OFFER_URI, it) }
            }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithoutOffer() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
