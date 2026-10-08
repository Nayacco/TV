import java.io.StringReader
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.library")
}

/**
 * FongMi-owned build adapter for the unmodified FluxDown native/mobile bridge.
 *
 * Required build tools:
 *   rustup target add aarch64-linux-android armv7-linux-androideabi
 *   cargo install cargo-ndk
 *
 * Optional Gradle properties:
 *   fluxdown.abis=arm64-v8a,armeabi-v7a
 *   fluxdown.cargoBin=C:/Users/me/.cargo/bin
 *   fluxdown.ffmpegDir=/path/to/build/ffmpeg/jniLibs
 *     Build first with scripts/build-android-ffmpeg.sh (Linux/NDK); includes sibling assets/.
 */
object FluxRust {
    const val PACKAGE = "fluxdown_mobile"
    const val LIBRARY = "lib$PACKAGE.so"
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun parseAbis(value: String): List<String> {
        val requested = value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
        if (requested.isEmpty()) throw GradleException("fluxdown.abis must not be empty")
        val unsupported = requested.filterNot(SUPPORTED_ABIS::contains)
        if (unsupported.isNotEmpty()) {
            throw GradleException(
                "Unsupported fluxdown.abis ${unsupported.joinToString()}; expected ${SUPPORTED_ABIS.joinToString()}",
            )
        }
        return requested
    }

    fun ndkHome(localProperties: String?, sdkDir: File): File {
        val configured = localProperties
            ?.let { Properties().apply { load(StringReader(it)) }.getProperty("ndk.dir") }
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("ANDROID_NDK_HOME")?.takeIf(String::isNotBlank)
            ?: System.getenv("ANDROID_NDK_ROOT")?.takeIf(String::isNotBlank)
        if (configured != null) {
            return File(configured).also {
                if (!File(it, "source.properties").isFile) {
                    throw GradleException("Configured FluxDown Android NDK is invalid: $it")
                }
            }
        }
        val installed = File(sdkDir, "ndk").listFiles { file -> File(file, "source.properties").isFile }
            ?.maxWithOrNull(Comparator(FluxRust::compareNdkVersions))
        return installed ?: throw GradleException(
            "FluxDown requires the Android NDK; set ndk.dir in local.properties or install an SDK NDK",
        )
    }

    private fun compareNdkVersions(left: File, right: File): Int {
        val leftParts = left.name.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.name.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return left.name.compareTo(right.name)
    }

    private fun cargoBins(explicit: String?): List<String> = listOfNotNull(
        explicit?.takeIf { it.isNotBlank() },
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { "$it/bin" },
        "${System.getProperty("user.home")}/.cargo/bin",
    ).filter { File(it).isDirectory }

    fun cargo(explicit: String?): String = cargoBins(explicit)
        .map {
            File(
                it,
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "cargo.exe" else "cargo",
            )
        }
        .firstOrNull { it.isFile }?.absolutePath ?: "cargo"

    fun path(explicit: String?): String =
        (cargoBins(explicit) + listOfNotNull(System.getenv("PATH"))).joinToString(File.pathSeparator)
}

abstract class FluxCargoNdk @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val workspaceRoot: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection
    @get:Input abstract val abis: Property<String>
    @get:Input abstract val release: Property<Boolean>
    @get:Input abstract val platform: Property<Int>
    @get:Input @get:Optional abstract val localProperties: Property<String>
    @get:Input abstract val sdkDir: Property<String>
    @get:Input @get:Optional abstract val cargoBin: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun buildNative() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val requestedAbis = FluxRust.parseAbis(abis.get())
        val ndk = FluxRust.ndkHome(localProperties.orNull, File(sdkDir.get()))
        val command = buildList {
            add(FluxRust.cargo(cargoBin.orNull))
            add("ndk")
            requestedAbis.forEach { add("-t"); add(it) }
            add("--platform"); add(platform.get().toString())
            add("-o"); add(output.absolutePath)
            add("build")
            add("-p"); add(FluxRust.PACKAGE)
            if (release.get()) add("--release")
        }
        exec.exec {
            workingDir = workspaceRoot.get().asFile
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            environment("PATH", FluxRust.path(cargoBin.orNull))
            commandLine(command)
        }
        requestedAbis.forEach { abi ->
            val abiDir = File(output, abi)
            check(File(abiDir, FluxRust.LIBRARY).isFile) { "FluxDown did not produce $abi/${FluxRust.LIBRARY}" }
            abiDir.listFiles { file -> file.extension == "so" && file.name != FluxRust.LIBRARY }
                ?.forEach { it.delete() }
        }
    }
}

abstract class FluxUniffiBindgen @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val workspaceRoot: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibrary: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bindgenConfig: ConfigurableFileCollection
    @get:Input abstract val abis: Property<String>
    @get:Input @get:Optional abstract val cargoBin: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generateBindings() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val abi = FluxRust.parseAbis(abis.get()).first()
        val library = File(nativeLibrary.get().asFile, "$abi/${FluxRust.LIBRARY}")
        check(library.isFile) { "Missing UniFFI metadata library: $library" }
        exec.exec {
            workingDir = workspaceRoot.get().asFile
            environment("PATH", FluxRust.path(cargoBin.orNull))
            commandLine(
                FluxRust.cargo(cargoBin.orNull), "run", "-p", FluxRust.PACKAGE,
                "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", library.absolutePath,
                "--language", "kotlin", "--no-format", "--out-dir", output.absolutePath,
            )
        }
    }
}

abstract class FluxFFmpegBundle @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeInput: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsInput: DirectoryProperty
    @get:Input abstract val abis: Property<String>
    @get:OutputDirectory abstract val nativeOutput: DirectoryProperty
    @get:OutputDirectory abstract val assetsOutput: DirectoryProperty

    @TaskAction
    fun bundle() {
        val requested = FluxRust.parseAbis(abis.get())
        requested.forEach { abi ->
            val binary = File(nativeInput.get().asFile, "$abi/libffmpeg.so")
            check(binary.isFile && binary.length() > 0) {
                "Missing Android FFmpeg for $abi. Run scripts/build-android-ffmpeg.sh or set fluxdown.ffmpegDir."
            }
        }
        check(File(assetsInput.get().asFile, "ffmpeg/COPYING.LGPLv2.1").isFile) {
            "Android FFmpeg bundle is missing its license notices"
        }
        files.sync {
            from(nativeInput)
            requested.forEach { include("$it/libffmpeg.so") }
            into(nativeOutput)
        }
        files.sync {
            from(assetsInput)
            include("ffmpeg/**")
            into(assetsOutput)
        }
    }
}

android {
    namespace = "com.fongmi.fluxdown"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val fluxWorkspace = rootProject.layout.projectDirectory.dir("rust/fluxdown")
val configuredAbis = providers.gradleProperty("fluxdown.abis").orElse("arm64-v8a,armeabi-v7a")
val configuredCargoBin = providers.gradleProperty("fluxdown.cargoBin")
val configuredFfmpeg = providers.gradleProperty("fluxdown.ffmpegDir")
    .map { rootProject.file(it) }
    .orElse(rootProject.layout.buildDirectory.dir("ffmpeg/jniLibs").map { it.asFile })
val localPropertiesText = providers.provider {
    rootProject.file("local.properties").takeIf { it.isFile }?.readText() ?: ""
}

fun FluxCargoNdk.configureCommon() {
    workspaceRoot.set(fluxWorkspace)
    rustSources.from(fileTree(fluxWorkspace) {
        include("Cargo.toml", "Cargo.lock", ".cargo/**", "native/**", "crates/**", "third_party/**")
        exclude("**/target/**")
    })
    platform.set(24)
    localProperties.set(localPropertiesText)
    sdkDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.asFile.absolutePath })
    cargoBin.set(configuredCargoBin)
}

// Release .so files are stripped, so generate bindings from a shared unstripped debug build.
val fluxBindingsLibrary = tasks.register<FluxCargoNdk>("fluxCargoNdkBindings") {
    configureCommon()
    abis.set(configuredAbis.map { it.substringBefore(',').trim() })
    release.set(false)
    outputDir.set(layout.buildDirectory.dir("intermediates/fluxdown/bindingsLib"))
}

val fluxFfmpeg = tasks.register<FluxFFmpegBundle>("fluxFFmpegBundle") {
    nativeInput.fileProvider(configuredFfmpeg)
    assetsInput.fileProvider(configuredFfmpeg.map { File(it.parentFile, "assets") })
    abis.set(configuredAbis)
    nativeOutput.set(layout.buildDirectory.dir("generated/ffmpeg/jniLibs"))
    assetsOutput.set(layout.buildDirectory.dir("generated/ffmpeg/assets"))
}

androidComponents {
    onVariants { variant ->
        val kotlinSources = checkNotNull(variant.sources.kotlin) {
            "AGP built-in Kotlin is unavailable for ${variant.name}"
        }
        kotlinSources.addStaticSourceDirectory("src/main/java")
        kotlinSources.addStaticSourceDirectory("../fluxdown/mobile/Android/core/src/main/java")
        kotlinSources.addStaticSourceDirectory("../fluxdown/mobile/Android/bridge/src/main/java")

        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val nativeBuild = tasks.register<FluxCargoNdk>("fluxCargoNdk$capitalized") {
            configureCommon()
            abis.set(configuredAbis)
            release.set(variant.buildType == "release")
            outputDir.set(layout.buildDirectory.dir("intermediates/fluxdown/${variant.name}/jniLibs"))
        }
        val bindgen = tasks.register<FluxUniffiBindgen>("fluxUniffiBindgen$capitalized") {
            workspaceRoot.set(fluxWorkspace)
            nativeLibrary.set(fluxBindingsLibrary.flatMap(FluxCargoNdk::outputDir))
            bindgenConfig.from(fluxWorkspace.file("native/mobile/uniffi.toml"))
            abis.set(configuredAbis)
            cargoBin.set(configuredCargoBin)
            outputDir.set(layout.buildDirectory.dir("generated/fluxdown/${variant.name}/kotlin"))
        }
        checkNotNull(variant.sources.jniLibs) { "AGP did not expose generated jniLibs for ${variant.name}" }
            .addGeneratedSourceDirectory(nativeBuild, FluxCargoNdk::outputDir)
        checkNotNull(variant.sources.jniLibs)
            .addGeneratedSourceDirectory(fluxFfmpeg, FluxFFmpegBundle::nativeOutput)
        checkNotNull(variant.sources.assets)
            .addGeneratedSourceDirectory(fluxFfmpeg, FluxFFmpegBundle::assetsOutput)
        kotlinSources.addGeneratedSourceDirectory(bindgen, FluxUniffiBindgen::outputDir)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
