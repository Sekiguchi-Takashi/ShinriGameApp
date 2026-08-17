plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appathy.shinrigame"
    compileSdk = 34

    // 配布ビルド（タグ発行 → release.yml）は ci/appathy.keystore で署名される。
    // ここで固定するのは debug だけ。ランナーには鍵が残らず、
    // 何もしないとビルドのたびに別のデバッグ鍵になって上書き更新ができなくなる。
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
        versionCode = 23
        versionName = "2.0.3"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
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
