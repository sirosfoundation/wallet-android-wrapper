package org.siros.wwwallet.facetec

import org.siros.wwwallet.BuildConfig

object FaceTecProvider {
    fun getManager(): FaceTecManager =
        if (BuildConfig.FACETEC_API_BEARER_TOKEN.isNotBlank()) {
            try {
                Class
                    .forName("org.siros.wwwallet.facetec.FaceTecManagerImpl")
                    .getDeclaredConstructor()
                    .newInstance() as FaceTecManager
            } catch (_: Exception) {
                FaceTecNoOpManager()
            }
        } else {
            FaceTecNoOpManager()
        }
}
