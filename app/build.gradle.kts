plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "ir.cmbtools.ledger"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.cmbtools.ledger"
        minSdk = 26
        targetSdk = 34
        // ── نسخه‌بندی ────────────────────────────────────────────────
        // برای هر آپدیت: versionCode یکی زیاد شود و versionName طبق قاعدهٔ زیر:
        //   تغییر بزرگ → 1.0.0     قابلیت جدید → 0.x.0     رفع باگ → 0.x.y
        versionCode = 21
        versionName = "0.11.0"
        resourceConfigurations += listOf("fa", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // برای نصب شخصی و امکان به‌روزرسانی، CI این variant غیرقابل‌دیباگ را
            // با همان گواهی ثابتِ cacheشده امضا می‌کند. هشدار «منبع ناشناس» Android همچنان ممکن است.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // برای نمایش شمارهٔ نسخه داخل اپ (BuildConfig.VERSION_NAME)
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
