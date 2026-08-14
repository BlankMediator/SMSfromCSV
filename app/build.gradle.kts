plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.weddingsmssender"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.weddingsmssender"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
