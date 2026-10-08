import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/report-gradle-build.py"
SPEC = importlib.util.spec_from_file_location("gradle_build_report", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class GradleBuildReportTest(unittest.TestCase):
    def test_native_outcomes_distinguish_restored_unchanged_rebuilt_and_failed(self):
        log = """> Task :fluxdown-android:fluxCargoNdkBindings FROM-CACHE
> Task :fluxdown-android:fluxUniffiBindgenRelease UP-TO-DATE
> Task :fluxdown-android:fluxCargoNdkRelease
> Task :fluxdown-android:fluxCargoNdkDebug FAILED
> Task :app:compileLeanbackReleaseKotlin FROM-CACHE
"""
        self.assertEqual(REPORT.native_outcomes(log), {
            ":fluxdown-android:fluxCargoNdkBindings": "FROM-CACHE",
            ":fluxdown-android:fluxUniffiBindgenRelease": "UP-TO-DATE",
            ":fluxdown-android:fluxCargoNdkRelease": "REBUILT",
            ":fluxdown-android:fluxCargoNdkDebug": "FAILED",
        })

    def test_native_outcomes_strip_ansi_before_parsing_task_states(self):
        log = (
            "\x1b[1m> Task :fluxdown-android:fluxCargoNdkBindings\x1b[0m "
            "\x1b[32mFROM-CACHE\x1b[0m\n"
            "> Task :fluxdown-android:fluxUniffiBindgenRelease "
            "\x1b[33mUP-TO-DATE\x1b[0m\n"
            "\x1b[1m> Task :fluxdown-android:fluxCargoNdkRelease\x1b[0m\n"
        )
        self.assertEqual(REPORT.native_outcomes(log), {
            ":fluxdown-android:fluxCargoNdkBindings": "FROM-CACHE",
            ":fluxdown-android:fluxUniffiBindgenRelease": "UP-TO-DATE",
            ":fluxdown-android:fluxCargoNdkRelease": "REBUILT",
        })

    def test_profile_parser_handles_nested_cells_and_ignores_project_rows(self):
        parser = REPORT.ProfileRows()
        parser.feed("""<table><tr><th>Task</th><th>Duration</th></tr>
<tr><td>:fluxdown-android</td><td>2m3s</td></tr>
<tr><td><a>:fluxdown-android:fluxCargoNdkRelease</a></td><td>1m5.123s</td><td>FROM-CACHE</td></tr>
</table>""")
        self.assertEqual(parser.tasks[":fluxdown-android:fluxCargoNdkRelease"], "1m5.123s")

    def test_java_property_decoding(self):
        self.assertEqual(REPORT.decode_property(r"p\=a\:ss\\word\n\r\t\u0041"), "p=a:ss\\word\n\r\tA")

    def test_signing_values_are_also_redacted_when_html_escaped(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            properties = Path(directory) / "local.properties"
            properties.write_text('storePassword=secret&<>"\n', encoding="ISO-8859-1")
            sensitive = REPORT.signing_values(properties)
            self.assertEqual(
                REPORT.sanitize("<p>secret&amp;&lt;&gt;&quot;</p>", sensitive),
                "<p>[REDACTED]</p>",
            )

    def test_sanitizer_redacts_ansi_split_plain_and_html_info_values(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            properties = Path(directory) / "local.properties"
            properties.write_text('storePassword=secret&<>"\n', encoding="ISO-8859-1")
            sensitive = REPORT.signing_values(properties)
            self.assertEqual(
                REPORT.sanitize('Command: --ks-pass pass:sec\x1b[0mret&<>"', sensitive),
                "Command: --ks-pass pass:[REDACTED]",
            )
            self.assertEqual(
                REPORT.sanitize("<p>sec\x1b[0mret&amp;&lt;&gt;&quot;</p>", sensitive),
                "<p>[REDACTED]</p>",
            )

    def test_signing_alias_matching_task_path_does_not_hide_cache_hits(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            root = Path(directory)
            (root / "local.properties").write_text(
                "keyAlias=fluxdown\nstorePassword=never-publish-password\n", encoding="ISO-8859-1",
            )
            raw = root / "raw.log"
            raw.write_text(
                "> Task :fluxdown-android:fluxCargoNdkRelease FROM-CACHE\nnever-publish-password\n",
                encoding="utf-8",
            )
            profiles = root / "build/reports/profile"
            profiles.mkdir(parents=True)
            (profiles / "profile-2026-10-08.html").write_text(
                "<table><tr><td>:fluxdown-android:fluxCargoNdkRelease</td>"
                "<td>0.456s</td><td>FROM-CACHE</td></tr></table>", encoding="utf-8",
            )
            with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": ""}):
                with contextlib.redirect_stdout(io.StringIO()) as stdout:
                    REPORT.report(raw, root)
            self.assertIn("FROM-CACHE=1", stdout.getvalue())
            metrics = json.loads((root / "build/ci-reports/timings.json").read_text(encoding="utf-8"))
            self.assertEqual(metrics["native_tasks"][0]["duration"], "0.456s")
            for file in (root / "build/ci-reports").rglob("*"):
                if file.is_file():
                    self.assertNotIn("never-publish-password", file.read_text(encoding="utf-8"))

    def test_report_redacts_signing_values_and_copies_only_safe_profile_files(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            root = Path(directory)
            (root / "local.properties").write_text(
                "storePassword=p\\=a\\:ss\nkeyAlias=private-alias\nstoreFile=/private/release.jks\n",
                encoding="ISO-8859-1",
            )
            raw = root / "raw.log"
            raw.write_text(
                "> Task :fluxdown-android:fluxCargoNdkRelease FROM-CACHE\n"
                "decoded p=a:ss escaped p\\=a\\:ss private-alias /private/release.jks\n",
                encoding="utf-8",
            )
            profiles = root / "build/reports/profile"
            profiles.mkdir(parents=True)
            (profiles / "profile-2026-10-08.html").write_text(
                "<p>p=a:ss</p><table><tr><td>:fluxdown-android:fluxCargoNdkRelease</td>"
                "<td>0.456s</td><td>FROM-CACHE</td></tr></table>", encoding="utf-8",
            )
            (profiles / "secret.jks").write_bytes(b"not for publication")
            summary = root / "job-summary.md"
            with patch.dict(os.environ, {"GRADLE_BUILD_SECONDS": "12", "GITHUB_STEP_SUMMARY": str(summary)}):
                with contextlib.redirect_stdout(io.StringIO()) as stdout:
                    REPORT.report(raw, root)
            self.assertIn("FROM-CACHE=1", stdout.getvalue())
            self.assertIn("Gradle wall time=12 s", stdout.getvalue())
            output = root / "build/ci-reports"
            for file in output.rglob("*"):
                if file.is_file():
                    contents = file.read_text(encoding="utf-8")
                    for secret in ("p=a:ss", r"p\=a\:ss", "private-alias", "/private/release.jks"):
                        self.assertNotIn(secret, contents)
            self.assertFalse((output / "profile/secret.jks").exists())
            self.assertFalse((output / "local.properties").exists())
            metrics = json.loads((output / "timings.json").read_text(encoding="utf-8"))
            self.assertEqual(metrics["elapsed_seconds"], 12)
            self.assertEqual(metrics["native_tasks"][0]["duration"], "0.456s")
            self.assertIn("FROM-CACHE", summary.read_text(encoding="utf-8"))

    def test_info_log_and_html_redact_signing_values_split_by_ansi(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            root = Path(directory)
            (root / "local.properties").write_text(
                'storePassword=secret&<>"\nkeyAlias=fluxdown\nstoreFile=/private/release.jks\n',
                encoding="ISO-8859-1",
            )
            raw = root / "raw.log"
            raw.write_text(
                "\x1b[1m> Task :fluxdown-android:fluxCargoNdkRelease\x1b[0m "
                "\x1b[32mFROM-CACHE\x1b[0m\n"
                "Starting process: command apksigner --ks-pass pass:sec\x1b[0mret&<>\" "
                "--ks-key-alias flux\x1b[0mdown --ks /private/\x1b[0mrelease.jks\n",
                encoding="utf-8",
            )
            profiles = root / "build/reports/profile"
            profiles.mkdir(parents=True)
            (profiles / "profile-2026-10-08.html").write_text(
                "<p>sec\x1b[0mret&amp;&lt;&gt;&quot;</p>"
                "<table><tr><td>:fluxdown-android:fluxCargoNdkRelease</td>"
                "<td>0.456s</td><td>FROM-CACHE</td></tr></table>",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": ""}):
                with contextlib.redirect_stdout(io.StringIO()) as stdout:
                    REPORT.report(raw, root)
            self.assertIn("FROM-CACHE=1", stdout.getvalue())
            output = root / "build/ci-reports"
            metrics = json.loads((output / "timings.json").read_text(encoding="utf-8"))
            self.assertEqual(metrics["native_tasks"][0]["outcome"], "FROM-CACHE")
            for file in output.rglob("*"):
                if not file.is_file():
                    continue
                contents = file.read_text(encoding="utf-8")
                self.assertNotIn("\x1b", contents)
                self.assertNotIn('secret&<>"', contents)
                self.assertNotIn("secret&amp;&lt;&gt;&quot;", contents)
                self.assertNotIn("sec\x1b[0mret", contents)
                self.assertNotIn("flux\x1b[0mdown", contents)
                self.assertNotIn("/private/release.jks", contents)
            sanitized_log = (output / "gradle.log").read_text(encoding="utf-8")
            self.assertIn("pass:[REDACTED]", sanitized_log)
            self.assertIn("--ks-key-alias [REDACTED]", sanitized_log)
            self.assertIn("--ks [REDACTED]", sanitized_log)

    def test_absent_log_reports_no_false_cache_hit(self):
        with tempfile.TemporaryDirectory(dir=SCRIPT.parent.parent) as directory:
            with contextlib.redirect_stdout(io.StringIO()) as stdout:
                REPORT.report(Path(directory) / "missing.log", Path(directory))
            self.assertIn("No Gradle log", stdout.getvalue())
            self.assertNotIn("FROM-CACHE=", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
