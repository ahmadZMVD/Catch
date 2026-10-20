import java.security.MessageDigest
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "io.github.ahmadzmvd.catchapp"
    minSdk = 28
    targetSdk = 36
    versionCode = 101
    versionName = "1.0.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
      isUniversalApk = true
    }
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  val debugKeystoreFile = file("${rootDir}/debug.keystore")
  val debugKeystoreBase64File = file("${rootDir}/debug.keystore.base64")
  if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
    val cleanBase64 = debugKeystoreBase64File.readText().replace("\\s".toRegex(), "")
    debugKeystoreFile.writeBytes(Base64.getDecoder().decode(cleanBase64))
  }

  signingConfigs {
    create("release") {
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
      val customKeystorePath = System.getenv("KEYSTORE_PATH")
      val customKeystoreFile = if (!customKeystorePath.isNullOrBlank()) {
        file(customKeystorePath)
      } else {
        file("${rootDir}/my-upload-key.jks")
      }
      if (customKeystoreFile.exists()) {
        storeFile = customKeystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    create("debugConfig") {
      storeFile = debugKeystoreFile
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      all {
        it.maxHeapSize = "1536m"
        it.maxParallelForks = 1
      }
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  ksp(libs.androidx.room.compiler)
}

tasks.register("collectReleaseApks") {
  dependsOn("assembleRelease")
  doLast {
    val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val distDir = rootProject.file("dist")
    distDir.mkdirs()
    val versionTag = "v${android.defaultConfig.versionName}"
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")
    val checksumLines = mutableListOf<String>()

    for (abi in abis) {
      val srcApk = releaseDir.listFiles()?.firstOrNull {
        it.isFile && it.extension == "apk" && it.name.contains(abi)
      } ?: throw GradleException("Missing release APK for ABI: $abi in ${releaseDir.absolutePath}")

      val targetName = "Catch-${versionTag}-${abi}.apk"
      val targetFile = File(distDir, targetName)
      srcApk.copyTo(targetFile, overwrite = true)

      val digest = MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(targetFile.readBytes())
      val hexHash = hashBytes.joinToString("") { "%02x".format(it) }
      checksumLines.add("$hexHash  $targetName")
    }

    File(distDir, "SHA256SUMS.txt").writeText(checksumLines.joinToString("\n") + "\n")
  }
}
