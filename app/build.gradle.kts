import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.busseatalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.busseatalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.0"
    }

    // Release signing key; keystore.properties holds the passwords and isn't part of the source.
    val keystoreProps = rootProject.file("release/keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            create("release") {
                val p = Properties().apply { keystoreProps.inputStream().use(::load) }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking stays off: kotlinx.serialization and JavaMail rely on reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { it.systemProperty("live", System.getProperty("live") ?: "false"); it.testLogging.showStandardStreams = true }
    packaging {
        resources.excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 brings an old fragment library that crashes with the new permission API; use a current one.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
