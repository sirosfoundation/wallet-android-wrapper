package org.siros.wwwallet.facetec

import android.content.Context
import android.os.Build
import com.facetec.sdk.FaceTecSDK
import org.siros.wwwallet.BuildConfig
import timber.log.Timber

/**
 * Breadcrumbs for the FaceTec flow, written through Timber and therefore through
 * [org.siros.wwwallet.util.FileLoggingTree], which keeps the log on disk so it survives the
 * process being killed by a crash. (Read it back from the debug menu — rage shake.)
 *
 * Issue #20: on some devices (reported: Samsung Galaxy S23 Ultra, Android 16, release build)
 * the FaceTec SDK dies with a `java.lang.VerifyError` while loading its own classes — once
 * from the `AsyncTask` inside `FaceTecSDK.initializeWithSessionRequest`, once on the main
 * thread while the session UI is coming up. Two things follow from that:
 *
 * - `VerifyError` is an [Error], not an [Exception], so the usual `catch (e: Exception)` does
 *   not stop it. [step] therefore catches [Throwable] on purpose.
 * - Both throws happen on threads *inside* the SDK, so no `try`/`catch` of ours can wrap
 *   them. What we can do is log immediately before and after every single SDK call, so that
 *   the last line in the log file names the exact step that died — plus the device and build
 *   details needed to tell "release-only" and "Samsung-only" apart.
 */
internal object FaceTecDiagnostics {
    /**
     * Everything that plausibly differs between a device where the flow works and one where
     * it crashes, in one line, logged before the first SDK call is made.
     */
    fun logEnvironment(context: Context) {
        Timber.i(
            "FaceTec environment: app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), debug=${BuildConfig.DEBUG}, " +
                "faceTecSdk=${faceTecVersion()}, device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
                "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), abis=${Build.SUPPORTED_ABIS.joinToString("/")}, " +
                "api=${BuildConfig.FACETEC_API_BASE_URL}",
        )

        // Reading these goes through the SDK too, so it can fail exactly the way the rest of
        // the flow does — hence the same wrapping as every other SDK call.
        step("read camera permission status") {
            Timber.i("FaceTec camera permission status: ${FaceTecSDK.getCameraPermissionStatus(context)}.")
        }

        step("read lockout state") {
            Timber.i("FaceTec lockout: isLockedOut=${FaceTecSDK.isLockedOut(context)}, lockoutEndTime=${FaceTecSDK.getLockoutEndTime(context)}.")
        }
    }

    /**
     * Runs one FaceTec SDK call, logging the step name and the calling thread before and
     * after it, and returns `null` if it threw.
     *
     * Inline, so the log lines carry the tag of the class that actually made the call rather
     * than this one. Note that a `null` return only distinguishes "threw" from "returned" for
     * blocks that never legitimately return `null` — which is all of them here.
     */
    inline fun <T> step(
        name: String,
        block: () -> T,
    ): T? =
        try {
            Timber.i("FaceTec step '$name': starting on thread '${Thread.currentThread().name}'.")
            block().also { Timber.i("FaceTec step '$name': returned.") }
        } catch (t: Throwable) {
            // Throwable, not Exception: the failure we are chasing is a VerifyError (see the
            // class doc). A LinkageError out of a third-party SDK should leave the user
            // without a scan, not without an app.
            Timber.e(t, "FaceTec step '$name': FAILED with ${t.javaClass.name} on thread '${Thread.currentThread().name}'.")
            null
        }

    private fun faceTecVersion(): String =
        try {
            FaceTecSDK.version()
        } catch (t: Throwable) {
            // Even this touches the SDK's classes, which is precisely what fails in issue #20.
            "<unavailable: ${t.javaClass.name}: ${t.message}>"
        }
}
