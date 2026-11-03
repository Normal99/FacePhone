plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.family.grandmaphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.grandmaphone"
        minSdk = 29
        // The phone is on Android 11 and the app is sideloaded, so it targets that version.
        targetSdk = 30
        // Minutes since 2026, so every build is newer than the last and one-tap updates accept it.
        versionCode = (providers.gradleProperty("versionCode").orNull?.toInt()
            ?: ((System.currentTimeMillis() / 1000 - 1_767_225_600) / 60).toInt())
        versionName = "1.$versionCode"
        // Releases on this GitHub repository are where the update button looks.
        val updateRepo = providers.gradleProperty("updateRepo").orNull ?: "Normal99/grandma-phone"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    buildTypes {
        release {
            // Debug-key signing suits sideloading from one computer. Use your own key to distribute.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
