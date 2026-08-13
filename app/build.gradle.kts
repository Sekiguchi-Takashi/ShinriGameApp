plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appathy.shinrigame"
    compileSdk = 34

    // ビルドのたびに鍵が変わると署名が一致せず、上書き更新ができなくなる。
    // GitHub Actions のランナーには鍵が残らないため、固定の鍵をリポジトリに置いて使う。
    signingConfigs {
        create("shared") {
            storeFile = file("shinri.keystore")
            storePassword = "shinrigame"
            keyAlias = "shinri"
            keyPassword = "shinrigame"
        }
    }

    defaultConfig {
        applicationId = "com.appathy.shinrigame"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "1.2.1"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
}
