package org.siros.wwwallet.facetec

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.Keep
import timber.log.Timber

@Suppress("unused")
@Keep
class FaceTecManagerImpl : FaceTecManager {
    override fun startPhotoIdMatch(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        Timber.i("Launching PhotoIdMatchActivity.")

        val intent = Intent(activity, PhotoIdMatchActivity::class.java)
        launcher.launch(intent)
    }
}
