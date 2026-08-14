plugins {
    id("com.android.application")
}

android {
    namespace = "com.blankmediator.smsfromcsv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blankmediator.smsfromcsv"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.4.1"

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
