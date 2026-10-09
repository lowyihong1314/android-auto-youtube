import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing and the Play package name, resolved from signing.properties
// (gitignored), then -P project properties, then environment variables.
val signingProps = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyName: String, envName: String): String? =
    (signingProps.getProperty(propertyName)
        ?: project.findProperty(propertyName)?.toString()
        ?: System.getenv(envName))
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile =
    signingValue("carvideoReleaseStoreFile", "CARVIDEO_RELEASE_STORE_FILE")
        ?: "carvideo-release.keystore"
val releaseStorePassword =
    signingValue("carvideoReleaseStorePassword", "CARVIDEO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias =
    signingValue("carvideoReleaseKeyAlias", "CARVIDEO_RELEASE_KEY_ALIAS")
val releaseKeyPassword =
    signingValue("carvideoReleaseKeyPassword", "CARVIDEO_RELEASE_KEY_PASSWORD")

// The package name a Play Console listing is bound to is private build config, not
// source: keep it in signing.properties so a public checkout stays neutral.
val playApplicationId =
    signingValue("carvideoApplicationId", "CARVIDEO_APPLICATION_ID") ?: "my.kang.carvideo"

val hasReleaseSigning =
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

// Fail early and clearly rather than emitting an unsigned artifact Play will reject.
if (!hasReleaseSigning && gradle.startParameter.taskNames.any { it.contains("elease") }) {
    throw GradleException(
        "Release signing is not configured. Create signing.properties with " +
            "carvideoReleaseStorePassword / carvideoReleaseKeyAlias / carvideoReleaseKeyPassword, " +
            "or set CARVIDEO_RELEASE_STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD."
    )
}

android {
    // Kotlin/resource package stays my.kang.carvideo; only the Play package name
    // (applicationId) has to match your Play Console listing - see playApplicationId.
    namespace = "my.kang.carvideo"
    compileSdk = 36

    defaultConfig {
        applicationId = playApplicationId
        minSdk = 26
        targetSdk = 36
        // Overridable so a versionCode clash on an upload can be fixed without
        // editing this file: ./gradlew assembleRelease -PappVersionCode=7
        versionCode = (project.findProperty("appVersionCode")?.toString()?.toIntOrNull()) ?: 1
        versionName = (project.findProperty("appVersionName")?.toString()) ?: "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Lets a debug build sit next to the Play install instead of clashing
            // with its signature. Android Auto only runs the Play one in a real car;
            // the debug one is for the phone screen and the DHU.
            applicationIdSuffix = ".debug"
        }
        release {
            // Left off on purpose: R8 would need extra keep rules for the
            // reflection the Car App Library host protocol does.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Research build: never let lint block assembling an APK.
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Car App Library - NAVIGATION category gives us the raw car Surface.
    implementation("androidx.car.app:app:1.7.0")

    // ComponentActivity -> OnBackPressedDispatcher. Apps targeting SDK 36 get
    // predictive back, where Activity.onBackPressed() is never called.
    implementation("androidx.activity:activity-ktx:1.13.0")

    // Phone-side settings screen: cards, sliders, switches, toggle groups.
    implementation("com.google.android.material:material:1.12.0")
}
