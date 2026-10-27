package org.siros.wwwallet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yubico.yubikit.fido.client.MultipleAssertionsAvailable
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import org.siros.wwwallet.R

@Composable
fun CredentialSelectionDialog(
    assertions: MultipleAssertionsAvailable,
    onDismiss: (PublicKeyCredential?) -> Unit
) {
    Dialog(onDismissRequest = { }) {
        CredentialSelectionDialogContent(
            assertions.users.map { it.displayName }.toTypedArray()
        ) {
            onDismiss(if (it == null) null else assertions.select(it))
        }
    }
}

@Composable
fun CredentialSelectionDialogContent(
    items: Array<String>,
    onDismiss: (Int?) -> Unit
) {
    Surface(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        RoundedCornerShape(16.dp),
        Color.White
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            Arrangement.spacedBy(32.dp),
            Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.credential_dialog_title),
                Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                stringResource(R.string.credential_dialog_explanation),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )

            Box(
                Modifier.fillMaxWidth().heightIn(max = 300.dp)
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(items) { index, name ->
                        ListItem(
                            { Text(name) },
                            Modifier.clickable { onDismiss(index) },
                            colors = ListItemDefaults.colors(Color.Transparent)
                        )

                        if (index < items.size - 1) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }

            Button(
                { onDismiss(null) },
                ButtonType.OUTLINED,
                stringResource(android.R.string.cancel)
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCredentialSelectionDialog() {
    MaterialTheme(
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            CredentialSelectionDialogContent(
                arrayOf("Foo", "Bar", "Baz", "Bam", "Bla")
            ) { }
        }
    }
}