import java.util.Properties

plugins {
    id(ThunderbirdPlugins.App.androidCompose)
}

android {
    namespace = "org.novabird.mail"

    defaultConfig {
        applicationId = "org.novabird.mail"
        testApplicationId = "org.novabird.mail.tests"

        // versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + stage (1..98 beta, 99 release)
        versionCode = 70001
        versionName = "0.7.0-beta1"

        // NovaBird: only Android versions that still get security updates (Android 14+, bulletin 2026-10);
        // also covers ONNX Runtime (API 24+). Upstream modules keep their own minSdk.
        minSdk = 34

        // NovaBird: 64-bit ARM only, keeps ONNX Runtime from adding ~100 MB of native code for other ABIs
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "CLIENT_INFO_APP_NAME", "\"NovaBird Mail\"")
    }

    androidResources {
        // Keep in sync with the resource string array "supported_languages"
        localeFilters += listOf(
            "ar",
            "be",
            "bg",
            "br",
            "ca",
            "co",
            "cs",
            "cy",
            "da",
            "de",
            "el",
            "en",
            "en-rGB",
            "eo",
            "es",
            "et",
            "eu",
            "fa",
            "fi",
            "fr",
            "fy",
            "ga",
            "gd",
            "gl",
            "hr",
            "hu",
            "in",
            "is",
            "it",
            "iw",
            "ja",
            "ko",
            "lt",
            "lv",
            "nb",
            "nl",
            "nn",
            "pl",
            "pt-rBR",
            "pt-rPT",
            "ro",
            "ru",
            "sk",
            "sl",
            "sq",
            "sr",
            "sv",
            "ta-rIN",
            "tr",
            "uk",
            "vi",
            "zh-rCN",
            "zh-rTW",
        )
    }

    // NovaBird: release key stays outside the repo. The properties file (keys nb.release.storeFile, .storePassword,
    // .keyAlias, .keyPassword) is named by the Gradle property novabird.signingFile, e.g. in the Gradle user home.
    val releaseSigning = (findProperty("novabird.signingFile") as String?)?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseSigning != null) {
            val signing = Properties().apply { releaseSigning.inputStream().use { load(it) } }
            create("novabirdRelease") {
                storeFile = file(signing.getProperty("nb.release.storeFile"))
                storePassword = signing.getProperty("nb.release.storePassword")
                keyAlias = signing.getProperty("nb.release.keyAlias")
                keyPassword = signing.getProperty("nb.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("novabirdRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-SNAPSHOT"

            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
    }

    flavorDimensions += listOf("app")
    productFlavors {
        create("foss") {
            dimension = "app"
            buildConfigField("String", "PRODUCT_FLAVOR_APP", "\"foss\"")
        }
    }

    bundle {
        language {
            // Don't split by language. Otherwise our in-app language switcher won't work.
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("kotlin/**")
        }

        resources {
            // NovaBird: kotlin/** (*.kotlin_builtins) bleibt drin. kotlin-reflect liegt im Klassenpfad und braucht
            // die Builtins für typeOf<T>() (QuickJs.evaluate<T> der NovaBird-Suche).
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
            )
        }
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.ui.compose.common)
    implementation(projects.core.ui.legacy.theme2.thunderbird)
    implementation(projects.feature.launcher)

    implementation(projects.legacy.core)
    implementation(projects.legacy.ui.legacy)
    implementation(projects.feature.search.implLegacy)
    implementation(libs.android.material)
    implementation(libs.glide)
    implementation(libs.okhttp)
    implementation(libs.minidns.hla)
    implementation(novabirdLibs.androidsvg)

    implementation(projects.core.featureflag)

    implementation(projects.feature.account.settings.impl)
    implementation(projects.feature.account.setup)
    implementation(projects.feature.mail.message.list.api)
    implementation(projects.feature.mail.message.list.internal)
    implementation(projects.feature.mail.message.reader.api)

    implementation(projects.feature.widget.messageList)
    implementation(projects.feature.widget.messageListGlance)
    implementation(projects.feature.widget.shortcut)
    implementation(projects.feature.widget.unread)

    implementation(projects.feature.telemetry.noop)
    implementation(projects.feature.funding.noop)
    implementation(projects.feature.migration.launcher.thunderbird)
    implementation(projects.feature.settings.import)
    implementation(projects.feature.onboarding.permissions)
    implementation(projects.feature.onboarding.migration.noop)
    implementation(projects.feature.thundermail.api)
    implementation(projects.feature.thundermail.k9mail)

    implementation(projects.feature.novabird.ai)
    implementation(projects.feature.novabird.branding)
    implementation(projects.feature.novabird.protection)
    implementation(projects.feature.novabird.search.api)
    implementation(projects.feature.novabird.search.internal)
    implementation(projects.feature.novabird.sync)
    implementation(projects.feature.novabird.vectors)
    debugImplementation(libs.kotlinx.serialization.json)
    debugImplementation(novabirdLibs.litertlm.android)

    implementation(libs.androidx.work.runtime)
    implementation(libs.jsoup)

    implementation(projects.feature.autodiscovery.api)
    debugImplementation(projects.backend.demo)
    debugImplementation(projects.feature.autodiscovery.demo)

    // Required for DependencyInjectionTest
    testImplementation(projects.feature.account.api)
    testImplementation(projects.feature.account.common)
    testImplementation(projects.feature.thundermail.internal.common)
    testImplementation(projects.plugins.openpgpApiLib.openpgpApi)
    testImplementation(projects.feature.changelog.api)
    testImplementation(projects.feature.changelog.internal)

    testImplementation(libs.appauth)
    testImplementation(libs.robolectric)
}
