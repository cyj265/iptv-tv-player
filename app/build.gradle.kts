plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cyj265.iptvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cyj265.iptvplayer"
        minSdk = 21
        targetSdk = 34
        versionCode = 15
        versionName = "1.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 自用侧载：用 debug 密钥签名，保证 CI 产出的 APK 可直接安装。
            // 显式指定 keystore 路径与口令：避免 Gradle 打不开时静默自动生成新 keystore
            // 导致每次构建签名都变、无法覆盖安装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // VLC (libVLC) 播放内核
    // 3.5.1 = VLC Android 稳定版，TiviMate/Kodi 系同族。HEVC/H.265 软硬解自动
    // 切换（硬解失败自动回退软解），Amlogic T1 (Android 7) 实测全格式流畅。
    // 之前 Media3 ExoPlayer 在 T1 上 HEVC 硬解反复失败（init failed / 只出声
    // 不出画），故整体替换为 libVLC。
    implementation("org.videolan.android:libvlc-all:3.5.1")
}
