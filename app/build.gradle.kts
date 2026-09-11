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
        versionCode = 23
        versionName = "1.7.2"
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

    // Media3 (ExoPlayer) 播放内核
    // 1.4.1 = 影视仓/TVBox 系播放器同代内核，行为最接近，Amlogic T1 兼容性最好
    // （影视仓 EXO 硬解在 T1 上 4K HEVC 全流畅）。1.8.0 曾出现 HEVC 硬解
    // DECODER_INIT_FAILED（解码器支持但创建失败），故固定此版本。
    // 注：v1.5.0 曾尝试 libVLC 3.5.1 内核（83MB），T1 实测仅 1080P 正常、
    // 4K 花屏/720P 黑屏，且体积过大，已回退纯 EXO 方案。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 扫码局域网管理：轻量 HTTP 服务 + 二维码编码（体积小，无额外权限）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
}