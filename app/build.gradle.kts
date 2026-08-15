plugins {
    id("com.android.application")
}

android {
    namespace = "com.blankmediator.smsfromcsv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blankmediator.smsfromcsv.mms"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.0"

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
