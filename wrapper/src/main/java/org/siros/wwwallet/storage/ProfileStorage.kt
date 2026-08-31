package org.siros.wwwallet.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.buffer
import okio.sink
import okio.source
import org.siros.wwwallet.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.map
import kotlin.collections.toTypedArray

data class Profile(
    val baseUrl: String,
)

class ProfileStorage(
    private val context: Context,
) {
    companion object {
        private const val LEGACY_FILE_NAME = "profile"
        private const val ENCRYPTED_FILE_NAME = "profile_encrypted"

        private const val KEYSET_NAME = "profile_keyset"
        private const val PREFERENCE_FILE = "profile_key_prefs"
        private const val MASTER_KEY_URI = "android-keystore://_androidx_security_master_key_"

        private val BASE_URL_KEY = stringPreferencesKey("baseurl")

        private object PreferencesDataStoreSerializer : Serializer<Preferences> {
            override val defaultValue: Preferences = emptyPreferences()

            override suspend fun readFrom(input: InputStream): Preferences = PreferencesSerializer.readFrom(input.source().buffer())

            override suspend fun writeTo(
                t: Preferences,
                output: OutputStream,
            ) {
                PreferencesSerializer.writeTo(t, output.sink().buffer())
            }
        }

        private suspend fun createDataStore(context: Context): DataStore<Preferences> {
            val unencryptedFile = context.preferencesDataStoreFile(LEGACY_FILE_NAME)
            var dataStore: DataStore<Preferences>

            try {
                AeadConfig.register()

                // Creates a key to encrypt our preferences which is bound to an Android keystore master key.
                val aead =
                    AndroidKeysetManager
                        .Builder()
                        .withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
                        .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
                        .withMasterKeyUri(MASTER_KEY_URI)
                        .build()
                        .keysetHandle
                        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

                val serializer =
                    AeadSerializer(
                        aead,
                        PreferencesDataStoreSerializer,
                        ENCRYPTED_FILE_NAME.encodeToByteArray(),
                    )

                val encryptedFile = context.preferencesDataStoreFile(ENCRYPTED_FILE_NAME)

                val shouldMigrate = !encryptedFile.exists()

                dataStore =
                    DataStoreFactory.create(
                        serializer,
                        ReplaceFileCorruptionHandler({ emptyPreferences() }),
                        produceFile = { encryptedFile },
                    )

                // Migrate preferences and remove legacy unencrypted file.
                if (shouldMigrate) migrate(unencryptedFile, dataStore)
            } catch (_: Throwable) {
                // Ignored. If we really need to, we fall back to unencrypted.

                dataStore =
                    DataStoreFactory.create(
                        PreferencesDataStoreSerializer,
                        ReplaceFileCorruptionHandler({ emptyPreferences() }),
                        produceFile = { unencryptedFile },
                    )
            }

            return dataStore
        }

        private suspend fun migrate(
            unencryptedFile: File,
            dataStore: DataStore<Preferences>,
        ) {
            if (!unencryptedFile.exists()) return

            try {
                val unencryptedPrefs =
                    unencryptedFile
                        .inputStream()
                        .use {
                            PreferencesDataStoreSerializer.readFrom(it)
                        }.asMap()
                        .map { (key, value) ->
                            @Suppress("UNCHECKED_CAST")
                            (key as Preferences.Key<Any>) to value
                        }.toTypedArray()

                dataStore.edit { preferences ->
                    preferences.putAll(*unencryptedPrefs)
                }

                unencryptedFile.delete()
            } catch (_: Throwable) {
                // Ignored. Better no preferences than no app.
            }
        }
    }

    private lateinit var dataStore: DataStore<Preferences>

    suspend fun store(profile: Profile) {
        if (!this::dataStore.isInitialized) {
            dataStore = createDataStore(context.applicationContext)
        }

        dataStore.edit { store ->
            store[BASE_URL_KEY] = profile.baseUrl
        }
    }

    suspend fun restore(): Profile {
        if (!this::dataStore.isInitialized) {
            dataStore = createDataStore(context.applicationContext)
        }

        val profile =
            dataStore.data
                .map { preferences ->
                    Profile(
                        baseUrl = preferences[BASE_URL_KEY] ?: "https://${BuildConfig.BASE_DOMAIN1}/",
                    )
                }.first()

        return profile
    }
}
