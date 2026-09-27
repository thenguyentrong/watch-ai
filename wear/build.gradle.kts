import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vinhnguyen.watchai.wear"
    compileSdk = 37

    defaultConfig {
        // Same id and signing key as the phone app: that's how the Wear Data Layer pairs the two.
        applicationId = "com.vinhnguyen.watchai"
        minSdk = 33 // Wear OS 4: Galaxy Watch4 and newer
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Watches are ARM (the Galaxy Watch5 runs 32-bit); the wake word's x86 libraries would only add weight.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Test the watch with release builds: a debug build is 10x heavier on its two small cores.
            // Signed with the debug key (same as the phone's debug build, so they pair) until the Play upload key exists.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // sherpa-onnx's JNI library needs only onnxruntime; its C and C++ APIs are for other languages.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        // A watch app: ChromeOS's x86 builds don't apply.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    jvmToolchain(17)
}

/** Downloads [url] once into [target] and checks it against a pinned SHA-256. */
abstract class PinnedDownload : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputFile abstract val target: RegularFileProperty

    @TaskAction
    fun fetch() {
        val file = target.get().asFile
        if (file.isFile && sha256Of(file) == sha256.get()) return
        file.parentFile.mkdirs()
        val part = File(file.path + ".part")
        URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(part)
        if (actual != sha256.get()) {
            part.delete()
            throw GradleException("${url.get()}: SHA-256 $actual, expected ${sha256.get()}")
        }
        file.delete()
        check(part.renameTo(file)) { "couldn't move ${part.name} into place" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The wake word model's four files from its release archive, as app assets under kws/. */
abstract class ExtractWakeModel : DefaultTask() {
    @get:InputFile abstract val archive: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        val model = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        files.sync {
            from(archives.tarTree(archives.bzip2(archive.get().asFile))) {
                include("*/encoder-$model", "*/decoder-$model", "*/joiner-$model", "*/tokens.txt")
                eachFile { path = "kws/$name" }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

// "Hey Buddy": sherpa-onnx keyword spotting (Apache-2.0). Its AAR isn't on Maven Central and the model
// is a GitHub release file, so both are fetched into .downloads/ and checked against pinned SHA-256s.
val fetchSherpaOnnx =
    tasks.register<PinnedDownload>("fetchSherpaOnnx") {
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
        sha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        target = layout.projectDirectory.file(".downloads/sherpa-onnx-1.13.8.aar")
    }
val fetchWakeModel =
    tasks.register<PinnedDownload>("fetchWakeModel") {
        url =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
        sha256 = "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
        target = layout.projectDirectory.file(".downloads/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2")
    }
val wakeModel =
    tasks.register<ExtractWakeModel>("wakeModel") {
        archive = fetchWakeModel.flatMap { it.target }
        outputDir = layout.buildDirectory.dir("generated/wakeModel")
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(wakeModel, ExtractWakeModel::outputDir)
    }
}

dependencies {
    implementation(project(":core:watchlink"))
    implementation(files(fetchSherpaOnnx.flatMap { it.target }))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.concurrent.futures)
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
