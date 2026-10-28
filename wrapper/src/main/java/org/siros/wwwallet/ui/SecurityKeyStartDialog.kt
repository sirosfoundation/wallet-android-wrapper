package org.siros.wwwallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.siros.wwwallet.R

@Composable
fun SecurityKeyStartDialog(onDismiss: () -> Unit) {
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
                    stringResource(R.string.start_dialog_title),
                    Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.Center,
                    Alignment.Top,
                ) {
                    Column(
                        Modifier.weight(43f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.width(80.dp).height(80.dp).background(colorResource(R.color.button_outlined_background)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_usb),
                                null,
                                tint = colorResource(R.color.button_primary_background),
                            )
                        }

                        Text(
                            stringResource(R.string.start_dialog_message1),
                            Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Column(
                        Modifier.weight(14f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.start_dialog_or),
                            Modifier.padding(top = 24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }

                    Column(
                        Modifier.weight(43f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.width(80.dp).height(80.dp).background(colorResource(R.color.button_outlined_background)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_nfc),
                                null,
                                tint = colorResource(R.color.button_primary_background),
                            )
                        }

                        Text(
                            stringResource(R.string.start_dialog_message2),
                            Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

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
private fun PreviewSecurityKeyStartDialog() {
    MaterialTheme(
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            SecurityKeyStartDialog {}
        }
    }
}
