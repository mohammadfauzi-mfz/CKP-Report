plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.dsngroup.ckpreport"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.dsngroup.ckpreport"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "2.0"
    }
    // Kunci tanda tangan tetap: APK baru bisa dipasang menimpa versi lama tanpa uninstall (data aman).
    signingConfigs {
        create("ckp") {
            storeFile = file("ckp.keystore")
            storePassword = "ckpreport"
            keyAlias = "ckp"
            keyPassword = "ckpreport"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("ckp") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ckp")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime:2.9.0")
    testImplementation("junit:junit:4.13.2")
}
