package org.siros.wwwallet.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

object RenderEffects {
    val Grayscale =
        RenderEffect
            .createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }),
            ).asComposeRenderEffect()
}

fun Modifier.grayscale(enabled: Boolean = true): Modifier =
    this.then(
        Modifier.graphicsLayer {
            this.renderEffect = if (enabled) RenderEffects.Grayscale else null
        },
    )
