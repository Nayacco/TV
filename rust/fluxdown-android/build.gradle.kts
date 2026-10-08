import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

    fun ndkHome(configuredNdk: String?, sdkDir: File): File {
        val configured = configuredNdk?.takeIf(String::isNotBlank)
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

    fun tool(name: String, explicit: String?): String = cargoBins(explicit)
        .map {
            File(
                it,
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "$name.exe" else name,
            )
        }
        .firstOrNull { it.isFile }?.absolutePath ?: name

    fun cargo(explicit: String?): String = tool("cargo", explicit)

    fun path(explicit: String?): String =
        (cargoBins(explicit) + listOfNotNull(System.getenv("PATH"))).joinToString(File.pathSeparator)

    // Only compilation inputs belong in the native key, never signing values, CI IDs or tokens.
    fun compilationEnvironment(): Map<String, String> {
        val names = setOf(
            "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTDOCFLAGS", "CARGO_ENCODED_RUSTDOCFLAGS",
            "RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "RUSTUP_TOOLCHAIN",
            "CARGO_BUILD_TARGET", "CARGO_BUILD_RUSTFLAGS", "CARGO_BUILD_RUSTC",
            "CARGO_BUILD_RUSTC_WRAPPER", "CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER",
            "CARGO_BUILD_INCREMENTAL", "CARGO_INCREMENTAL", "SOURCE_DATE_EPOCH",
            "CC", "CXX", "AR", "RANLIB", "NM", "STRIP", "OBJCOPY",
            "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "HOST_CC", "HOST_CXX", "TARGET_CC", "TARGET_CXX",
            "PKG_CONFIG", "PKG_CONFIG_PATH", "PKG_CONFIG_LIBDIR", "PKG_CONFIG_SYSROOT_DIR", "PKG_CONFIG_ALLOW_CROSS",
            "FLUXDOWN_APP_VERSION", "FLUXDOWN_ANALYTICS_APP_KEY", "FLUXCLOUD_BASE_URL",
        )
        val flags = Regex("(CC|CXX|AR|RANLIB|CFLAGS|CXXFLAGS|CPPFLAGS|LDFLAGS)_.+|.+_(CFLAGS|CXXFLAGS|CPPFLAGS|LDFLAGS)")
        return System.getenv().filterKeys { name ->
            name in names || name.startsWith("CARGO_PROFILE_") ||
                (name.startsWith("CARGO_TARGET_") && (name.endsWith("_RUSTFLAGS") || name.endsWith("_LINKER"))) ||
                name.startsWith("CARGO_NDK_") || flags.matches(name)
        }.toSortedMap()
    }

    // Cargo searches configuration in the working directory's ancestors and CARGO_HOME.
    // rustc -vV below identifies the selected toolchain, including rustup overrides.
    fun configurations(workspace: File): List<File> = buildList {
        var current: File? = workspace.absoluteFile
        while (current != null) {
            val directory = current
            listOf(".cargo/config", ".cargo/config.toml", "rust-toolchain", "rust-toolchain.toml")
                .map { File(directory, it) }.filter(File::isFile).forEach(::add)
            current = directory.parentFile
        }
        val cargoHome = System.getenv("CARGO_HOME")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".cargo")
        listOf("config", "config.toml").map { File(cargoHome, it) }.filter(File::isFile).forEach(::add)
    }.distinct()

    fun standardTools(environment: Map<String, String>, configuration: Set<File>): Boolean {
        // A custom wrapper/compiler can change in place without changing its path/version.
        // Do not reuse either local or remote outputs unless the normal identified tools are used.
        val customTool = Regex("(RUSTC(_.*)?|CARGO_BUILD_RUSTC(_.*)?|CC(_.*)?|CXX(_.*)?|AR(_.*)?|RANLIB(_.*)?|NM|STRIP|OBJCOPY|HOST_CC|HOST_CXX|TARGET_CC|TARGET_CXX|PKG_CONFIG(_PATH|_LIBDIR|_SYSROOT_DIR)?|CARGO_TARGET_.+_LINKER)")
        if (environment.any { (name, value) -> value.isNotBlank() && customTool.matches(name) }) return false
        val customConfig = Regex("(^|[^A-Za-z0-9_-])(rustc|rustc-wrapper|rustc-workspace-wrapper|linker|ar)([^A-Za-z0-9_-]|$)")
        return configuration.none { file ->
            file.isFile && file.readLines().any { line ->
                !line.trimStart().startsWith('#') && customConfig.containsMatchIn(line.substringBefore('='))
            }
        }
    }
}

abstract class FluxRustTask @Inject constructor(@get:Internal protected val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val workspaceRoot: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolConfiguration: ConfigurableFileCollection
    @get:Internal abstract val cargoBin: Property<String>

    @get:Input val compilationEnvironment: Map<String, String>
        get() = FluxRust.compilationEnvironment()

    // Preserve Cargo's configuration precedence without keying the absolute checkout path.
    @get:Input val toolConfigurationOrder: List<String>
        get() = toolConfiguration.files.filter(File::isFile).map { file ->
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            "${file.name}:$hash"
        }

    @get:Input open val toolchainIdentity: Map<String, String>
        get() = mapOf(
            "host-os" to System.getProperty("os.name"),
            "host-arch" to System.getProperty("os.arch"),
            "cargo" to version(FluxRust.cargo(cargoBin.orNull), "-vV"),
            "rustc" to version(FluxRust.tool("rustc", cargoBin.orNull), "-vV"),
        )

    init {
        outputs.cacheIf("Only identified standard Rust/NDK tools can reuse compiled outputs") {
            FluxRust.standardTools(compilationEnvironment, toolConfiguration.files)
        }
        outputs.upToDateWhen { FluxRust.standardTools(compilationEnvironment, toolConfiguration.files) }
    }

    protected fun version(vararg command: String): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            workingDir = workspaceRoot.get().asFile
            environment("PATH", FluxRust.path(cargoBin.orNull))
            commandLine(*command)
            standardOutput = output
            errorOutput = output
        }
        return output.toString(Charsets.UTF_8.name()).trim().also {
            check(it.isNotEmpty()) { "FluxDown build tool returned no version: ${command.first()}" }
        }
    }
}

@CacheableTask
abstract class FluxCargoNdk @Inject constructor(exec: ExecOperations) : FluxRustTask(exec) {
    @get:Input abstract val abis: Property<String>
    @get:Input abstract val release: Property<Boolean>
    @get:Input abstract val platform: Property<Int>
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Input override val toolchainIdentity: Map<String, String>
        get() = super.toolchainIdentity + mapOf(
            "cargo-ndk" to version(FluxRust.cargo(cargoBin.orNull), "ndk", "--version"),
            "ndk" to File(ndkDirectory.get().asFile, "source.properties").readText(),
        )

    @TaskAction
    fun buildNative() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val requestedAbis = FluxRust.parseAbis(abis.get())
        val ndk = ndkDirectory.get().asFile
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

@CacheableTask
abstract class FluxUniffiBindgen @Inject constructor(exec: ExecOperations) : FluxRustTask(exec) {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibrary: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bindgenConfig: ConfigurableFileCollection
    @get:Input abstract val abis: Property<String>
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

@CacheableTask
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
// local.properties also contains signing secrets and an ephemeral key path. Read only ndk.dir,
// and use the installed NDK revision (not its machine-specific path) as the compilation input.
val configuredNdk = providers.provider {
    val localPropertiesFile = rootProject.file("local.properties")
    if (!localPropertiesFile.isFile) null else Properties().apply {
        localPropertiesFile.inputStream().use { load(it) }
    }.getProperty("ndk.dir")
}
val configuredNdkDirectory = providers.provider {
    FluxRust.ndkHome(configuredNdk.orNull, androidComponents.sdkComponents.sdkDirectory.get().asFile)
}

fun FluxRustTask.configureSources() {
    workspaceRoot.set(fluxWorkspace)
    rustSources.from(fileTree(fluxWorkspace) {
        include(
            "Cargo.toml", "Cargo.lock", "rust-toolchain", "rust-toolchain.toml", ".cargo/**",
            "native/**", "crates/**", "third_party/**", "scripts/desktop-dev/Cargo.toml",
            // native/engine/build.rs reads the application version from this file.
            "pubspec.yaml",
        )
        exclude("**/target/**")
    })
    toolConfiguration.from(providers.provider { FluxRust.configurations(fluxWorkspace.asFile) })
    cargoBin.set(configuredCargoBin)
}

fun FluxCargoNdk.configureCommon() {
    configureSources()
    platform.set(24)
    ndkDirectory.fileProvider(configuredNdkDirectory)
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
            configureSources()
            nativeLibrary.set(fluxBindingsLibrary.flatMap(FluxCargoNdk::outputDir))
            bindgenConfig.from(fluxWorkspace.file("native/mobile/uniffi.toml"))
            abis.set(configuredAbis)
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
