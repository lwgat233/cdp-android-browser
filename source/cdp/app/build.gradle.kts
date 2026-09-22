plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    packaging {
        // ffmpeg-kit 要按传统方式把 .so 解出来加载（它官方建议这么配）
        jniLibs { useLegacyPackaging = true }
    }
    namespace = "dev.cdp"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.cdp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // addWebMessageListener（按 origin 开放的 JS↔原生通道） + addDocumentStartJavaScript（脚本在文档开始前注入）
    implementation("androidx.webkit:webkit:1.11.0")
    // 内置 ffmpeg（清单第 7/8 条）：用预编译的 ffmpeg-kit（LGPL 版，不含 x264 这类 GPL 组件）。
    // 必须用 **https** 变体：min 变体不带 TLS，连 https:// 都打不开（报 "Protocol not found"），
    // 那样只能抓 http 的流——B 站这类 CDN 全是 https，等于没用。https 变体 = min + HTTPS，约 35MB/ABI。
    implementation("com.arthenica:ffmpeg-kit-https:6.0-2")
}
