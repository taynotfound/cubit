import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (not in git). Without it, release builds come out unsigned, which
// is what F-Droid's build server expects before it checks the result against our signed APK.
val keystore = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.cubit.game"
    compileSdk = 34
    defaultConfig {
        applicationId = "de.taymaerz.cubit"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "1.6.0"
    }
    buildFeatures { buildConfig = true }
    // Two stores, one app: the GitHub build checks GitHub for new releases; the F-Droid build leaves updates to F-Droid.
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store"; buildConfigField("boolean", "UPDATE_CHECK", "true") }
        create("fdroid") { dimension = "store"; buildConfigField("boolean", "UPDATE_CHECK", "false") }
    }
    signingConfigs {
        if (keystore.isNotEmpty()) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
            vcsInfo.include = false // the embedded git commit would make builds from a source tarball differ
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Keep the APK reproducible: no build-time dependency metadata blob (F-Droid also can't read Google's encrypted one).
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
