#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: bash scripts/test-android-ffmpeg.sh <Android FFmpeg jniLibs directory>" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "${script_dir}/.." && pwd)"
native_dir="$(cd -- "$1" && pwd)"
project_dir="${repo_dir}/tests/ffmpeg-smoke"
log_dir="${repo_dir}/build/ffmpeg-smoke/logs"
package="com.fongmi.ffmpegsmoke"
mkdir -p "${log_dir}"

command -v adb >/dev/null || { echo "adb is required on PATH" >&2; exit 2; }
adb get-state >/dev/null

fail_with_diagnostics() {
  local status=$?
  trap - ERR
  local diagnostic
  diagnostic="$(tail -n 120 "${log_dir}/gradle.log" "${log_dir}/result.txt" 2>/dev/null | head -c 12000 || true)"
  diagnostic="${diagnostic//'%'/'%25'}"
  diagnostic="${diagnostic//$'\r'/'%0D'}"
  diagnostic="${diagnostic//$'\n'/'%0A'}"
  echo "::error title=Android FFmpeg smoke test failed::${diagnostic}"
  exit "${status}"
}
trap fail_with_diagnostics ERR

bash "${repo_dir}/gradlew" --no-daemon --console=plain \
  -p "${project_dir}" \
  "-Pfluxdown.ffmpegDir=${native_dir}" \
  assembleDebug 2>&1 | tee "${log_dir}/gradle.log"

shopt -s nullglob
apks=("${project_dir}"/build/outputs/apk/debug/*.apk)
if [[ ${#apks[@]} -ne 1 ]]; then
  echo "Expected one smoke-test APK, found ${#apks[@]}" >&2
  exit 1
fi

# Only this dedicated test package is removed, including stale results from older smoke runs.
cleanup() {
  adb shell am force-stop "${package}" >/dev/null 2>&1 || true
  adb uninstall "${package}" >/dev/null 2>&1 || true
}
trap cleanup EXIT
adb uninstall "${package}" >/dev/null 2>&1 || true
adb install "${apks[0]}"
adb shell am start -W -n "${package}/.SmokeActivity" | tee "${log_dir}/launch.log"

for ((attempt = 0; attempt < 60; attempt++)); do
  result="$(adb shell run-as "${package}" cat files/result.txt 2>/dev/null | tr -d '\r' || true)"
  if [[ "${result}" == SUCCESS$'\n'* ]]; then
    printf '%s\n' "${result}" | tee "${log_dir}/result.txt"
    exit 0
  fi
  if [[ "${result}" == FAILURE$'\n'* ]]; then
    printf '%s\n' "${result}" | tee "${log_dir}/result.txt" >&2
    adb logcat -d -s 'FFmpegSmoke:*' 'AndroidRuntime:E' > "${log_dir}/logcat.txt" || true
    false
  fi
  sleep 1
done

echo "Timed out after 60 seconds waiting for the app-domain FFmpeg smoke test" | tee "${log_dir}/result.txt" >&2
adb logcat -d -s 'FFmpegSmoke:*' 'AndroidRuntime:E' > "${log_dir}/logcat.txt" || true
false
