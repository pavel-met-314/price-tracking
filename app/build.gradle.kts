plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.otsled"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.otsled"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Ключ лежит в репозитории намеренно: это отладочный ключ, и он должен быть один на
            // все прогоны CI. Без него каждый сборщик генерирует свой ~/.android/debug.keystore,
            // подписи APK из разных прогонов не совпадают, и `adb install -r` отказывает с
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE — на телефоне при этом остаётся старая версия.
            // Для публикации в RuStore/Play нужен отдельный release-ключ, и его в git класть
            // нельзя (см. README, раздел «Подпись и обновления»).
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }

        // Release-ключ в репозиторий не кладём никогда: он даёт право выпускать обновления от имени
        // приложения. Путь и пароли приходят из окружения CI (секреты репозитория) либо из
        // локального `gradle.properties`, которого в git нет. Если ключа нет — release собирается
        // неподписанным: это лучше, чем подписать сборку чужим ключом и потерять возможность
        // обновлять установленную версию.
        val releaseStoreFile = System.getenv("OTSLED_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
            ?: providers.gradleProperty("otsled.release.storeFile").orNull
        if (releaseStoreFile != null && file(releaseStoreFile).isFile) {
            create("release") {
                storeFile = file(releaseStoreFile)
                // PKCS12 — то, что современный keytool и openssl пишут по умолчанию; переопределяется
                // переменной окружения, если ключ вдруг окажется старым JKS.
                storeType = System.getenv("OTSLED_RELEASE_STORE_TYPE")?.takeIf { it.isNotBlank() }
                    ?: "PKCS12"
                storePassword = System.getenv("OTSLED_RELEASE_STORE_PASSWORD")
                    ?: providers.gradleProperty("otsled.release.storePassword").orNull
                keyAlias = System.getenv("OTSLED_RELEASE_KEY_ALIAS")
                    ?: providers.gradleProperty("otsled.release.keyAlias").orNull
                keyPassword = System.getenv("OTSLED_RELEASE_KEY_PASSWORD")
                    ?: providers.gradleProperty("otsled.release.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ключа может не быть (форк, локальная сборка без секретов) — тогда сборка выходит
            // неподписанной, и падать из-за этого не нужно: debug-путь от этого не зависит.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // assembleRelease не должен падать на предупреждениях стиля: они не мешают ни установке,
        // ни проверке подписи, а первый релиз из-за них пришлось бы разбирать вслепую.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
