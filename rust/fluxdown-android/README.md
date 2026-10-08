# FluxDown Android adapter

This FongMi-owned module builds and wraps the unmodified FluxDown mobile engine
from the adjacent `rust/fluxdown` git submodule. The submodule is pinned to
FluxDown v0.5.4 (`7ce1ae6373bce1f9aa99541cfc4c16daa3ba7e17`).

FluxDown is licensed under AGPL-3.0. Distributions of an APK containing this
engine must satisfy the corresponding-source and other obligations of that
license. See `../fluxdown/LICENSE` for the upstream license text.

Build prerequisites:

```text
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
```

The default build targets `arm64-v8a,armeabi-v7a` with Android NDK platform 24.
The platform matches FongMi's `minSdk`; FluxDown upstream currently exercises
its Android build at API 31 on arm64, so API 24 and armv7 still require device
coverage in this application. Additional supported cargo-ndk ABI names are
`x86` and `x86_64`.

Optional Gradle properties:

```properties
fluxdown.abis=arm64-v8a,armeabi-v7a
fluxdown.cargoBin=C:/Users/me/.cargo/bin
```

The NDK is resolved from `ndk.dir`, `ANDROID_NDK_HOME`,
`ANDROID_NDK_ROOT`, or the newest installed side-by-side SDK NDK. A normal
Android variant build then:

1. builds `fluxdown_mobile` with cargo-ndk;
2. runs upstream's `uniffi-bindgen` against an unstripped debug library;
3. adds generated `com.fluxdown.bridge` Kotlin and ABI-specific
   `libfluxdown_mobile.so` files through the AGP variant source APIs.

AGP 9 built-in Kotlin compiles the FongMi facade plus these unchanged upstream
source trees:

```text
../fluxdown/mobile/Android/core/src/main/java
../fluxdown/mobile/Android/bridge/src/main/java
```

`FluxDownEngine` is the stable Java-facing API. `start` opens the process-local
daemon, `create` invokes the raw `daemon.task.create` wire method with
`unattended=true`, and pause/resume/delete/stop remain asynchronous. Listener
and callback methods run on the adapter's IO coroutine scope, so UI consumers
must dispatch to their own main-thread mechanism.

No generated binding, native library, or patched upstream source is checked in.

## Build cache

Native compilation and UniFFI generation support Gradle's task-output cache.
The cache tracks Rust sources and configuration, compiler and cargo-ndk versions,
the NDK revision, ABI, Android platform and build profile. APK signing properties
are deliberately excluded. Changing only application Java/Kotlin code or the
signing key therefore does not invalidate unchanged native outputs. Custom
compiler or linker overrides conservatively disable this reuse.

The draft-APK workflow uses `setup-gradle` to restore the latest compatible Gradle
cache and save updated state for each commit. The first run after changing the
native task implementation or toolchain needs to populate that cache. Subsequent
runs report native `FROM-CACHE`/rebuild counts, per-task timing and a sanitized
Gradle log in the job summary and `gradle-build-reports` artifact.

Cargo's fallback dependency cache has a separate, versioned Release namespace
which includes the NDK and cargo-ndk versions. It is saved only after a successful
build, so a failed Debug-only run cannot seed an immutable Release cache. It does
not replace Gradle's final native-output cache or skip Rust input verification.
The first successful run with a new namespace must populate it; compare the next
run to measure reuse. `--info` records native task cache keys and caching decisions
in the sanitized log when final-output restoration misses.

On the hosted APK runner only, Gradle uses a 4 GiB daemon heap and at most two
workers to avoid native-debug-metadata merging exhausting the default 2 GiB
heap. Local JVM settings, Release shrinking, both APK flavors and unit tests are
unchanged. Actual build success and warm-cache speed still require CI validation.

From the repository root on Windows, verify the actual task classes without an
Android SDK or Rust compiler:

```powershell
./scripts/test-fluxdown-build-cache.ps1 -JavaHome 'C:/path/to/jdk-21'
```

This test uses deterministic fake compilers with the real Gradle build cache to
check restoration, input invalidation and signing independence. It is a cache
contract test, not a substitute for the CI Android builds and media smoke test.
