plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.convokit.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.convokit.example"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "CONVOKIT_API_URL", "\"https://convokit-backend.onrender.com\"")
        buildConfigField("String", "CONVOKIT_CLIENT_ID", "\"998da6ce-2572-42b1-8c60-734ce09c88e4\"")
        buildConfigField("String", "DEMO_BACKEND_URL", "\"https://convokit-open-chatroom.vercel.app\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
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
    implementation("app.convokit:convokit-android:0.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")
}
