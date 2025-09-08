package ui

import android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
fun EnterBaseUrlDialog(
    title: String,
    hint: String? = null,
    currentBaseUrl: String,
    onCanceled: () -> Unit,
    onUrlEntered: (url: String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = .7f))
                .clickable(onClick = onCanceled),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(16.dp),
        ) {
            var url by remember { mutableStateOf("") }

            Column(
                modifier =
                    Modifier
                        .padding(8.dp)
                        .clickable(onClick = onCanceled),
            ) {
                Text(
                    modifier =
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                            .fillMaxWidth(),
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )

                if (!hint.isNullOrBlank()) {
                    Text(
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth(),
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                TextField(
                    modifier =
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    singleLine = true,
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("New Base Url") },
                    placeholder = { Text(currentBaseUrl) },
                    isError = !url.isUrl,
                    supportingText = {
                        if (!url.isUrl) {
                            Text("Not a valid URL.")
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { onUrlEntered(url) }),
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onCanceled) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { onUrlEntered(url) }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun EnterBaseUrlDialogPreview() {
    EnterBaseUrlDialog(
        title = "title",
        hint = "hint",
        currentBaseUrl = "https://funke.wwwallet.org",
        onCanceled = {},
        onUrlEntered = {},
    )
}

private val String.isUrl: Boolean
    get() =
        try {
            URI.create(this).isAbsolute
            true
        } catch (_: Throwable) {
            false
        }
