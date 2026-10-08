"""Guard the CI settings which caused stale Rust caches and JVM heap exhaustion.

These are workflow contract tests, not an Android build or speed benchmark.
"""

from pathlib import Path
import re
import shlex
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/fluxdown-draft-apk.yml"


class DraftApkWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.rust_cache = next(
            step for step in re.split(r"(?m)^      - name: ", cls.workflow)
            if "uses: Swatinem/rust-cache@v2" in step
        )
        command = re.search(
            r"(?ms)^ *\./gradlew +(.+?)(?=^ +2>&1 +\| +tee)", cls.workflow,
        )
        if command is None:
            raise AssertionError("The main Gradle invocation must retain its captured console log")
        cls.gradle_args = shlex.split(command.group(1).replace("\\\n", " "))

    def test_rust_cache_separates_release_profile_and_android_tool_versions(self):
        cache_key = re.search(r"(?m)^          key: (.+)$", self.rust_cache)
        self.assertIsNotNone(cache_key)
        key = cache_key.group(1)
        self.assertRegex(key, r"release-v\d+")
        self.assertIn("${{ env.NDK_VERSION }}", key)
        self.assertIn("${{ env.CARGO_NDK_VERSION }}", key)
        self.assertIn('CARGO_NDK_VERSION: "4.1.2"', self.workflow)
        self.assertIn("tool: cargo-ndk@${{ env.CARGO_NDK_VERSION }}", self.workflow)

    def test_rust_cache_never_freezes_a_failed_debug_only_seed(self):
        # An exact rust-cache hit skips saving. Only a complete successful build
        # may seed this Release namespace, not a failure before Release starts.
        self.assertRegex(self.rust_cache, r"(?m)^          cache-on-failure: false$")
        self.assertRegex(self.rust_cache, r"(?m)^          cache-workspace-crates: false$")
        self.assertIn("workspaces: rust/fluxdown -> target", self.rust_cache)

    def test_ci_daemon_heap_and_worker_limit_preserve_required_jvm_flags(self):
        jvm_args = [arg for arg in self.gradle_args if arg.startswith("-Dorg.gradle.jvmargs=")]
        self.assertEqual(len(jvm_args), 1)
        flags = shlex.split(jvm_args[0].split("=", 1)[1])
        self.assertIn("-Xmx4g", flags)
        self.assertIn("-Dfile.encoding=UTF-8", flags)
        self.assertIn("--add-exports", flags)
        self.assertIn("jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED", flags)
        self.assertIn("--max-workers=2", self.gradle_args)

    def test_ci_retains_release_variants_tests_and_safe_cache_diagnostics(self):
        for arg in (
            ":app:testLeanbackDebugUnitTest", ":app:assembleLeanbackRelease",
            ":app:assembleMobileRelease", "--build-cache", "--profile", "--info",
        ):
            self.assertIn(arg, self.gradle_args)
        self.assertNotIn("--debug", self.gradle_args)
        self.assertFalse(any(arg.startswith("-x") for arg in self.gradle_args))
        self.assertIn("-p 'test_*.py'", self.workflow)


if __name__ == "__main__":
    unittest.main()
