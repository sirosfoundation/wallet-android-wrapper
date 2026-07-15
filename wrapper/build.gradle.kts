import build.env
import build.envOrDefault
import build.fileFromEnv
import org.gradle.api.DefaultTask
import java.io.File


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.serialization)
}

val faceTecToken = envOrDefault("FACETEC_API_BEARER_TOKEN", "")
val includeFaceTec = faceTecToken.isNotBlank()

android {
    namespace = "org.siros.wwwallet"
    compileSdk = 37

    defaultConfig {
        applicationId = env("WWWALLET_ANDROID_APPLICATION_ID")
        minSdk = 33
        targetSdk = 37
        versionCode = (property("wallet.versionCode") as String).toInt()
        versionName = property("wallet.versionName") as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // FaceTec only ships native libraries for these ABIs.
        if (includeFaceTec) {
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    sourceSets {
        getByName("main") {
            if (includeFaceTec) {
                kotlin.directories.add("src/facetec/java")
            }
        }
    }

    signingConfigs {
        val allConfig =
            create("all") {
                keyAlias = env("WWWALLET_ANDROID_KEY_ALIAS")
                keyPassword = env("WWWALLET_ANDROID_KEY_PASSWORD")
                storePassword = env("WWWALLET_ANDROID_STORE_PASSWORD")
                storeFile = fileFromEnv(project, "WWWALLET_ANDROID_STORE_B64", "wwwallet.keystore")
            }

        create("release") {
            initWith(allConfig)
            keyAlias = env("WWWALLET_ANDROID_RELEASE_KEY_ALIAS")
        }
    }

    buildTypes {
        all {
            @Suppress("UNCHECKED_CAST")
            val baseDomains = rootProject.extra["baseDomains"] as List<String>
            var i = 0

            for (baseDomain in baseDomains) {
                i += 1
                buildConfigField("String", "BASE_DOMAIN$i", "\"$baseDomain\"")
            }

            resValue("string", "shortcut_open_base_domain1", baseDomains[0])
            resValue("string", "shortcut_open_base_domain2", baseDomains.getOrNull(1) ?: baseDomains[0])
            resValue("string", "shortcut_open_base_domain3", baseDomains.getOrNull(2) ?: baseDomains[0])

            buildConfigField("Boolean", "SHOW_URL_ROW", "false")
            buildConfigField("Boolean", "VISUALIZE_INJECTION", "false")

            // facetec-api endpoint that proxies FaceTec SDK session requests and turns
            // accepted photo-ID matches into a credentialOfferURI. The bearer token is a
            // secret and must only come from the environment / local.properties, never
            // be hardcoded here.
            //
            // WWWALLET_FACETEC_API_BASE_URL overrides the default for local development:
            // point it at a Cloudflare quick tunnel URL from facetec-api's `make tunnel`
            // (e.g. "https://xyz.trycloudflare.com/process-request") to test against a
            // facetec-api instance running on your own machine instead of the shared dev
            // deployment. Leave unset to use the shared dev deployment as before.
            buildConfigField(
                "String",
                "FACETEC_API_BASE_URL",
                "\"${envOrDefault("WWWALLET_FACETEC_API_BASE_URL", "https://ft1-dev-api.common.siros.org/v1/process-request")}\"",
            )

            buildConfigField(
                "String",
                "FACETEC_API_BEARER_TOKEN",
                "\"$faceTecToken\"",
            )

            signingConfig = signingConfigs.getByName("all")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            buildConfigField("Boolean", "VISUALIZE_INJECTION", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The FaceTec SDK locale folders (values-af, values-de, …) only carry
        // facetec-strings.xml, not a full translation of this app's own strings.xml —
        // that's intentional, not a missing translation.
        disable += "MissingTranslation"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            excludes += "COPYING"
        }

        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.registry.digitalcredentials.openid)
    implementation(libs.androidx.registry.digitalcredentials.sdjwtvc)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.playservices)
    implementation(libs.coroutines)
    implementation(libs.webkit)
    implementation(libs.ausweis)
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.fido)
    implementation(libs.logback)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.cbor)
    implementation(libs.cose)
    implementation(libs.kotlinx.serialization)

    if (includeFaceTec) {
        implementation(libs.facetec.sdk)
    }

    // digital credentials api
    implementation(libs.playservices.identity.credentials)
    implementation(libs.androidx.registry.provider)
    implementation(libs.androidx.registry.provider.play.services)
    implementation(libs.androidx.registry.digitalcredentials.mdoc)

    testImplementation(libs.junit)
    testImplementation(libs.test.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    verbose.set(true)
    android.set(true)

    additionalEditorconfig.set(
        mapOf(
            "max_line_length" to "200",
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
        ),
    )
}

abstract class GenerateManifestTask : DefaultTask() {
    @get:Input
    abstract val baseDomains: ListProperty<String>

    @get:Input
    abstract val showShortcuts: Property<Boolean>

    @get:Input
    abstract val includeFt: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val domains = baseDomains.get()
        val outFile = outputFile.get().asFile

        outFile.parentFile.mkdirs()

        val shortcuts =
            if (showShortcuts.get()) {
                """
                        <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />"""
            } else {
                ""
            }

        val faceTecActivity =
            if (includeFt.get()) {
                """
                    <activity
                        android:name=".facetec.PhotoIdMatchActivity"
                        android:configChanges="orientation|keyboardHidden|screenSize"
                        android:exported="false"
                        android:screenOrientation="userPortrait"
                        android:theme="@style/Theme.Wwwallet" />
            """
            } else {
                ""
            }

        val intentFilters =
            domains.joinToString("\n") { domain ->
                """
                        <intent-filter android:autoVerify="true">
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="http" />
                            <data android:scheme="https" />
                            <data android:host="$domain" />
                        </intent-filter>"""
            }

        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    $faceTecActivity 
                    <activity 
                        android:name="org.siros.wwwallet.MainActivity"
                        android:exported="true">
                        $shortcuts
                        $intentFilters
                    </activity>
                </application>
            </manifest>
            """.trimIndent()

        outFile.writeText(xml)
    }
}

androidComponents {
    onVariants { variant ->

        val manifestTaskProvider =
            tasks.register(
                "generate${variant.name.replaceFirstChar { it.uppercase() }}Manifest",
                GenerateManifestTask::class.java,
            ) {
                @Suppress("UNCHECKED_CAST")
                val baseDomains = rootProject.extra["baseDomains"] as List<String>
                this.baseDomains.set(baseDomains)

                showShortcuts.set(variant.debuggable)

                includeFt.set(includeFaceTec)

                outputFile.set(File("generated/manifests/${variant.name}/AndroidManifest.xml"))
            }

        variant.sources.manifests.addGeneratedManifestFile(
            manifestTaskProvider,
        ) { it.outputFile }
    }
}
