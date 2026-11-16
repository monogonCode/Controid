import java.security.MessageDigest
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.controid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.controid"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}

val syncWebApk = tasks.register("syncWebApk") {
    val apkFileProvider = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val webDir = rootProject.projectDir.parentFile.resolve("web")
    val appVersionName = android.defaultConfig.versionName ?: "0.1.0"
    val appVersionCode = android.defaultConfig.versionCode ?: 1

    onlyIf {
        webDir.isDirectory && apkFileProvider.get().asFile.isFile
    }

    doLast {
        val apkFile = apkFileProvider.get().asFile
        val downloadsDir = webDir.resolve("downloads").apply { mkdirs() }
        val targetApk = downloadsDir.resolve("controid.apk")
        apkFile.copyTo(targetApk, overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        targetApk.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val sizeBytes = targetApk.length()
        val builtAt = Instant.now().toString()

        val infoJson = """
            {
              "filename": "controid.apk",
              "url": "downloads/controid.apk?v=${sha256.take(12)}",
              "versionName": "$appVersionName",
              "versionCode": $appVersionCode,
              "sizeBytes": $sizeBytes,
              "sha256": "$sha256",
              "builtAt": "$builtAt"
            }
        """.trimIndent() + "\n"
        downloadsDir.resolve("apk-info.json").writeText(infoJson)
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(syncWebApk)
}
