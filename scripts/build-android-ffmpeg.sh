#!/usr/bin/env bash
# Build a standalone Android FFmpeg process. The .so name lets PackageManager
# install it in nativeLibraryDir; this file is an executable, not a JNI library.
set -euo pipefail
export LC_ALL=C

readonly FFMPEG_VERSION="8.0.3"
readonly FFMPEG_SIGNING_KEY="FCF986EA15E6E293A5644F10B4322F04D67658D8"
readonly ANDROID_API="24"

usage() {
  printf 'Usage: bash %s --ndk NDK_DIRECTORY --output OUTPUT_DIRECTORY [--jobs COUNT] [--abis ABI,ABI]\n' "$0"
  printf 'Produces jniLibs/, assets/ffmpeg/ and distribution/ under OUTPUT_DIRECTORY.\n'
}

ndk_directory="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
output_directory="build/ffmpeg"
jobs="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2')"
requested_abis="arm64-v8a,armeabi-v7a"
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --ndk|--output|--jobs|--abis)
      [[ "$#" -ge 2 ]] || { usage >&2; exit 2; }
      case "$1" in
        --ndk) ndk_directory="$2" ;;
        --output) output_directory="$2" ;;
        --jobs) jobs="$2" ;;
        --abis) requested_abis="$2" ;;
      esac
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == "Linux" ]] || { printf 'This build script requires Linux.\n' >&2; exit 1; }
[[ -n "${ndk_directory}" && -f "${ndk_directory}/source.properties" ]] || {
  printf 'Provide an installed Android NDK with --ndk or ANDROID_NDK_HOME.\n' >&2
  exit 1
}
[[ "${jobs}" =~ ^[1-9][0-9]*$ ]] || { printf 'Invalid --jobs value.\n' >&2; exit 2; }
[[ "${requested_abis}" =~ ^(arm64-v8a|armeabi-v7a|x86_64)(,(arm64-v8a|armeabi-v7a|x86_64))*$ ]] || {
  printf 'Unsupported --abis value. Supported ABIs: arm64-v8a, armeabi-v7a, x86_64.\n' >&2
  exit 2
}
IFS=, read -r -a abis <<< "${requested_abis}"
for tool in curl gpg tar xz make sha256sum awk grep realpath; do
  command -v "${tool}" >/dev/null || { printf 'Missing build dependency: %s\n' "${tool}" >&2; exit 1; }
done

script_file="$(realpath "${BASH_SOURCE[0]}")"
ndk_directory="$(realpath "${ndk_directory}")"
mkdir -p "${output_directory}"
output_directory="$(realpath "${output_directory}")"
toolchain="${ndk_directory}/toolchains/llvm/prebuilt/linux-x86_64"
for tool in llvm-ar llvm-ranlib llvm-nm llvm-strip llvm-readelf; do
  test -x "${toolchain}/bin/${tool}"
done
ndk_version="$(awk -F' = ' '$1 == "Pkg.Revision" { print $2 }' "${ndk_directory}/source.properties")"
test -n "${ndk_version}"

distribution="${output_directory}/distribution"
notices="${output_directory}/assets/ffmpeg"
work="${output_directory}/work"
mkdir -p "${distribution}" "${notices}" "${work}"
archive="ffmpeg-${FFMPEG_VERSION}.tar.xz"

download() {
  local url="$1" destination="$2"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    --output "${destination}.download" "${url}"
  mv "${destination}.download" "${destination}"
}

download "https://ffmpeg.org/releases/${archive}" "${distribution}/${archive}"
download "https://ffmpeg.org/releases/${archive}.asc" "${distribution}/${archive}.asc"
download "https://ffmpeg.org/ffmpeg-devel.asc" "${distribution}/ffmpeg-devel.asc"

# Use a dedicated empty keyring and pin the official primary-key fingerprint.
# A valid signature from an arbitrary key downloaded alongside the source is not enough.
keyring="$(mktemp -d "${work}/gnupg.XXXXXX")"
chmod 700 "${keyring}"
key_fingerprint="$(
  gpg --homedir "${keyring}" --batch --with-colons --import-options show-only \
    --import "${distribution}/ffmpeg-devel.asc" | awk -F: '$1 == "fpr" { print $10; exit }'
)"
[[ "${key_fingerprint}" == "${FFMPEG_SIGNING_KEY}" ]] || {
  printf 'The FFmpeg release key does not match the pinned fingerprint.\n' >&2
  exit 1
}
gpg --homedir "${keyring}" --batch --import "${distribution}/ffmpeg-devel.asc"
gpg --homedir "${keyring}" --batch --status-fd 1 \
  --verify "${distribution}/${archive}.asc" "${distribution}/${archive}" \
  > "${work}/signature-status.txt"
awk -v expected="${FFMPEG_SIGNING_KEY}" '
  $1 == "[GNUPG:]" && $2 == "VALIDSIG" && ($3 == expected || $NF == expected) { valid = 1 }
  END { exit !valid }
' "${work}/signature-status.txt"

tar --extract --xz --file "${distribution}/${archive}" --directory "${work}"
source_directory="${work}/ffmpeg-${FFMPEG_VERSION}"
test "$(<"${source_directory}/VERSION")" = "${FFMPEG_VERSION}"
cp "${source_directory}/COPYING.LGPLv2.1" "${notices}/COPYING.LGPLv2.1"
cp "${source_directory}/LICENSE.md" "${notices}/LICENSE.md"
cp "${notices}/COPYING.LGPLv2.1" "${distribution}/COPYING.LGPLv2.1"
cp "${notices}/LICENSE.md" "${distribution}/FFmpeg-LICENSE.md"
cp "${script_file}" "${distribution}/build-android-ffmpeg.sh"

configure_flags=(
  --target-os=android
  --enable-cross-compile
  --enable-static
  --disable-shared
  --enable-pic
  --disable-autodetect
  --disable-gpl
  --disable-version3
  --disable-nonfree
  --disable-doc
  --disable-debug
  --disable-ffplay
  --disable-ffprobe
  --disable-devices
  --enable-indev=lavfi
  --disable-network
  --pkg-config=false
  "--ar=${toolchain}/bin/llvm-ar"
  "--ranlib=${toolchain}/bin/llvm-ranlib"
  "--nm=${toolchain}/bin/llvm-nm"
  "--strip=${toolchain}/bin/llvm-strip"
  "--sysroot=${toolchain}/sysroot"
  "--extra-cflags=-O2 -fPIE"
  "--extra-ldflags=-fPIE -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)

verify_executable() {
  local executable="$1" expected_machine="$2" expected_interpreter="$3"
  local headers program_headers needed_library alignment
  headers="$("${toolchain}/bin/llvm-readelf" --file-header "${executable}")"
  grep -Eq 'Type:[[:space:]]+DYN' <<< "${headers}"
  grep -Fq "${expected_machine}" <<< "${headers}"
  program_headers="$("${toolchain}/bin/llvm-readelf" --program-headers --wide "${executable}")"
  grep -Fq "${expected_interpreter}" <<< "${program_headers}"
  mapfile -t load_alignments < <(awk '$1 == "LOAD" { print $NF }' <<< "${program_headers}")
  [[ "${#load_alignments[@]}" -gt 0 ]]
  for alignment in "${load_alignments[@]}"; do
    (( alignment >= 16384 )) || { printf 'ELF LOAD alignment is below 16 KiB.\n' >&2; return 1; }
  done
  mapfile -t needed_libraries < <(
    "${toolchain}/bin/llvm-readelf" --dynamic "${executable}" |
      awk '/\(NEEDED\)/ { sub(/.*\[/, ""); sub(/\].*/, ""); print }'
  )
  for needed_library in "${needed_libraries[@]}"; do
    case "${needed_library}" in
      libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so|libmediandk.so) ;;
      *) printf 'Unpackaged FFmpeg dependency: %s\n' "${needed_library}" >&2; return 1 ;;
    esac
  done
}

build_abi() {
  local abi="$1" arch="$2" triple="$3" machine="$4" interpreter="$5"
  local build_directory="${work}/build-${abi}"
  local executable="${output_directory}/jniLibs/${abi}/libffmpeg.so"
  local abi_flags=("--arch=${arch}" "--cc=${toolchain}/bin/${triple}${ANDROID_API}-clang"
                   "--cxx=${toolchain}/bin/${triple}${ANDROID_API}-clang++")
  if [[ "${arch}" == "arm" ]]; then abi_flags+=(--cpu=armv7-a); fi
  # The emulator smoke build does not require host NASM; retain the C fallbacks.
  if [[ "${arch}" == "x86_64" ]]; then abi_flags+=(--disable-x86asm); fi
  test -x "${toolchain}/bin/${triple}${ANDROID_API}-clang"
  mkdir -p "${build_directory}" "$(dirname "${executable}")"
  printf 'Building FFmpeg %s for %s (Android API %s)\n' "${FFMPEG_VERSION}" "${abi}" "${ANDROID_API}"
  (
    cd "${build_directory}"
    "${source_directory}/configure" "${configure_flags[@]}" "${abi_flags[@]}"
    grep -Eq '^#define CONFIG_GPL 0$' config.h
    grep -Eq '^#define CONFIG_VERSION3 0$' config.h
    grep -Eq '^#define CONFIG_NONFREE 0$' config.h
    make -j "${jobs}" ffmpeg
  )
  cp "${build_directory}/ffmpeg" "${executable}"
  "${toolchain}/bin/llvm-strip" --strip-unneeded "${executable}"
  chmod 755 "${executable}"
  verify_executable "${executable}" "${machine}" "${interpreter}"
  {
    printf 'ABI: %s\nAndroid API: %s\nAndroid NDK: %s\n' "${abi}" "${ANDROID_API}" "${ndk_version}"
    printf 'Configure command: '
    printf '%q ' ./configure "${configure_flags[@]}" "${abi_flags[@]}"
    printf '\nExecutable SHA-256: '
    sha256sum "${executable}" | awk '{ print $1 }'
  } > "${notices}/BUILD-${abi}.txt"
  cp "${notices}/BUILD-${abi}.txt" "${distribution}/FFmpeg-BUILD-${abi}.txt"
}

for abi in "${abis[@]}"; do
  case "${abi}" in
    arm64-v8a) build_abi "${abi}" aarch64 aarch64-linux-android AArch64 /system/bin/linker64 ;;
    armeabi-v7a) build_abi "${abi}" arm armv7a-linux-androideabi ARM /system/bin/linker ;;
    x86_64) build_abi "${abi}" x86_64 x86_64-linux-android "Advanced Micro Devices X86-64" /system/bin/linker64 ;;
  esac
done

{
  printf 'FFmpeg %s\n' "${FFMPEG_VERSION}"
  printf 'Official source: https://ffmpeg.org/releases/%s\n' "${archive}"
  printf 'Release signing key: %s\n' "${FFMPEG_SIGNING_KEY}"
  printf 'Source SHA-256: '
  sha256sum "${distribution}/${archive}" | awk '{ print $1 }'
  printf 'Build script SHA-256: '
  sha256sum "${script_file}" | awk '{ print $1 }'
  printf 'Android NDK: %s\nAndroid minimum API: %s\n' "${ndk_version}" "${ANDROID_API}"
  printf '\nLicense: GNU Lesser General Public License, version 2.1 or later.\n'
  printf 'See COPYING.LGPLv2.1 and LICENSE.md. GPL, version3 and nonfree code are disabled.\n'
  printf 'FFmpeg libraries are linked statically into a separate PIE executable; only Android system libraries are required.\n'
  printf 'No external codec libraries or network protocols are enabled. Local-media muxers, demuxers and codecs use LGPL defaults.\n'
  printf 'The executable is packaged as lib/<abi>/libffmpeg.so solely for Android installer extraction, and is run as a child process.\n'
  printf '\nCorresponding source, signature, release key and this build script accompany each draft release.\n'
  printf 'Rebuild on Linux with: bash build-android-ffmpeg.sh --ndk /path/to/android-ndk --output build/ffmpeg\n'
  printf 'Per-ABI configure commands and executable hashes are in BUILD-<abi>.txt.\n'
} > "${notices}/BUILD.txt"
cp "${notices}/BUILD.txt" "${distribution}/FFmpeg-NOTICE.txt"
printf 'Android FFmpeg outputs are ready in %s\n' "${output_directory}"
