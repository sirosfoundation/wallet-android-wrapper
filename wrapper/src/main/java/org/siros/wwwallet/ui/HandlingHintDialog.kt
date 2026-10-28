package org.siros.wwwallet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.siros.wwwallet.R

enum class HandlingHintType {
    NFC, USB
}

@Composable
private fun HandlingHintType.title(): String {
    return when (this) {
        HandlingHintType.NFC -> stringResource(R.string.handling_dialog_title_nfc)
        HandlingHintType.USB -> stringResource(R.string.handling_dialog_title_usb)
    }
}

@Composable
private fun HandlingHintType.message(): String {
    return when (this) {
        HandlingHintType.NFC -> stringResource(R.string.handling_dialog_message_nfc)
        HandlingHintType.USB -> stringResource(R.string.handling_dialog_message_usb)
    }
}

@Composable
private fun HandlingHintType.icon(): Painter {
    return when (this) {
        HandlingHintType.NFC -> painterResource(R.drawable.ic_nfc)
        HandlingHintType.USB -> painterResource(R.drawable.ic_usb)
    }
}

@Composable
fun HandlingHintDialog(type: HandlingHintType = HandlingHintType.NFC, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { }) {
        Surface(
            Modifier.fillMaxWidth().wrapContentHeight(),
            RoundedCornerShape(16.dp),
            Color.White,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                Arrangement.spacedBy(32.dp),
                Alignment.CenterHorizontally,
            ) {
                Text(
                    type.title(),
                    Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )

                Text(
                    type.message(),
                    Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Icon(
                    type.icon(),
                    null,
                    tint = colorResource(R.color.button_primary_background),
                )

                Button(
                    { onDismiss() },
                    ButtonType.OUTLINED,
                    stringResource(android.R.string.cancel),
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewHandlingHintDialog() {
    MaterialTheme(
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            HandlingHintDialog(HandlingHintType.USB) {}
        }
    }
}
