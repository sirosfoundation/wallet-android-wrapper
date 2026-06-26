package org.siros.wwwallet

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siros.wwwallet.logging.YOLOLogger
import org.siros.wwwallet.storage.ProfileStorage
import java.net.URISyntaxException

@SuppressLint("StaticFieldLeak")
class MainViewModel : ViewModel() {
    lateinit var profileStorage: ProfileStorage

    var activity: MainActivity? = null
        set(value) {
            if (value != null) {
                profileStorage = ProfileStorage(value)

                viewModelScope.launch {
                    val baseurl = profileStorage.restore().baseUrl

                    // Only update, if this is not set, yet, as otherwise a race condition might
                    // occur, and this call would override an already acquired URL with which
                    // the app was called. (That is non-obvious from the code flow, but the
                    // race condition *will* appear otherwise!)
                    if (_url.value.isBlank()) {
                        _url.update {
                            baseurl
                        }
                    }
                }
            }

            field = value
        }

    private val _url: MutableStateFlow<String> = MutableStateFlow("")
    var url: StateFlow<String> = _url.asStateFlow()

    sealed class UpdateReason {
        object UserRequest : UpdateReason()

        object DeeplinkRequest : UpdateReason()

        data class WebpageError(
            val errorMessage: String,
        ) : UpdateReason()
    }

    private val _updateBaseUrl: MutableStateFlow<UpdateReason?> = MutableStateFlow(null)
    var updateBaseUrl: StateFlow<UpdateReason?> = _updateBaseUrl.asStateFlow()

    suspend fun browseToUrl(url: String) {
        _url.update { "" }

        _url.update {
            try {
                val uri = url.toUri()
                when (uri.scheme) {
                    "http" -> sanitize(uri).toString()

                    "https" -> sanitize(uri).toString()

                    "wwwallet" -> {
                        when (uri.host) {
                            "change-provider" -> changeProviderRequested(uri) ?: it
                            else -> url
                        }
                    }

                    "openid4vp", "haip" ->
                        Uri
                            .Builder()
                            .scheme("https")
                            .authority(getBaseUrl().toUri().authority)
                            .path("/cb")
                            .encodedQuery(uri.encodedQuery)
                            .encodedFragment(uri.encodedFragment)
                            .build()
                            .toString()

                    else -> url
                }
            } catch (uriException: URISyntaxException) {
                YOLOLogger.e(tagForLog, "URL ERROR, routing back to base url.", uriException)
                getBaseUrl()
            }
        }
    }

    fun onBackPressed() {
        _url.update { "webview://back" }
    }

    /**
     * Called when [org.siros.wwwallet.facetec.PhotoIdMatchActivity] returns. If facetec-api
     * accepted the scan, [credentialOfferURI] carries its query parameters straight to the
     * tenant base URL so the WebView starts processing the credential offer immediately,
     * without showing any intermediate screen first.
     */
    fun photoIdMatchCompleted(credentialOfferURI: String?) {
        if (credentialOfferURI.isNullOrBlank()) {
            YOLOLogger.i(tagForLog, "photoIdMatchCompleted: no credentialOfferURI, not navigating.")
            return
        }

        viewModelScope.launch {
            val target =
                getBaseUrl()
                    .toUri()
                    .buildUpon()
                    .encodedQuery(credentialOfferURI.toUri().encodedQuery)
                    .build()
                    .toString()

            YOLOLogger.i(tagForLog, "photoIdMatchCompleted: navigating WebView to $target")

            browseToUrl(target)
        }
    }

    fun parseIntent(intent: Intent) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = intent.data ?: return@launch

            browseToUrl(uri.toString())
        }
    }

    fun copyToClipboard(text: String) {
        val activity = activity

        if (activity == null) {
            YOLOLogger.e(tagForLog, "NULL activity, closing.")
            return
        }

        val manager =
            activity.applicationContext.getSystemService(ClipboardManager::class.java)

        val clip = ClipData.newPlainText("wwWallet log", text)
        manager.setPrimaryClip(clip)
    }

    suspend fun getBaseUrl(): String = profileStorage.restore().baseUrl

    suspend fun setBaseUrl(value: String): String {
        updateBaseUrlCanceled()

        val sanitized =
            when {
                value.startsWith("https://") -> sanitize(value).toString()
                value.startsWith("http://") -> sanitize(value).toString()
                value.isNotEmpty() && value.first().isLetter() -> sanitize("https://$value").toString()
                else -> value // for direct ip addresses
            }

        profileStorage.store(profileStorage.restore().copy(baseUrl = sanitized))

        return sanitized
    }

    fun openedFromShortcut(shortcut: String?) {
        if (shortcut == "shortcut_open_custom") {
            updateBaseUrl()

            return
        }

        val shortcutMap =
            BuildConfig::class.java.declaredFields
                .filter { it.name.startsWith("BASE_DOMAIN") }
                .associate { "shortcut_open_${it.name.lowercase()}" to it.get(null) as String }

        val domain = shortcutMap[shortcut]

        if (!domain.isNullOrEmpty()) {
            viewModelScope.launch {
                browseToUrl(setBaseUrl("https://$domain/"))
            }
        } else {
            YOLOLogger.e(tagForLog, "'$shortcut' is not a valid shortcut identifier!")
        }
    }

    fun updateBaseUrlCanceled() {
        _updateBaseUrl.update { null }
    }

    fun updateBaseUrl(reason: UpdateReason = UpdateReason.UserRequest) {
        _updateBaseUrl.update { reason }
    }

    fun errorReceived(description: String) {
        updateBaseUrl(
            UpdateReason.WebpageError(description),
        )
    }

    private suspend fun changeProviderRequested(uri: Uri): String? {
        if (uri.query == null) {
            updateBaseUrl(reason = UpdateReason.DeeplinkRequest)
            return null
        }

        val queryParameters =
            uri.query?.split("&")?.associate {
                val (k, v) = it.split("=")
                k to v
            } ?: emptyMap()

        if ("provider" in queryParameters) {
            return setBaseUrl(queryParameters.getOrDefault("provider", getBaseUrl()))
        } else {
            updateBaseUrl(reason = UpdateReason.DeeplinkRequest)
            return null
        }
    }

    private fun sanitize(url: String): Uri = sanitize(url.toUri())

    /**
     * Modify this for easier debugging with non-TLS encrypted dev environments.
     */
    private fun sanitize(uri: Uri): Uri =
        uri
            .buildUpon()
            .scheme("https")
            .build()
}
