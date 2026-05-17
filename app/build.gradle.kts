import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") // 读取 app/google-services.json
    id("com.google.devtools.ksp")        // ✅ 在 module 里也要应用
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.cvdoor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cvdoor.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // 可在 local.properties 中覆蓋：cvdoor.api.base.url=https://...
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
        val baseUrl = props.getProperty("cvdoor.api.base.url", "http://35.183.25.180:8000/")
        buildConfigField("String", "API_BASE_URL", "\"$baseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
            storeFile = rootProject.file(
                props.getProperty("cvdoor.keystore.file", "cvdoor-release.keystore")
            )
            storePassword = props.getProperty("cvdoor.keystore.password", "cvdoor2024")
            keyAlias     = props.getProperty("cvdoor.key.alias", "cvdoor")
            keyPassword  = props.getProperty("cvdoor.key.password", "cvdoor2024")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ===== Compose =====
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.android.billingclient:billing-ktx:6.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ===== Retrofit / OkHttp =====
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // ===== Room (用 ksp 替代 kapt) =====
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")  // ✅
    implementation("androidx.room:room-ktx:2.6.1")

    // ===== DataStore =====
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ===== Google Sign-In + Firebase Auth =====
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // ===== PDF =====
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
