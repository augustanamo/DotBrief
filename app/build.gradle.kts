plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.augustana.dotbrief"
    // 本机只装了 platforms;android-36，故用 36 编译；targetSdk 仍是 34，运行时行为不变。
    // AGP 8.5.2 只测到 compileSdk 34，会有一条"建议升级 AGP"的警告，不影响产物。
    compileSdk = 36
    // AGP 8.5.2 默认索要 build-tools 34.0.0（本机没装），显式指向已装的 35.0.0。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.augustana.dotbrief"
        minSdk = 26          // Android 8.0：NotificationListenerService / 自适应图标 / java.time 均可直接用
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 签名来自仓库根目录的 debug.keystore（AGP 默认的 ~/.android/debug.keystore 是每台机器
        // 各自随机生成的，换一台机器构建，包就装不进装过旧包的手机——报
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE）。收进仓库后指纹恒定，任何机器构建的包都能互相覆盖安装。
        // 密码/别名沿用 AGP 默认值（android / androiddebugkey，PKCS12 下两者相同），不必显式写。
        // 注意：这只是 debug 密钥，自用分发够用；上架商店请另签正式密钥。
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // release 也指同一份 keystore：否则 assembleRelease 出来是未签名包装不了；
            // 且 debug/release 签名一致，两种包之间也能互相覆盖安装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Kotlin 1.9.x 需要显式指定 Compose 编译器版本
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// Room 的 schema 导出目录（Phase 2 建库后会自动生成 json，便于写迁移测试）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // ---------- 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // ---------- Compose（设置界面）----------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---------- 配置存储：DataStore ----------
    implementation(libs.androidx.datastore.preferences)

    // ---------- 本地库：Room（Phase 2 起使用）----------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---------- 网络：OkHttp + Retrofit + kotlinx.serialization ----------
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ---------- RSS / Atom 解析 ----------
    implementation(libs.rssparser)

    // ---------- 后台任务 ----------
    implementation(libs.androidx.work.runtime.ktx)

    // ---------- 桌面小组件：Jetpack Glance ----------
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // ---------- 测试 ----------
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
