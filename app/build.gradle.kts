plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shazan.airbridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shazan.airbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core) // drawing the "Show QR code" pairing code
    implementation(libs.play.services.nearby)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
// Release builds started from Android Studio on a Mac (Build APK, Generate Signed APK) end by showing
// the APK in Finder, so it is one step away even when Studio's "locate" balloon was missed.
val revealReleaseApk = tasks.register("revealReleaseApk") {
    val injected = providers.gradleProperty("android.injected.apk.location")
    val fromIde = providers.gradleProperty("android.injected.invoked.from.ide")
    val defaultDir = layout.buildDirectory.dir("outputs/apk/release")
    onlyIf { fromIde.isPresent && System.getProperty("os.name").startsWith("Mac") }
    doLast {
        val dirs = listOfNotNull(injected.orNull?.let { File(it, "release") }, defaultDir.get().asFile)
        val apk = dirs.flatMap { d -> d.listFiles { f -> f.extension == "apk" }?.toList().orEmpty() }
            .maxByOrNull { it.lastModified() } ?: return@doLast
        ProcessBuilder("open", "-R", apk.absolutePath).start()
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(revealReleaseApk) }
