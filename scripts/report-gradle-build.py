#!/usr/bin/env python3
"""Report native task cache outcomes without publishing signing properties."""

import json
import os
import re
import sys
from html import escape
from html.parser import HTMLParser
from pathlib import Path


NATIVE_TASK = re.compile(r"^:fluxdown-android:flux(?:CargoNdk|UniffiBindgen)")
TASK_LINE = re.compile(
    r"^> Task (:[^\s]+)(?: (FROM-CACHE|UP-TO-DATE|FAILED|SKIPPED|NO-SOURCE))?\s*$",
    re.MULTILINE,
)
SIGNING_KEYS = {"storePassword", "keyPassword", "keyAlias", "storeFile"}
ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def strip_ansi(text):
    """Remove ANSI CSI sequences before parsing or matching signing values."""
    return ANSI_ESCAPE.sub("", text)


def decode_property(value):
    """Decode the escapes emitted by the workflow's Java-properties writer."""
    def unescape(match):
        escaped = match.group(1)
        if re.fullmatch(r"u[0-9a-fA-F]{4}", escaped):
            return chr(int(escaped[1:], 16))
        return {"n": "\n", "r": "\r", "t": "\t", "f": "\f"}.get(escaped, escaped)

    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", unescape, value)


def signing_values(properties):
    values = set()
    if properties.is_file():
        for line in properties.read_text(encoding="ISO-8859-1").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() in SIGNING_KEYS and value:
                values.update((value, decode_property(value), decode_property(value).lstrip()))
    values.update(escape(value) for value in tuple(values))
    return sorted(filter(None, values), key=len, reverse=True)


def sanitize(text, values):
    text = strip_ansi(text)
    for value in values:
        text = text.replace(value, "[REDACTED]")
    return re.sub(
        r"(?im)^(\s*(?:storePassword|keyPassword|keyAlias|storeFile)\s*[:=]\s*).*$",
        r"\1[REDACTED]",
        text,
    )


class ProfileRows(HTMLParser):
    """Extract task rows from Gradle's --profile HTML, ignoring page layout."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tasks = {}
        self.cells = []
        self.cell = None

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.cells = []
        elif tag in {"td", "th"}:
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in {"td", "th"} and self.cell is not None:
            self.cells.append("".join(self.cell).strip())
            self.cell = None
        elif tag == "tr" and len(self.cells) >= 3 and self.cells[2] != "(total)":
            path, duration = self.cells[:2]
            if (path.startswith(":") and re.fullmatch(r"[\w:.-]+", path)
                    and re.fullmatch(r"(?:\d+(?:\.\d+)?[dhms])+", duration)):
                self.tasks[path] = duration


def native_outcomes(log):
    return {
        path: outcome or "REBUILT"
        for path, outcome in TASK_LINE.findall(strip_ansi(log))
        if NATIVE_TASK.match(path)
    }


def report(raw_log, root=Path(".")):
    if not raw_log.is_file():
        print("::warning title=Gradle timing report::No Gradle log was produced; build may have stopped before execution.")
        return

    output = root / "build/ci-reports"
    output.mkdir(parents=True, exist_ok=True)
    sensitive = signing_values(root / "local.properties")
    raw_text = raw_log.read_text(encoding="utf-8", errors="replace")
    # Derive public task names before redaction: a signing alias such as
    # "fluxdown" must not hide the known native task's cache state.
    outcomes = native_outcomes(raw_text)
    log = sanitize(raw_text, sensitive)
    (output / "gradle.log").write_text(log, encoding="utf-8")

    # Only sanitized copies are uploaded. The raw log stays in RUNNER_TEMP;
    # local.properties, configuration-cache state and keystores are never copied.
    profiles = root / "build/reports/profile"
    profile_files = sorted(profiles.glob("profile-*.html"))
    timings = {}
    if profile_files:
        parser = ProfileRows()
        parser.feed(profile_files[-1].read_text(encoding="utf-8"))
        timings = parser.tasks
        for source in profiles.rglob("*"):
            if source.is_file() and source.suffix in {".html", ".css", ".js"}:
                destination = output / "profile" / source.relative_to(profiles)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(
                    sanitize(source.read_text(encoding="utf-8", errors="replace"), sensitive),
                    encoding="utf-8",
                )

    native = [
        {"task": task, "outcome": state, "duration": timings.get(task, "unavailable")}
        for task, state in outcomes.items()
    ]
    elapsed = os.environ.get("GRADLE_BUILD_SECONDS", "")
    elapsed = int(elapsed) if elapsed.isdecimal() else None
    metrics = {"elapsed_seconds": elapsed, "native_tasks": native, "task_durations": timings}
    (output / "timings.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")

    hits = sum(item["outcome"] == "FROM-CACHE" for item in native)
    unchanged = sum(item["outcome"] == "UP-TO-DATE" for item in native)
    rebuilt = sum(item["outcome"] in {"REBUILT", "FAILED"} for item in native)
    wall_time = f"{elapsed} s" if elapsed is not None else "unavailable"
    print(
        "::notice title=FluxDown native cache::"
        f"FROM-CACHE={hits}, UP-TO-DATE={unchanged}, MISS/rebuilt={rebuilt}; "
        f"Gradle wall time={wall_time}. Per-task timings are in the job summary and report artifact."
    )
    summary = [
        "### Gradle build timing and native cache", "",
        f"Gradle wall time: {wall_time}. Native cache hits: {hits}; unchanged: {unchanged}; rebuilt: {rebuilt}.",
        "", "| Native task | Cache outcome | Task time |", "| --- | --- | --- |",
    ]
    summary.extend(f"| `{item['task']}` | {item['outcome']} | {item['duration']} |" for item in native)
    if not native:
        summary.append("| No native tasks reached | unavailable | unavailable |")
    summary += [
        "", "`FROM-CACHE` restores native outputs without running Cargo; `UP-TO-DATE` reuses this runner's outputs. "
        "A first run after changing source, task implementation or toolchain is expected to rebuild.",
        "", "The setup-gradle job summary separately lists restored and saved Gradle cache entries. "
        "The report artifact contains a sanitized console log, per-task timing JSON and the local profile report.", "",
    ]
    summary_text = "\n".join(summary)
    (output / "summary.md").write_text(summary_text, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(summary_text)


if __name__ == "__main__":
    report(Path(sys.argv[1]))
