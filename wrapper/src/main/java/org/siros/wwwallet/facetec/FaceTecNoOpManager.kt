package org.siros.wwwallet.facetec

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher

class FaceTecNoOpManager : FaceTecManager {
    override fun startPhotoIdMatch(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        Toast.makeText(activity, "FaceTec feature is not enabled in this build", Toast.LENGTH_SHORT).show()
    }
}
