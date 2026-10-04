package org.siros.wwwallet.facetec

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.facetec.sdk.FaceTecInitializationError
import com.facetec.sdk.FaceTecSDK
import com.facetec.sdk.FaceTecSDKInstance
import org.siros.wwwallet.R
import timber.log.Timber

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
 *
 * Every call into the SDK goes through [FaceTecDiagnostics.step], which logs the step and
 * catches [Throwable] rather than [Exception] — see that class for why (issue #20).
 *
 * facetec-api issues nothing without an authenticated read of the document's NFC chip
 * (sirosfoundation/facetec-api#65), so the scan is not started on a phone without NFC, and
 * a user with NFC switched off is sent to the settings first. When the scan completes but no
 * credential is issued, the `credentialIssueErrorCode` facetec-api returned is explained to the
 * user instead of silently returning to the wallet.
 */
class PhotoIdMatchActivity : ComponentActivity() {
    private var sdkInstance: FaceTecSDKInstance? = null
    private var capturedCredentialOfferURI: String? = null
    private var capturedCredentialIssueErrorCode: String? = null

    private val processor =
        PhotoIdMatchSessionRequestProcessor(
            onCredentialOfferReceived = { capturedCredentialOfferURI = it },
            onCredentialIssueRefused = { capturedCredentialIssueErrorCode = it },
        )

    private val nfcSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val enabled = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true
            Timber.i("Returned from the NFC settings: NFC enabled=$enabled.")

            if (enabled) {
                prepareAndStart()
            } else {
                Toast.makeText(this, getString(R.string.photo_id_match_nfc_still_disabled), Toast.LENGTH_LONG).show()
                finishWithoutOffer()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("Camera permission request answered: granted=$granted.")

            if (granted) {
                startSession()
            } else {
                Toast.makeText(this, getString(R.string.photo_id_match_camera_permission_denied), Toast.LENGTH_LONG).show()
                finishWithoutOffer()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("PhotoIdMatchActivity.onCreate(recreated=${savedInstanceState != null}).")

        FaceTecDiagnostics.logEnvironment(this)

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Timber.i("NFC available=${nfcAdapter != null}, enabled=${nfcAdapter?.isEnabled}.")

        when {
            nfcAdapter == null -> {
                Toast.makeText(this, getString(R.string.photo_id_match_nfc_unavailable), Toast.LENGTH_LONG).show()
                finishWithoutOffer()
            }
            !nfcAdapter.isEnabled -> askToEnableNfc()
            else -> prepareAndStart()
        }
    }

    private fun askToEnableNfc() {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.photo_id_match_nfc_disabled_title)
            .setMessage(R.string.photo_id_match_nfc_disabled_message)
            .setPositiveButton(R.string.photo_id_match_nfc_open_settings) { _, _ -> openNfcSettings() }
            .setNegativeButton(R.string.photo_id_match_cancel) { _, _ -> finishWithoutOffer() }
            .setOnCancelListener { finishWithoutOffer() }
            .show()
    }

    private fun openNfcSettings() {
        try {
            nfcSettingsLauncher.launch(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Some devices have no dedicated NFC settings screen.
            Timber.w(e, "No NFC settings screen; falling back to the wireless settings.")
            nfcSettingsLauncher.launch(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun prepareAndStart() {
        // The preparation calls are listed one per step so the log names whichever of them
        // fails; `preload` in particular is where the SDK first loads its own classes.
        val prepared =
            FaceTecDiagnostics.step("preload") { FaceTecSDK.preload(this) } != null &&
                FaceTecDiagnostics.step("configureOCRLocalization") { FaceTecConfig.configureOCRLocalization(this) } != null &&
                FaceTecDiagnostics.step("setCustomization") { FaceTecSDK.setCustomization(FaceTecConfig.customization()) } != null

        if (!prepared) {
            failWithInitializationError()
            return
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        Timber.i("Camera permission already granted: $hasCameraPermission.")

        if (hasCameraPermission) {
            startSession()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startSession() {
        val instance = sdkInstance
        if (instance != null) {
            Timber.i("Reusing the FaceTecSDKInstance from a previous initialization.")
            startPhotoIdMatch(instance)
            return
        }

        Timber.i("Initializing the FaceTec SDK (device key identifier: ${FaceTecConfig.DEVICE_KEY_IDENTIFIER}).")

        // NB: this `step` only covers the synchronous part of the call. The SDK does the
        // actual initialization on an AsyncTask worker of its own, and the VerifyError of
        // issue #20 is thrown *there* — no `try`/`catch` on this thread can catch it. What
        // the surrounding logging does buy us is a log file whose last line says
        // "initializeWithSessionRequest: starting", pinning the crash to this step.
        val launched =
            FaceTecDiagnostics.step("initializeWithSessionRequest") {
                FaceTecSDK.initializeWithSessionRequest(
                    this,
                    FaceTecConfig.DEVICE_KEY_IDENTIFIER,
                    processor,
                    object : FaceTecSDK.InitializeCallback {
                        override fun onSuccess(newSdkInstance: FaceTecSDKInstance) {
                            Timber.i("FaceTec SDK initialization succeeded on thread '${Thread.currentThread().name}'.")

                            sdkInstance = newSdkInstance
                            startPhotoIdMatch(newSdkInstance)
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
                            Timber.e("FaceTec SDK initialization failed on thread '${Thread.currentThread().name}': $error")
                            failWithInitializationError()
                        }
                    },
                )
            }

        if (launched == null) {
            failWithInitializationError()
        }
    }

    private fun startPhotoIdMatch(instance: FaceTecSDKInstance) {
        val started =
            FaceTecDiagnostics.step("start3DLivenessThen3D2DPhotoIDMatch") {
                instance.start3DLivenessThen3D2DPhotoIDMatch(this, processor)
            }

        if (started == null) {
            failWithInitializationError()
        }
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

        Timber.i("PhotoIdMatchActivity.onActivityResult(requestCode=$requestCode, resultCode=$resultCode, hasData=${data != null}).")

        val result = FaceTecDiagnostics.step("getActivitySessionResult") { FaceTecSDK.getActivitySessionResult(requestCode, resultCode, data) }

        if (result == null) {
            // Either the SDK did not recognize this result as one of its own (some other
            // Activity we started came back), or the call itself threw -- the step above
            // logged which. Staying alive is only correct in the former case, so say so.
            Timber.w("No FaceTec session result for requestCode=$requestCode -- staying open.")
            return
        }

        Timber.i("FaceTec session finished with status ${result.status}.")

        val refusal = capturedCredentialIssueErrorCode
        if (capturedCredentialOfferURI == null && refusal != null) {
            showRefusalAndFinish(refusal)
        } else {
            finishWithOfferIfAvailable()
        }
    }

    private fun showRefusalAndFinish(credentialIssueErrorCode: String) {
        Timber.i("No credential issued: credentialIssueErrorCode=$credentialIssueErrorCode.")

        val message =
            when (credentialIssueErrorCode) {
                "nfc_not_requested" -> R.string.photo_id_match_refused_nfc_not_requested
                "nfc_device_not_capable" -> R.string.photo_id_match_refused_nfc_device_not_capable
                "nfc_skipped" -> R.string.photo_id_match_refused_nfc_skipped
                "nfc_chip_read_failed" -> R.string.photo_id_match_refused_nfc_chip_read_failed
                "nfc_not_authenticated" -> R.string.photo_id_match_refused_nfc_not_authenticated
                "chip_untrusted" -> R.string.photo_id_match_refused_chip_untrusted
                "policy_rejected" -> R.string.photo_id_match_refused_policy_rejected
                "match_failed" -> R.string.photo_id_match_refused_match_failed
                "issuance_failed" -> R.string.photo_id_match_refused_issuance_failed
                "internal_error" -> R.string.photo_id_match_refused_internal_error
                "liveness_failed" -> R.string.photo_id_match_refused_liveness_failed
                "document_expired" -> R.string.photo_id_match_refused_document_expired
                "document_unreadable" -> R.string.photo_id_match_refused_document_unreadable
                else -> R.string.photo_id_match_refused_other
            }

        AlertDialog
            .Builder(this)
            .setTitle(R.string.photo_id_match_refused_title)
            .setMessage(message)
            .setPositiveButton(R.string.photo_id_match_ok) { _, _ -> finishWithoutOffer() }
            .setOnCancelListener { finishWithoutOffer() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()

        Timber.i("PhotoIdMatchActivity.onDestroy(isFinishing=$isFinishing).")
    }

    private fun failWithInitializationError() {
        runOnUiThread {
            Toast
                .makeText(
                    this,
                    getString(R.string.photo_id_match_initialization_failed),
                    Toast.LENGTH_LONG,
                ).show()
        }

        finishWithoutOffer()
    }

    private fun finishWithOfferIfAvailable() {
        Timber.i("Finishing with credentialOfferURI=$capturedCredentialOfferURI")

        val resultIntent =
            Intent().apply {
                capturedCredentialOfferURI?.let { putExtra(FaceTecManager.EXTRA_CREDENTIAL_OFFER_URI, it) }
            }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithoutOffer() {
        Timber.i("Finishing without a credentialOfferURI.")

        setResult(RESULT_CANCELED)
        finish()
    }
}
