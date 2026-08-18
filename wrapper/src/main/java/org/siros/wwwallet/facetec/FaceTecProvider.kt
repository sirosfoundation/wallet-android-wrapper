package org.siros.wwwallet.facetec

import org.siros.wwwallet.BuildConfig
import timber.log.Timber

object FaceTecProvider {
    private const val IMPL_CLASS = "org.siros.wwwallet.facetec.FaceTecManagerImpl"

    fun getManager(): FaceTecManager {
        if (BuildConfig.FACETEC_API_BEARER_TOKEN.isBlank()) {
            Timber.i("FaceTec is not enabled in this build: no FACETEC_API_BEARER_TOKEN.")

            return FaceTecNoOpManager()
        }

        return try {
            Class
                .forName(IMPL_CLASS)
                .getDeclaredConstructor()
                .newInstance() as FaceTecManager
        } catch (t: Throwable) {
            // This used to fail silently, which is indistinguishable from "FaceTec was left
            // out of this build" once you are looking at a device rather than a build script.
            // Throwable rather than Exception, since a class that cannot be verified throws
            // an Error -- see [org.siros.wwwallet.facetec.FaceTecDiagnostics] (issue #20).
            Timber.e(t, "FaceTec is enabled in this build, but '$IMPL_CLASS' could not be loaded -- falling back to a no-op.")

            FaceTecNoOpManager()
        }
    }
}
