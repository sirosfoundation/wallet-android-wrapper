package org.siros.wwwallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import org.siros.wwwallet.R

enum class ButtonType(val containerColorRes: Int, val contentColorRes: Int, val borderColorRes: Int?) {
    PRIMARY(
        R.color.button_primary_background,
        R.color.button_primary_text,
        null
    ),
    OUTLINED(
        R.color.button_outlined_background,
        R.color.button_outlined_text,
        R.color.button_outlined_border
    )
}

@Composable
fun ButtonType.buttonColors(): ButtonColors {
    return ButtonDefaults.buttonColors(
        colorResource(containerColorRes),
        colorResource(contentColorRes),
        colorResource(containerColorRes),
        colorResource(contentColorRes)
    )
}

@Composable
fun ButtonType.borderStroke(): BorderStroke? {
    if (borderColorRes == null) return null

    return BorderStroke(1.dp, colorResource(borderColorRes))
}

@Composable
fun Button(
    onClick: () -> Unit,
    type: ButtonType,
    text: String,
    enabled: Boolean = true
) {
    Button(
        onClick,
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .grayscale(!enabled),
        enabled,
        shape = RoundedCornerShape(8.dp),
        colors = type.buttonColors(),
        border = type.borderStroke()
    ) {
        Text(text)
    }
}