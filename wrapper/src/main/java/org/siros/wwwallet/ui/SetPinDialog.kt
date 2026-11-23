package org.siros.wwwallet.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yubico.yubikit.fido.ctap.ClientPin
import org.siros.wwwallet.R

@Composable
fun SetPinDialog(onDismiss: (String?) -> Unit) {
    var accepted by remember { mutableStateOf(false) }

    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }

    var showStep1 by remember { mutableStateOf(true) }

    var passwordVisible1 by remember { mutableStateOf(false) }
    var passwordVisible2 by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { }) {
        Surface(
            Modifier.fillMaxWidth().wrapContentHeight(),
            RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
                Arrangement.spacedBy(32.dp),
                Alignment.CenterHorizontally,
            ) {
                if (showStep1) {
                    Text(
                        stringResource(R.string.set_pin_dialog_title),
                        Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Text(
                        stringResource(R.string.set_pin_explanation),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(R.string.set_pin_remember),
                                Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )

                            Text(
                                stringResource(R.string.set_pin_disclaimer),
                                Modifier.padding(16.dp, 0.dp, 0.dp, 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            Text(
                                stringResource(R.string.set_pin_disclaimer_1),
                                Modifier.padding(16.dp, 0.dp, 0.dp, 0.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            Text(
                                stringResource(R.string.set_pin_disclaimer_2),
                                Modifier.padding(16.dp, 0.dp, 0.dp, 0.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            Text(
                                stringResource(R.string.set_pin_disclaimer_3),
                                Modifier.padding(16.dp, 0.dp, 16.dp, 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Icon(
                            Icons.Rounded.Warning,
                            null,
                            Modifier.padding(8.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(accepted, onValueChange = { accepted = it }),
                    ) {
                        Checkbox(accepted, null)

                        Text(
                            stringResource(R.string.set_pin_confirmation),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth(),
                        Arrangement.spacedBy(16.dp),
                    ) {
                        Button(
                            { showStep1 = false },
                            ButtonType.PRIMARY,
                            stringResource(R.string.set_pin_create_pin),
                            accepted,
                        )

                        Button(
                            { onDismiss(null) },
                            ButtonType.OUTLINED,
                            stringResource(android.R.string.cancel),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.set_pin_dialog_title2),
                        Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Column(
                        Modifier.fillMaxWidth(),
                        Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            pin1,
                            { pin1 = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.pin_dialog_hint)) },
                            trailingIcon = {
                                IconButton({ passwordVisible1 = !passwordVisible1 }) {
                                    Icon(
                                        if (passwordVisible1) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        if (passwordVisible1) {
                                            stringResource(R.string.dialog_hide_pin)
                                        } else {
                                            stringResource(
                                                R.string.dialog_show_pin,
                                            )
                                        },
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible1) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                        )

                        OutlinedTextField(
                            pin2,
                            { pin2 = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.set_pin_confirm_hint)) },
                            trailingIcon = {
                                IconButton({ passwordVisible2 = !passwordVisible2 }) {
                                    Icon(
                                        if (passwordVisible2) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        if (passwordVisible2) {
                                            stringResource(R.string.dialog_hide_pin)
                                        } else {
                                            stringResource(
                                                R.string.dialog_show_pin,
                                            )
                                        },
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible2) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth(),
                        Arrangement.spacedBy(16.dp),
                    ) {
                        Button(
                            { onDismiss(pin1) },
                            ButtonType.PRIMARY,
                            stringResource(R.string.dialog_confirm),
                            checkPin(pin1) && pin1 == pin2,
                        )

                        Button(
                            { onDismiss(null) },
                            ButtonType.OUTLINED,
                            stringResource(android.R.string.cancel),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Checks PIN length against YubiKit's configured MIN_PIN_LEN and MAX_PIN_LEN,
 * which it would throw upon.
 *
 * Unfortunately, these values are not public, so we try to get them via reflection,
 * with a fallback to the values as they were when this check was created.
 */
private fun checkPin(pin: String): Boolean {
    val pinArray = pin.toCharArray()

    return pinArray.size >= getField("MIN_PIN_LEN", 4) && pinArray.size <= getField("MAX_PIN_LEN", 63)
}

private fun getField(
    name: String,
    default: Int,
): Int =
    try {
        ClientPin::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.getInt(null)
        }
    } catch (_: Throwable) {
        default
    }

@Preview
@Composable
private fun PreviewPinEntryDialog() {
    WalletTheme {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            SetPinDialog { }
        }
    }
}
