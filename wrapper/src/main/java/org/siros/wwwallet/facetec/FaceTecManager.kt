package org.siros.wwwallet.facetec

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher

interface FaceTecManager {
    companion object {
        const val EXTRA_CREDENTIAL_OFFER_URI = "credentialOfferURI"
    }

    fun startPhotoIdMatch(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<Intent>,
    )
}
