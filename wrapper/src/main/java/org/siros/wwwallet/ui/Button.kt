package org.siros.wwwallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ButtonType {
    PRIMARY,
    OUTLINED,
}

@Composable
fun ButtonType.buttonColors(): ButtonColors =
    when (this) {
        ButtonType.PRIMARY ->
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        ButtonType.OUTLINED ->
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
    }

@Composable
fun ButtonType.borderStroke(): BorderStroke? {
    if (this == ButtonType.PRIMARY) return null
    return BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
}

@Composable
fun Button(
    onClick: () -> Unit,
    type: ButtonType,
    text: String,
    enabled: Boolean = true,
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
        border = type.borderStroke(),
    ) {
        Text(text)
    }
}
