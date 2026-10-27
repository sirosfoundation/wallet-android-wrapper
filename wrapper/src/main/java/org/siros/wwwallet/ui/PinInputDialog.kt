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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.siros.wwwallet.R

@Composable
fun PinInputDialog(
    onDismiss: (String?) -> Unit
) {
    Dialog(onDismissRequest = { }) {
        PinInputDialogContent(onDismiss)
    }
}

@Composable
private fun PinInputDialogContent(
    onDismiss: (String?) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }

    Surface(
        Modifier.fillMaxWidth().wrapContentHeight(),
        RoundedCornerShape(16.dp),
        Color.White
    ) {
        Column(
            Modifier.padding(24.dp),
            Arrangement.spacedBy(32.dp),
            Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.pin_dialog_title),
                Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                pin,
                { pin = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pin_dialog_hint)) },
                trailingIcon = {
                    IconButton({ passwordVisible = !passwordVisible }) {
                        Icon(
                           if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            if (passwordVisible) stringResource(R.string.dialog_hide_pin) else stringResource(R.string.dialog_show_pin)
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            Column(
                Modifier.fillMaxWidth(),
                Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    { onDismiss(pin) },
                    ButtonType.PRIMARY,
                    stringResource(R.string.dialog_confirm),
                    pin.isNotEmpty(),
                )

                Button(
                    { onDismiss(null) },
                    ButtonType.OUTLINED,
                    stringResource(android.R.string.cancel)
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewPinInputDialog() {
    MaterialTheme(
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            PinInputDialogContent { }
        }
    }
}