package org.siros.wwwallet.storage

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.siros.wwwallet.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class Profile(
    @SerialName("baseurl")
    val baseUrl: String,
)

/**
 * https://developer.android.com/topic/libraries/architecture/datastore
 * https://developer.android.com/jetpack/androidx/releases/datastore#1.3.0-alpha07
 */
class ProfileStorage(
    private val context: Context,
) {
    companion object {
        private const val LEGACY_FILE_NAME = "profile"
        private const val ENCRYPTED_FILE_NAME = "profile_encrypted"

        private const val KEYSET_NAME = "profile_keyset"
        private const val PREFERENCE_FILE = "profile_key_prefs"
        private const val MASTER_KEY_URI = "android-keystore://_androidx_security_master_key_"

        private object ProfileSerializer : Serializer<Profile> {
            override val defaultValue: Profile = Profile("https://${BuildConfig.BASE_DOMAIN1}/")

            private val json = Json { ignoreUnknownKeys = true }

            override suspend fun readFrom(input: InputStream): Profile =
                withContext(Dispatchers.IO) {
                    try {
                        json.decodeFromString<Profile>(input.readBytes().decodeToString())
                    } catch (t: Throwable) {
                        throw CorruptionException("Unable to read Profile", t)
                    }
                }

            override suspend fun writeTo(
                t: Profile,
                output: OutputStream,
            ) = withContext(Dispatchers.IO) {
                output.write(json.encodeToString(t).encodeToByteArray())
            }
        }

        private suspend fun createDataStore(context: Context): DataStore<Profile> {
            val unencryptedFile = context.preferencesDataStoreFile(LEGACY_FILE_NAME)
            var dataStore: DataStore<Profile>

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
                        ProfileSerializer,
                        ENCRYPTED_FILE_NAME.encodeToByteArray(),
                    )

                val encryptedFile = context.preferencesDataStoreFile(ENCRYPTED_FILE_NAME)

                val shouldMigrate = !encryptedFile.exists()

                dataStore =
                    DataStoreFactory.create(
                        serializer,
                        ReplaceFileCorruptionHandler({ ProfileSerializer.defaultValue }),
                        produceFile = { encryptedFile },
                    )

                // Migrate preferences and remove legacy unencrypted file.
                if (shouldMigrate) migrate(unencryptedFile, dataStore)
            } catch (_: Throwable) {
                // Ignored. If we really need to, we fall back to unencrypted.

                dataStore =
                    DataStoreFactory.create(
                        ProfileSerializer,
                        ReplaceFileCorruptionHandler({ ProfileSerializer.defaultValue }),
                        produceFile = { unencryptedFile },
                    )
            }

            return dataStore
        }

        private suspend fun migrate(
            unencryptedFile: File,
            dataStore: DataStore<Profile>,
        ) {
            if (!unencryptedFile.exists()) return

            try {
                val unencryptedProfile =
                    unencryptedFile
                        .inputStream()
                        .use {
                            ProfileSerializer.readFrom(it)
                        }

                dataStore.updateData {
                    unencryptedProfile
                }

                unencryptedFile.delete()
            } catch (_: Throwable) {
                // Ignored. Better no preferences than no app.
            }
        }
    }

    private lateinit var dataStore: DataStore<Profile>

    suspend fun store(profile: Profile) {
        if (!this::dataStore.isInitialized) {
            dataStore = createDataStore(context.applicationContext)
        }

        dataStore.updateData {
            profile
        }
    }

    suspend fun restore(): Profile {
        if (!this::dataStore.isInitialized) {
            dataStore = createDataStore(context.applicationContext)
        }

        return dataStore.data.first()
    }
}
