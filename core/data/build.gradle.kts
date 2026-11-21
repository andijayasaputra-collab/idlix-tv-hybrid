import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}

android {
    namespace = "com.idlix.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        // One source of truth for both bundled fallback and remote configuration.
        val bundledSource = rootProject.file("remote-source.json").readText()
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        buildConfigField("String", "BUNDLED_REMOTE_SOURCE_JSON", quoted(bundledSource))
        val remoteUrl = localProperties.getProperty("remoteSourceConfigUrl")
            ?: "https://raw.githubusercontent.com/andijayasaputra-collab/idlix-tv-hybrid/main/remote-source.json"
        buildConfigField("String", "REMOTE_SOURCE_CONFIG_URL", quoted(remoteUrl))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Network & Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
