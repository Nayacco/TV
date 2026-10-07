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
