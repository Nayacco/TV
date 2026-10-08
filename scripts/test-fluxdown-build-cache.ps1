<#
.SYNOPSIS
Exercises the actual FluxDown Gradle task classes without an Android SDK or Rust compiler.
.DESCRIPTION
Extracts the task definitions from the production adapter into a temporary Gradle
project. Deterministic fake cargo/rustc executables stand in for compilation, while
Gradle itself snapshots inputs, executes tasks, stores outputs and restores cache
entries. All fixtures and the local cache remain under a fresh workspace build/
directory; pass -KeepFixture to retain diagnostic logs.
#>
[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $GradlePath,
    [switch] $KeepFixture
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $GradlePath) { $GradlePath = Join-Path $repository 'gradlew.bat' }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Supply -JavaHome pointing to a Java 21 installation.'
}
if (-not (Test-Path -LiteralPath $GradlePath)) { throw "Gradle launcher not found: $GradlePath" }
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) {
    throw 'This Windows fixture requires the .NET Framework C# compiler.'
}

$fixture = Join-Path $repository ('build/fluxdown-cache-test-' + [Guid]::NewGuid().ToString('N'))
$fixture = [IO.Path]::GetFullPath($fixture)
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repository 'build')) + [IO.Path]::DirectorySeparatorChar
if (-not $fixture.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Fixture escaped the repository build directory: $fixture"
}
New-Item -ItemType Directory -Path $fixture | Out-Null
$utf8 = New-Object Text.UTF8Encoding $false
function Write-FixtureFile([string] $RelativePath, [string] $Content) {
    $destination = Join-Path $fixture $RelativePath
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    [IO.File]::WriteAllText($destination, $Content, $utf8)
}
function Clear-FixtureOutputs {
    foreach ($name in @('out', 'relocated-out')) {
        $target = [IO.Path]::GetFullPath((Join-Path $fixture $name))
        if (-not $target.StartsWith($fixture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unsafe output target: $target"
        }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    }
}

$taskSource = [IO.File]::ReadAllText((Join-Path $repository 'rust/fluxdown-android/build.gradle.kts'))
$androidBlock = [regex]::Match($taskSource, '(?m)^android\s*\{')
if (-not $androidBlock.Success) { throw 'Could not find the Android adapter configuration boundary.' }
$taskSource = $taskSource.Substring(0, $androidBlock.Index)
$taskSource = [regex]::Replace($taskSource, '(?ms)^plugins\s*\{.*?^\}\s*', '')
$fixtureConfiguration = @'
val fixtureWorkspace = layout.projectDirectory.dir("workspace")
val fixtureAbis = providers.gradleProperty("cache.abis").orElse("arm64-v8a")
val fixtureOutput = providers.gradleProperty("cache.output").orElse("out")
val fixtureRustSources = fileTree(fixtureWorkspace) {
    include("Cargo.toml", "Cargo.lock", ".cargo/**", "native/**", "crates/**", "third_party/**")
    exclude("**/target/**")
}
val fixtureToolConfiguration = fileTree(fixtureWorkspace) {
    include(".cargo/config", ".cargo/config.toml", "rust-toolchain", "rust-toolchain.toml")
}
val fixtureNative = tasks.register<FluxCargoNdk>("nativeBuild") {
    workspaceRoot.set(fixtureWorkspace)
    rustSources.from(fixtureRustSources)
    toolConfiguration.from(fixtureToolConfiguration)
    cargoBin.set(layout.projectDirectory.dir("tools").asFile.absolutePath)
    ndkDirectory.fileProvider(providers.provider {
        val configuredNdk = Properties().apply {
            file("local.properties").inputStream().use { load(it) }
        }.getProperty("ndk.dir")
        FluxRust.ndkHome(configuredNdk, layout.projectDirectory.dir("sdk").asFile)
    })
    abis.set(fixtureAbis)
    platform.set(providers.gradleProperty("cache.api").orElse("24").map(String::toInt))
    release.set(providers.gradleProperty("cache.release").orElse("false").map(String::toBoolean))
    outputDir.set(fixtureOutput.map { layout.projectDirectory.dir("$it/native") })
}
tasks.register<FluxUniffiBindgen>("generateBindings") {
    workspaceRoot.set(fixtureWorkspace)
    rustSources.from(fixtureRustSources)
    toolConfiguration.from(fixtureToolConfiguration)
    nativeLibrary.set(fixtureNative.flatMap(FluxCargoNdk::outputDir))
    bindgenConfig.from(fixtureWorkspace.file("native/mobile/uniffi.toml"))
    abis.set(fixtureAbis)
    cargoBin.set(layout.projectDirectory.dir("tools").asFile.absolutePath)
    outputDir.set(fixtureOutput.map { layout.projectDirectory.dir("$it/bindings") })
}
tasks.register<FluxFFmpegBundle>("bundleFfmpeg") {
    nativeInput.set(layout.projectDirectory.dir("ffmpeg/jniLibs"))
    assetsInput.set(layout.projectDirectory.dir("ffmpeg/assets"))
    abis.set(fixtureAbis)
    nativeOutput.set(fixtureOutput.map { layout.projectDirectory.dir("$it/ffmpeg-native") })
    assetsOutput.set(fixtureOutput.map { layout.projectDirectory.dir("$it/ffmpeg-assets") })
}
'@
Write-FixtureFile 'build.gradle.kts' ($taskSource + $fixtureConfiguration)
Write-FixtureFile 'settings.gradle.kts' @'
rootProject.name = "fluxdown-cache-regression"
buildCache {
    local {
        directory = file("cache")
        isEnabled = true
    }
}
'@
Write-FixtureFile 'workspace/Cargo.toml' "[workspace]`nmembers = []`n"
Write-FixtureFile 'workspace/Cargo.lock' "# fake locked dependencies`n"
Write-FixtureFile 'workspace/native/mobile/src/lib.rs' "pub fn original() {}`n"
Write-FixtureFile 'workspace/native/mobile/uniffi.toml' "[bindings.kotlin]`npackage_name = 'fixture'`n"
Write-FixtureFile 'workspace/.cargo/config.toml' "[build]`njobs = 2`n"
Write-FixtureFile 'workspace/rust-toolchain.toml' "[toolchain]`nchannel = 'stable'`n"
Write-FixtureFile 'ndk/source.properties' "Pkg.Desc = Android NDK`nPkg.Revision = 29.0.14206865`n"
$fixtureNdk = (Join-Path $fixture 'ndk').Replace('\', '/')
Write-FixtureFile 'local.properties' "ndk.dir=$fixtureNdk`nstoreFile=initial-test-key.jks`nkeyAlias=initial-alias`nstorePassword=initial-test-value`n"
Write-FixtureFile 'tools/cargo-version.txt' "cargo 1.90.0 (fixture)`n"
Write-FixtureFile 'tools/rustc-version.txt' "rustc 1.90.0 (fixture)`nhost: x86_64-pc-windows-msvc`n"
Write-FixtureFile 'tools/ndk-version.txt' "cargo-ndk 4.1.2 (fixture)`n"
foreach ($abi in @('arm64-v8a', 'armeabi-v7a')) {
    Write-FixtureFile "ffmpeg/jniLibs/$abi/libffmpeg.so" "fake ffmpeg $abi`n"
}
Write-FixtureFile 'ffmpeg/assets/ffmpeg/COPYING.LGPLv2.1' "test license fixture`n"

# A real executable avoids Windows ProcessBuilder's .cmd/.bat shell differences.
# Version responses deliberately live outside rustSources: toolchain identity,
# not a source-file side effect, must invalidate the cache when they change.
Write-FixtureFile 'tools/FakeCargo.cs' @'
using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
using System.Collections.Generic;

class FakeCargo {
    static string Tools { get { return AppDomain.CurrentDomain.BaseDirectory; } }
    static string ReadVersion(string name) { return File.ReadAllText(Path.Combine(Tools, name + "-version.txt")); }
    static string Argument(string[] args, string name) {
        for (int i = 0; i + 1 < args.Length; i++) if (args[i] == name) return args[i + 1];
        throw new Exception("Missing argument " + name);
    }
    static string Digest(string[] args) {
        var content = new StringBuilder();
        content.Append(ReadVersion("cargo")).Append(ReadVersion("rustc")).Append(ReadVersion("ndk"));
        var files = new List<string>(Directory.GetFiles(Directory.GetCurrentDirectory(), "*", SearchOption.AllDirectories));
        files.Sort(StringComparer.Ordinal);
        foreach (string file in files) content.Append(file.Substring(Directory.GetCurrentDirectory().Length)).Append(File.ReadAllText(file));
        string ndk = Environment.GetEnvironmentVariable("ANDROID_NDK_HOME");
        if (!String.IsNullOrEmpty(ndk)) content.Append(File.ReadAllText(Path.Combine(ndk, "source.properties")));
        foreach (string variable in new [] { "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_RUSTFLAGS" }) {
            content.Append(variable).Append(Environment.GetEnvironmentVariable(variable));
        }
        for (int i = 0; i < args.Length; i++) {
            if (args[i] == "-o" || args[i] == "--out-dir") { i++; continue; }
            if (args[i] == "--library") { content.Append(File.ReadAllText(args[++i])); continue; }
            content.Append(args[i]).Append("\n");
        }
        using (var hash = SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(content.ToString())));
    }
    static int Main(string[] args) {
        try {
            bool rustc = Path.GetFileNameWithoutExtension(Environment.GetCommandLineArgs()[0]).Equals("rustc", StringComparison.OrdinalIgnoreCase);
            if (args.Length == 1 && (args[0] == "-vV" || args[0] == "--version")) {
                Console.Write(ReadVersion(rustc ? "rustc" : "cargo")); return 0;
            }
            if (args.Length == 2 && args[0] == "ndk" && args[1] == "--version") {
                Console.Write(ReadVersion("ndk")); return 0;
            }
            string digest = Digest(args);
            string log = Path.Combine(Tools, "executions.log");
            if (args.Length > 0 && args[0] == "ndk") {
                string output = Argument(args, "-o");
                for (int i = 0; i + 1 < args.Length; i++) if (args[i] == "-t") {
                    string destination = Path.Combine(output, args[++i]);
                    Directory.CreateDirectory(destination);
                    File.WriteAllText(Path.Combine(destination, "libfluxdown_mobile.so"), digest);
                }
                File.AppendAllText(log, "native\n"); return 0;
            }
            if (args.Length > 0 && args[0] == "run") {
                string output = Argument(args, "--out-dir");
                Directory.CreateDirectory(output);
                File.WriteAllText(Path.Combine(output, "Flux.kt"), "// deterministic fixture " + digest);
                File.AppendAllText(log, "bindings\n"); return 0;
            }
            throw new Exception("Unexpected fake command: " + String.Join(" ", args));
        } catch (Exception error) { Console.Error.WriteLine(error.ToString()); return 1; }
    }
}
'@
$fakeCargo = Join-Path $fixture 'tools/cargo.exe'
& $compiler /nologo /target:exe "/out:$fakeCargo" (Join-Path $fixture 'tools/FakeCargo.cs')
if ($LASTEXITCODE -ne 0) { throw 'Compiling the deterministic fake cargo executable failed.' }
Copy-Item -LiteralPath $fakeCargo -Destination (Join-Path $fixture 'tools/rustc.exe')

$savedJavaHome = $env:JAVA_HOME
$savedRustFlags = $env:RUSTFLAGS
$savedEncodedFlags = $env:CARGO_ENCODED_RUSTFLAGS
$savedRustWrapper = $env:RUSTC_WRAPPER
$env:JAVA_HOME = $JavaHome
$env:RUSTFLAGS = ''
$env:CARGO_ENCODED_RUSTFLAGS = ''
$env:RUSTC_WRAPPER = ''
$cases = 0
function Test-CacheCase {
    param([string] $Name, [bool] $NativeHit, [bool] $BindingsHit, [bool] $FfmpegHit = $true, [string[]] $Properties = @())
    Clear-FixtureOutputs
    $executionLog = Join-Path $fixture 'tools/executions.log'
    $before = if (Test-Path -LiteralPath $executionLog) { @([IO.File]::ReadAllLines($executionLog)).Count } else { 0 }
    $arguments = @('-p', $fixture, '--offline', '--build-cache', '--console=plain', '--daemon', 'generateBindings', 'bundleFfmpeg') + $Properties
    if ($Name -eq 'initial-build') { $arguments += '--profile' }
    # Windows PowerShell turns redirected native stderr into ErrorRecords;
    # capture complete Gradle diagnostics before judging the process exit code.
    $savedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $GradlePath @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    $output = $lines -join "`n"
    Write-FixtureFile ("logs/{0:D2}-{1}.txt" -f $script:cases, $Name) $output
    if ($exitCode -ne 0) { throw "Gradle failed in '$Name'. Fixture: $fixture`n$output" }
    foreach ($expectation in @(
        @{ Task = 'nativeBuild'; Hit = $NativeHit },
        @{ Task = 'generateBindings'; Hit = $BindingsHit },
        @{ Task = 'bundleFfmpeg'; Hit = $FfmpegHit }
    )) {
        $match = [regex]::Match($output, '(?m)^> Task :' + $expectation.Task + '(?: ([A-Z-]+))?\s*$')
        if (-not $match.Success) { throw "No result for $($expectation.Task) in '$Name':`n$output" }
        $actual = $match.Groups[1].Value
        $expected = if ($expectation.Hit) { 'FROM-CACHE' } else { '' }
        if ($actual -ne $expected) {
            throw "Expected $($expectation.Task) '$expected', got '$actual' in '$Name'. Fixture: $fixture`n$output"
        }
    }
    $after = if (Test-Path -LiteralPath $executionLog) { @([IO.File]::ReadAllLines($executionLog)).Count } else { 0 }
    $expectedExecutions = [int](-not $NativeHit) + [int](-not $BindingsHit)
    if ($after - $before -ne $expectedExecutions) {
        throw "Unexpected real task-action count in '$Name': $($after - $before), expected $expectedExecutions"
    }
    $script:cases++
    Write-Host "PASS $Name (native=$NativeHit, bindings=$BindingsHit, FFmpeg=$FfmpegHit)"
}

try {
    Test-CacheCase 'initial-build' $false $false $false
    Test-CacheCase 'clean-output-restore' $true $true
    Test-CacheCase 'relocated-output-restore' $true $true -Properties @('-Pcache.output=relocated-out')
    Write-FixtureFile 'local.properties' "ndk.dir=$fixtureNdk`nstoreFile=changed-test-key.jks`nkeyAlias=changed-alias`nstorePassword=changed-test-value`n"
    Test-CacheCase 'signing-only-change' $true $true

    $mutations = @(
        @{ Name = 'rust-source'; Path = 'workspace/native/mobile/src/lib.rs'; Value = "pub fn changed() {}`n" },
        @{ Name = 'cargo-config'; Path = 'workspace/.cargo/config.toml'; Value = "[build]`njobs = 3`n" },
        @{ Name = 'rust-toolchain'; Path = 'workspace/rust-toolchain.toml'; Value = "[toolchain]`nchannel = '1.90.0'`n" },
        @{ Name = 'ndk-revision'; Path = 'ndk/source.properties'; Value = "Pkg.Desc = Android NDK`nPkg.Revision = 30.0.1`n" },
        @{ Name = 'cargo-version'; Path = 'tools/cargo-version.txt'; Value = "cargo 1.91.0 (fixture)`n" },
        @{ Name = 'rustc-version'; Path = 'tools/rustc-version.txt'; Value = "rustc 1.91.0 (fixture)`nhost: x86_64-pc-windows-msvc`n" },
        @{ Name = 'cargo-ndk-version'; Path = 'tools/ndk-version.txt'; Value = "cargo-ndk 4.2.0 (fixture)`n" }
    )
    foreach ($mutation in $mutations) {
        $original = [IO.File]::ReadAllText((Join-Path $fixture $mutation.Path))
        Write-FixtureFile $mutation.Path $mutation.Value
        Test-CacheCase $mutation.Name $false $false
        Write-FixtureFile $mutation.Path $original
    }
    $env:RUSTFLAGS = '-C opt-level=1'
    Test-CacheCase 'rustflags' $false $false
    $env:RUSTFLAGS = ''
    $env:CARGO_ENCODED_RUSTFLAGS = "-C$([char]31)opt-level=2"
    Test-CacheCase 'encoded-rustflags' $false $false
    $env:CARGO_ENCODED_RUSTFLAGS = ''
    Test-CacheCase 'abi' $false $false $false -Properties @('-Pcache.abis=armeabi-v7a')
    Test-CacheCase 'android-api' $false $false -Properties @('-Pcache.api=26')
    Test-CacheCase 'release-profile' $false $false -Properties @('-Pcache.release=true')
    Test-CacheCase 'final-baseline-restore' $true $true
    $env:RUSTC_WRAPPER = 'unidentified-test-wrapper'
    Test-CacheCase 'custom-wrapper-first' $false $false
    Test-CacheCase 'custom-wrapper-never-restored' $false $false
    $env:RUSTC_WRAPPER = ''
    Write-Host "FluxDown build-cache regression passed: $cases cases."
} catch {
    $KeepFixture = $true
    throw
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:RUSTFLAGS = $savedRustFlags
    $env:CARGO_ENCODED_RUSTFLAGS = $savedEncodedFlags
    $env:RUSTC_WRAPPER = $savedRustWrapper
    if ($KeepFixture) {
        Write-Host "Diagnostic fixture retained: $fixture"
    } else {
        $resolvedFixture = [IO.Path]::GetFullPath($fixture)
        if (-not $resolvedFixture.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unsafe fixture cleanup target: $resolvedFixture"
        }
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
