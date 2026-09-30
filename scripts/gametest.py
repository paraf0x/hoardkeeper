#!/usr/bin/env python3
"""Run the hoardkeeper client gametests, one Minecraft launch per scenario.

    scripts/gametest.py [scenario ...] [--all] [--timeout SECONDS] [--force] [--no-daemon]
    scripts/gametest.py --chained [--timeout SECONDS] [--force] [--no-daemon]
    scripts/gametest.py --list | --sweep | --self-test

No arguments means --all: one Minecraft launch per scenario, one gradlew invocation each. This
stays the default -- and the one evidence runs should use -- because a hang or a crash partway
through a --chained run takes every scenario queued after it with it (nothing catches a thrown
AssertionError between scenarios inside that one JVM), where a crashed scenario here costs only
itself. --chained runs every scenario in a single launch instead (-Pscenario=all,
dev.hoardkeeper.gametest.GameTestReset resets the mod's singletons and wipes its shared
on-disk directory between each pair) and pays for one JVM/world startup instead of ten -- roughly
twice as fast in practice, not the order of magnitude the JVM count alone would suggest, since
each scenario's own scan and container placement still cost the same either way. Use it for fast
local iteration once you already trust an --all run is green.

Exit code 0 only if every scenario passed.
Design: docs/superpowers/specs/2026-09-08-client-gametest-harness-design.md, section 5.
Standard library only.
"""
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "build" / "run" / "clientGameTest"
REPORTS = ROOT / "build" / "reports" / "gametest"
GOLDEN = ROOT / "scripts" / "testdata" / "result.golden.json"
JDK25 = "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
MARKER = "hoardkeeper.gametest.run="
API_MARKER = "-Dfabric.client.gametest"
# GameTestEntry.ALL is the authority; keep this list in the same order.
SCENARIOS = ["boot", "scan", "search", "search-key", "retry", "resume", "upload", "passive", "measured", "site",
             "peek", "nudge"]
HIDDEN = ["harness-fail"]
FOCUS_WATCH_SECONDS = 90
RESULT_KEYS = {"scenario", "pass", "durationMs", "chained", "checks", "numbers", "screenshots", "error"}


# ------------------------------------------------------------------ processes

def sh(args, timeout=5, **kw):
    """A stuck `ps`/`vm_stat`/`lsappinfo`/`open` call must never stall the runner: on a timeout
    this returns an empty, well-shaped result (returncode=-1) instead of raising."""
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout, **kw)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, -1, stdout="", stderr="")


def java_major(home):
    try:
        out = sh([f"{home}/bin/java", "-version"]).stderr
    except OSError:
        return None
    m = re.search(r'version "(\d+)', out)
    return int(m.group(1)) if m else None


def java_home():
    env = os.environ.get("JAVA_HOME")
    if env and java_major(env) == 25:
        return env
    if java_major(JDK25) == 25:
        return JDK25
    sys.exit(f"no JDK 25 found: JAVA_HOME={env!r}, {JDK25} missing")


def ps_listing():
    return sh(["ps", "-axo", "pid=,command="]).stdout


def marker_pids(listing, marker):
    """PIDs whose command line carries `marker`, never this runner itself."""
    pids = []
    for line in listing.splitlines():
        line = line.strip()
        if not line:
            continue
        pid, _, cmd = line.partition(" ")
        if marker in cmd and "gametest.py" not in cmd:
            pids.append(int(pid))
    return pids


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def kill_pids(pids):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        alive = [p for p in pids if pid_alive(p)]
        if not alive:
            return
        for p in alive:
            try:
                os.kill(p, sig)
            except ProcessLookupError:
                pass
        deadline = time.time() + 10
        while time.time() < deadline and any(pid_alive(p) for p in alive):
            time.sleep(0.2)


def rss_mb(pid):
    out = sh(["ps", "-o", "rss=", "-p", str(pid)]).stdout.strip()
    return int(out) // 1024 if out.isdigit() else 0


def free_memory_gb():
    out = sh(["vm_stat"]).stdout
    page = re.search(r"page size of (\d+)", out)
    size = int(page.group(1)) if page else 16384

    def pages(name):
        m = re.search(rf"{name}:\s+(\d+)", out)
        return int(m.group(1)) if m else 0

    return (pages("Pages free") + pages("Pages inactive")) * size / 2 ** 30


# ------------------------------------------------------------------ focus

def frontmost():
    """The frontmost app's display name, via `lsappinfo` -- needs no macOS Automation permission,
    unlike the osascript/System Events approach this replaced. Returns None if either command
    fails, times out (sh()'s returncode=-1 sentinel), is missing entirely (OSError), or the output
    doesn't parse."""
    try:
        front = sh(["lsappinfo", "front"], timeout=2)
    except OSError:
        return None
    if front.returncode != 0:
        return None
    asn = front.stdout.strip()
    if not asn:
        return None
    try:
        info = sh(["lsappinfo", "info", "-only", "name", asn], timeout=2)
    except OSError:
        return None
    if info.returncode != 0:
        return None
    m = re.search(r'"LSDisplayName"="([^"]*)"', info.stdout)
    return m.group(1) if m else None


def reactivate(app_name):
    """Degraded fallback: re-activate whatever application was in front immediately before java
    took over, once. `open -a` needs no Automation permission either."""
    try:
        sh(["open", "-a", app_name], timeout=5)
    except OSError:
        pass


# ------------------------------------------------------------------ one scenario

def shielded(fn):
    """Run fn with SIGINT/SIGTERM ignored: cleanup must finish even if the user hits Ctrl-C twice."""
    old_int = signal.signal(signal.SIGINT, signal.SIG_IGN)
    old_term = signal.signal(signal.SIGTERM, signal.SIG_IGN)
    try:
        return fn()
    finally:
        signal.signal(signal.SIGINT, old_int)
        signal.signal(signal.SIGTERM, old_term)


def verdict(rc, result, timed_out):
    """A scenario is a PASS only if gradlew exited 0, the mod's own result.json says pass, and the
    runner did not have to time it out -- any one of those alone can lie (a Java-side pass with a
    non-zero task exit, a result left over from a prior run, a hang that never reports)."""
    return rc == 0 and bool(result.get("pass")) and not timed_out


def run_scenario(name, run_id, timeout, no_daemon, env):
    out_dir = REPORTS / run_id / name
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [str(ROOT / "gradlew"), "runClientGameTest", f"-Pscenario={name}", f"-PrunId={run_id}",
           "--console=plain"]
    if no_daemon:
        cmd.append("--no-daemon")
    started = time.time()
    peak = 0
    focus_stolen = False
    focus_watch = True
    reactivated = False
    previous_app = None
    jvm = None
    timed_out = False
    interrupted = False
    with open(out_dir / "gradle.log", "w") as log:
        proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env,
                                start_new_session=True)
        try:
            while proc.poll() is None:
                elapsed = time.time() - started
                if elapsed > timeout:
                    timed_out = True
                    break
                if jvm is None:
                    found = marker_pids(ps_listing(), MARKER + run_id)
                    jvm = found[0] if found else None
                if jvm is not None:
                    peak = max(peak, rss_mb(jvm))
                if focus_watch and elapsed < FOCUS_WATCH_SECONDS and not reactivated:
                    front = frontmost()
                    if front is None:
                        focus_watch = False
                    elif front.lower() == "java":
                        focus_stolen = True
                        if previous_app is not None:
                            reactivate(previous_app)
                        reactivated = True
                    else:
                        previous_app = front
                time.sleep(0.5)
        except KeyboardInterrupt:
            # Covers both a real Ctrl-C and the SIGTERM handler below, which raises this same
            # exception so `kill <runner pid>` takes the identical cleanup path.
            timed_out = True
            interrupted = True
        except BaseException:
            # Any other abnormal exit from the loop -- an OSError out of frontmost(), a bad
            # marker_pids() parse, anything unforeseen -- must still terminate gradlew and the
            # Minecraft JVM instead of orphaning them; only the cleanup differs from the paths
            # above, so this re-raises once `finally` below has run terminate().
            timed_out = True
            raise
        finally:
            if timed_out:
                # A second SIGINT/SIGTERM landing mid-cleanup must not cut this short: terminate()
                # can run for up to ~30s (two signal rounds plus the marker sweep below).
                shielded(lambda: terminate(proc, run_id))
    rc = proc.returncode if proc.returncode is not None else -1
    seconds = int(time.time() - started)
    extra = {"peakRssMb": peak, "focusStolen": focus_stolen, "exitCode": rc,
             "timedOut": timed_out, "seconds": seconds,
             "focusWatch": "active" if focus_watch else "unavailable",
             "interrupted": interrupted, "frontmostBefore": previous_app}
    if timed_out:
        # Same shielding for the write that marks this scenario interrupted -- a normal
        # (non-interrupted, non-timed-out) scenario end must not pay for this.
        result = shielded(lambda: collect(name, out_dir, extra))
    else:
        result = collect(name, out_dir, extra)
    if interrupted:
        # The row is marked (result.json above) and collected; re-raise so main()'s loop stops
        # launching further scenarios instead of opening the next Minecraft window.
        raise KeyboardInterrupt()
    ok = verdict(rc, result, timed_out)
    focus = "n/a" if not focus_watch else ("yes" if focus_stolen else "no")
    return {"name": name, "ok": ok, "seconds": seconds, "peak": peak, "focus": focus,
            "numbers": result.get("numbers") or {}, "result_path": out_dir / "result.json"}


def collect_chained_row(name, out_dir, extra):
    """One scenario's slice of a chained run: dev.hoardkeeper.gametest.Harness.writeResult
    writes gametest/result-<name>.json for every scenario it runs (alongside gametest/result.json,
    which a chained run leaves holding only the last one) -- this reads that file, the same
    contract-checking `collect` applies to the single-scenario result.json, but keyed by name
    instead of by which JVM produced it. Missing entirely means the chain never reached this
    scenario: build.gradle's clearRunDirectory wipes build/run/clientGameTest before every
    invocation, so a stale file from an unrelated earlier run cannot be mistaken for this one.

    Takes `out_dir` rather than deriving it from a run_id, mirroring `collect`'s own shape --
    that is what lets a test monkeypatch RUN_DIR and point `out_dir` at a scratch directory
    without touching the real build/reports/gametest/ tree, the same pattern CollectTests uses
    for the single-scenario path."""
    src = RUN_DIR / "gametest" / f"result-{name}.json"
    if src.exists():
        try:
            result = json.loads(src.read_text())
        except (ValueError, OSError) as exc:
            result = _fail_result(name, "CorruptResult", str(exc), chained=True)
        else:
            missing = RESULT_KEYS - set(result)
            if missing:
                result["pass"] = False
                result["error"] = {"type": "ContractMismatch",
                                   "message": f"result.json lacks {sorted(missing)}", "frames": []}
    else:
        result = _fail_result(name, "NoResult", "did not run in this chained launch (see gradle.log)",
                               chained=True)
    result.update(extra)
    (out_dir / "result.json").write_text(json.dumps(result, indent=2))
    return result


def run_chained(run_id, timeout, no_daemon, env):
    """One gradlew invocation (-Pscenario=all) instead of one per scenario. The watch loop mirrors
    run_scenario's -- not shared with it on purpose, so a change to one launch shape cannot
    silently change the other's tested behaviour -- but the timeout is scaled by scenario count,
    since this single JVM has to get through all of them, and there is only one JVM to watch
    instead of ten in sequence."""
    out_dir = REPORTS / run_id / "_chained"
    out_dir.mkdir(parents=True, exist_ok=True)
    total_timeout = timeout * len(SCENARIOS)
    cmd = [str(ROOT / "gradlew"), "runClientGameTest", "-Pscenario=all", f"-PrunId={run_id}",
           "--console=plain"]
    if no_daemon:
        cmd.append("--no-daemon")
    started = time.time()
    peak = 0
    focus_stolen = False
    focus_watch = True
    reactivated = False
    previous_app = None
    jvm = None
    timed_out = False
    interrupted = False
    with open(out_dir / "gradle.log", "w") as log:
        proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env,
                                start_new_session=True)
        try:
            while proc.poll() is None:
                elapsed = time.time() - started
                if elapsed > total_timeout:
                    timed_out = True
                    break
                if jvm is None:
                    found = marker_pids(ps_listing(), MARKER + run_id)
                    jvm = found[0] if found else None
                if jvm is not None:
                    peak = max(peak, rss_mb(jvm))
                if focus_watch and elapsed < FOCUS_WATCH_SECONDS and not reactivated:
                    front = frontmost()
                    if front is None:
                        focus_watch = False
                    elif front.lower() == "java":
                        focus_stolen = True
                        if previous_app is not None:
                            reactivate(previous_app)
                        reactivated = True
                    else:
                        previous_app = front
                time.sleep(0.5)
        except KeyboardInterrupt:
            timed_out = True
            interrupted = True
        except BaseException:
            timed_out = True
            raise
        finally:
            if timed_out:
                shielded(lambda: terminate(proc, run_id))
    rc = proc.returncode if proc.returncode is not None else -1
    total_seconds = int(time.time() - started)
    focus = "n/a" if not focus_watch else ("yes" if focus_stolen else "no")
    for sub in ("screenshots", "crash-reports"):
        d = RUN_DIR / sub
        if d.is_dir():
            shutil.copytree(d, out_dir / sub, dirs_exist_ok=True)
    latest = RUN_DIR / "logs" / "latest.log"
    if latest.exists():
        shutil.copy(latest, out_dir / "latest.log")

    extra = {"peakRssMb": peak, "focusStolen": focus_stolen, "chainExitCode": rc,
             "chainTimedOut": timed_out, "chainSeconds": total_seconds,
             "focusWatch": "active" if focus_watch else "unavailable"}
    rows = []
    for name in SCENARIOS:
        row_dir = REPORTS / run_id / name
        row_dir.mkdir(parents=True, exist_ok=True)
        result = shielded(lambda n=name, d=row_dir: collect_chained_row(n, d, extra)) if timed_out \
            else collect_chained_row(name, row_dir, extra)
        # durationMs is each scenario's own internal clock (Harness.startedNanos to close()) --
        # more precise here than an externally-measured "seconds" would be, since only one JVM's
        # wall time is directly observable from this side for the whole chain.
        seconds = int(result.get("durationMs", 0) / 1000)
        rows.append({"name": name, "ok": bool(result.get("pass")), "seconds": seconds, "peak": peak,
                     "focus": focus, "numbers": result.get("numbers") or {},
                     "result_path": row_dir / "result.json"})
    if interrupted:
        raise KeyboardInterrupt()
    return rows, total_seconds, rc, timed_out


def terminate(proc, run_id):
    """Process group first, then -- the step that matters -- every JVM carrying the run marker.
    With the Gradle daemon the Minecraft JVM is the daemon's child, not gradlew's, and is not in
    the group at all; the marker is on its own command line, so it is found regardless.

    A timeout that fires while Gradle is still starting can find nothing on a single sweep, with
    the JVM spawning moments later and surviving unnoticed. So this keeps sweeping every 0.5s for
    up to 10s, killing whatever appears each time, stopping early only once gradlew is confirmed
    gone AND two sweeps in a row found nothing."""
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            pass
        deadline = time.time() + 10
        while time.time() < deadline and proc.poll() is None:
            time.sleep(0.2)
        if proc.poll() is not None:
            break
    deadline = time.time() + 10
    empty_sweeps = 0
    while True:
        pids = marker_pids(ps_listing(), MARKER + run_id)
        if pids:
            kill_pids(pids)
            empty_sweeps = 0
        else:
            empty_sweeps += 1
        if proc.poll() is not None and empty_sweeps >= 2:
            break
        if time.time() >= deadline:
            break
        time.sleep(0.5)


def _fail_result(name, err_type, message, chained=False):
    return {"scenario": name, "pass": False, "durationMs": 0, "chained": chained, "checks": [],
            "numbers": {}, "screenshots": [],
            "error": {"type": err_type, "message": message, "frames": []}}


def collect(name, out_dir, extra):
    src = RUN_DIR / "gametest" / "result.json"
    if src.exists():
        try:
            result = json.loads(src.read_text())
        except (ValueError, OSError) as exc:
            # A SIGKILL can land mid-write in Harness.close(); a truncated result.json must not
            # crash the runner and lose summary.md for every scenario that already passed.
            result = _fail_result(name, "CorruptResult", str(exc))
        else:
            missing = RESULT_KEYS - set(result)
            if missing:
                result["pass"] = False
                result["error"] = {"type": "ContractMismatch",
                                   "message": f"result.json lacks {sorted(missing)}", "frames": []}
    else:
        result = _fail_result(name, "NoResult", "no result.json")
    result.update(extra)
    (out_dir / "result.json").write_text(json.dumps(result, indent=2))
    for sub in ("screenshots", "crash-reports"):
        d = RUN_DIR / sub
        if d.is_dir():
            shutil.copytree(d, out_dir / sub, dirs_exist_ok=True)
    latest = RUN_DIR / "logs" / "latest.log"
    if latest.exists():
        shutil.copy(latest, out_dir / "latest.log")
    return result


# ------------------------------------------------------------------ reporting

def summary_md(run_id, rows, interrupted=False, chained=False, total_seconds=None):
    passed = sum(1 for r in rows if r["ok"])
    head_note = " — interrupted" if interrupted else ""
    mode_note = " — chained (single launch)" if chained else ""
    lines = [f"# gametest {run_id} — {passed} passed, {len(rows) - passed} failed{head_note}{mode_note}", ""]
    if chained and total_seconds is not None:
        lines += [f"Total wall time: {total_seconds}s for all {len(rows)} scenarios in one launch. "
                  f"(\"seconds\" below is each scenario's own internal timer; peak RSS and focus "
                  f"stolen are the one shared JVM's, repeated on every row.)", ""]
    lines += ["| scenario | result | seconds | peak RSS | focus stolen | numbers |",
              "|---|---|---|---|---|---|"]
    for r in rows:
        nums = " ".join(f"{k}={v}" for k, v in r["numbers"].items())
        note = "" if r["ok"] else f" — see {r['name']}/result.json"
        lines.append(f"| {r['name']} | {'PASS' if r['ok'] else 'FAIL'} | {r['seconds']} | {r['peak']} MB "
                     f"| {r['focus']} | {nums}{note} |")
    return "\n".join(lines) + "\n"


def print_row(r):
    verdict = "PASS" if r["ok"] else "FAIL"
    tail = "" if r["ok"] else f"  {r['result_path'].relative_to(ROOT)}"
    print(f"{verdict} {r['name']} {r['seconds']}s {r['peak']}MB{tail}", flush=True)


# ------------------------------------------------------------------ commands

def preflight(force):
    env = dict(os.environ, JAVA_HOME=java_home())
    listing = ps_listing()
    stale = sorted(set(marker_pids(listing, MARKER) + marker_pids(listing, API_MARKER)))
    if stale:
        if not force:
            sys.exit(f"gametest JVMs already running (pids {stale}); rerun with --force, or --sweep")
        kill_pids(stale)
    free = free_memory_gb()
    if free < 5:
        print(f"warning: only {free:.1f} GB free memory", file=sys.stderr)
    return env


def sweep():
    listing = ps_listing()
    pids = sorted(set(marker_pids(listing, MARKER) + marker_pids(listing, API_MARKER)))
    print(f"killing {pids or 'nothing'}")
    kill_pids(pids)
    subprocess.run([str(ROOT / "gradlew"), "--stop"], cwd=ROOT)


def _sigterm_to_keyboard_interrupt(signum, frame):
    """SIGINT already raises KeyboardInterrupt; make SIGTERM take the same cleanup path so
    `kill <runner pid>` (not just Ctrl-C) reliably terminates the Minecraft child instead of
    orphaning it."""
    raise KeyboardInterrupt()


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenarios", nargs="*")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--timeout", type=int, default=600, help="seconds per scenario (default 600)")
    ap.add_argument("--force", action="store_true", help="kill stale gametest JVMs instead of refusing")
    ap.add_argument("--no-daemon", action="store_true")
    ap.add_argument("--chained", action="store_true",
                     help="one Minecraft launch for every scenario (-Pscenario=all) instead of one "
                          "per scenario. Roughly twice as fast (one JVM/world startup instead of "
                          "ten), but a hang or a crash partway through takes every scenario queued "
                          "after it -- the per-scenario default "
                          "isolates each scenario's own JVM instead, which is why evidence runs use "
                          "it and this stays opt-in.")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--sweep", action="store_true", help="kill every gametest JVM and stop Gradle daemons")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv)
    if a.self_test:
        ok = unittest.main(module=__name__, argv=["gametest"], exit=False).result.wasSuccessful()
        return 0 if ok else 1
    if a.list:
        print("\n".join(SCENARIOS + [f"{h} (hidden)" for h in HIDDEN]))
        return 0
    if a.sweep:
        sweep()
        return 0
    if a.all and a.scenarios:
        ap.error("--all cannot be combined with explicit scenario names")
    if a.chained and (a.all or a.scenarios):
        ap.error("--chained always runs every scenario; combine with neither --all nor scenario names")
    wanted = SCENARIOS if (a.all or a.chained) else (a.scenarios or SCENARIOS)
    unknown = [s for s in wanted if s not in SCENARIOS + HIDDEN]
    if unknown:
        sys.exit(f"unknown scenario(s) {unknown}; known: {SCENARIOS + HIDDEN}")
    signal.signal(signal.SIGTERM, _sigterm_to_keyboard_interrupt)
    env = preflight(a.force)
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    (REPORTS / run_id).mkdir(parents=True, exist_ok=True)
    rows = []
    interrupted = False
    total_seconds = None
    try:
        if a.chained:
            rows, total_seconds, rc, timed_out = run_chained(run_id, a.timeout, a.no_daemon, env)
            for row in rows:
                print_row(row)
            if rc != 0 or timed_out:
                print(f"chained launch itself exited {rc} (timed_out={timed_out}); see "
                      f"{(REPORTS / run_id / '_chained' / 'gradle.log').relative_to(ROOT)}",
                      file=sys.stderr)
        else:
            for name in wanted:
                row = run_scenario(name, run_id, a.timeout, a.no_daemon, env)
                print_row(row)
                rows.append(row)
    except KeyboardInterrupt:
        interrupted = True
        print(f"interrupted; {len(rows)} of {len(wanted)} scenario(s) completed", file=sys.stderr)
    finally:
        # A last sweep for this run_id: cheap when run_scenario's/run_chained's own terminate()
        # already cleaned up, and the only thing standing between an unforeseen exception and an
        # orphaned JVM.
        kill_pids(marker_pids(ps_listing(), MARKER + run_id))
    summary = REPORTS / run_id / "summary.md"
    summary.write_text(summary_md(run_id, rows, interrupted=interrupted, chained=a.chained,
                                   total_seconds=total_seconds))
    print(f"summary: {summary.relative_to(ROOT)}")
    if interrupted:
        return 130
    return 0 if all(r["ok"] for r in rows) else 1


# ------------------------------------------------------------------ self-test

class RunnerTests(unittest.TestCase):
    LISTING = (
        "  123 /opt/java/bin/java -Xmx3G -Dhoardkeeper.gametest.run=abc -Dfabric.client.gametest=true "
        "net.fabricmc.devlaunchinjector.Main\n"
        "  456 python3 scripts/gametest.py --sweep hoardkeeper.gametest.run=\n"
        "  789 /bin/zsh -c ./gradlew runClientGameTest -PrunId=abc\n"
    )

    def test_marker_pids_finds_the_jvm_and_not_the_runner(self):
        self.assertEqual([123], marker_pids(self.LISTING, MARKER + "abc"))
        self.assertEqual([123], marker_pids(self.LISTING, API_MARKER))

    def test_marker_pids_ignores_other_run_ids(self):
        self.assertEqual([], marker_pids(self.LISTING, MARKER + "zzz"))

    def test_golden_result_has_the_contract_keys(self):
        golden = json.loads(GOLDEN.read_text())
        self.assertEqual(RESULT_KEYS, set(golden))
        self.assertEqual({"name", "pass", "detail"}, set(golden["checks"][0]))

    def test_summary_lists_every_row_with_verdict(self):
        rows = [{"name": "boot", "ok": True, "seconds": 38, "peak": 1810, "focus": "no", "numbers": {}},
                {"name": "scan", "ok": False, "seconds": 71, "peak": 2310, "focus": "yes",
                 "numbers": {"scanned": 11}},
                {"name": "harness-fail", "ok": False, "seconds": 33, "peak": 1850, "focus": "n/a",
                 "numbers": {}}]
        md = summary_md("r1", rows)
        self.assertIn("1 passed, 2 failed", md)
        self.assertIn("| boot | PASS | 38 | 1810 MB | no |  |", md)
        self.assertIn("| scan | FAIL | 71 | 2310 MB | yes | scanned=11 — see scan/result.json |", md)
        self.assertIn("| harness-fail | FAIL | 33 | 1850 MB | n/a |  — see harness-fail/result.json |", md)

    def test_frontmost_survives_a_hanging_osascript(self):
        timeout_sentinel = subprocess.CompletedProcess(["lsappinfo"], -1, stdout="", stderr="")
        with patch.object(sys.modules[__name__], "sh", return_value=timeout_sentinel):
            self.assertIsNone(frontmost())

    def test_frontmost_parses_lsappinfo(self):
        front = subprocess.CompletedProcess(["lsappinfo", "front"], 0, stdout="ASN:0x0-0x1:\n", stderr="")
        info = subprocess.CompletedProcess(["lsappinfo", "info"], 0,
                                            stdout='"LSDisplayName"="Code"\n', stderr="")
        with patch.object(sys.modules[__name__], "sh", side_effect=[front, info]):
            self.assertEqual("Code", frontmost())

    def test_verdict_requires_exit_zero_pass_and_no_timeout(self):
        self.assertTrue(verdict(0, {"pass": True}, False))
        self.assertFalse(verdict(1, {"pass": True}, False))
        self.assertFalse(verdict(0, {"pass": False}, False))
        self.assertFalse(verdict(0, {"pass": True}, True))

    def test_shielded_restores_handlers(self):
        before_int = signal.getsignal(signal.SIGINT)
        before_term = signal.getsignal(signal.SIGTERM)
        result = shielded(lambda: 42)
        self.assertEqual(42, result)
        self.assertEqual(before_int, signal.getsignal(signal.SIGINT))
        self.assertEqual(before_term, signal.getsignal(signal.SIGTERM))


class CollectTests(unittest.TestCase):
    """collect() reads build/run/clientGameTest/gametest/result.json; RUN_DIR is monkeypatched to
    a scratch directory here (restored in tearDown) so these never touch a real Minecraft run."""

    def setUp(self):
        global RUN_DIR
        self._orig_run_dir = RUN_DIR
        self._tmp = tempfile.TemporaryDirectory()
        RUN_DIR = Path(self._tmp.name)
        (RUN_DIR / "gametest").mkdir(parents=True)
        self.out_dir = RUN_DIR / "out"
        self.out_dir.mkdir()
        self.src = RUN_DIR / "gametest" / "result.json"

    def tearDown(self):
        global RUN_DIR
        RUN_DIR = self._orig_run_dir
        self._tmp.cleanup()

    def test_collect_reads_a_passing_result_and_merges_extra_fields(self):
        shutil.copy(GOLDEN, self.src)
        result = collect("boot", self.out_dir, {"peakRssMb": 111})
        self.assertTrue(result["pass"])
        self.assertEqual(111, result["peakRssMb"])

    def test_collect_flags_a_result_missing_a_contract_key(self):
        golden = json.loads(GOLDEN.read_text())
        del golden["screenshots"]
        self.src.write_text(json.dumps(golden))
        result = collect("boot", self.out_dir, {})
        self.assertFalse(result["pass"])
        self.assertEqual("ContractMismatch", result["error"]["type"])

    def test_collect_defaults_cleanly_when_no_result_file_exists(self):
        result = collect("boot", self.out_dir, {})
        self.assertEqual("NoResult", result["error"]["type"])

    def test_collect_flags_a_truncated_result_file_instead_of_crashing(self):
        self.src.write_text('{"scenario": "boot", "pass": tru')
        result = collect("boot", self.out_dir, {})
        self.assertEqual("CorruptResult", result["error"]["type"])


class CollectChainedRowTests(unittest.TestCase):
    """collect_chained_row() reads build/run/clientGameTest/gametest/result-<name>.json --
    RUN_DIR is monkeypatched exactly the way CollectTests does it for the single-scenario path,
    so these never touch a real Minecraft run either."""

    def setUp(self):
        global RUN_DIR
        self._orig_run_dir = RUN_DIR
        self._tmp = tempfile.TemporaryDirectory()
        RUN_DIR = Path(self._tmp.name)
        (RUN_DIR / "gametest").mkdir(parents=True)
        self.out_dir = RUN_DIR / "out"
        self.out_dir.mkdir()
        self.src = RUN_DIR / "gametest" / "result-passive.json"

    def tearDown(self):
        global RUN_DIR
        RUN_DIR = self._orig_run_dir
        self._tmp.cleanup()

    def test_reads_a_passing_result_and_merges_extra_fields(self):
        shutil.copy(GOLDEN, self.src)
        result = collect_chained_row("passive", self.out_dir, {"peakRssMb": 111})
        self.assertTrue(result["pass"])
        self.assertEqual(111, result["peakRssMb"])

    def test_flags_a_result_missing_a_contract_key(self):
        golden = json.loads(GOLDEN.read_text())
        del golden["screenshots"]
        self.src.write_text(json.dumps(golden))
        result = collect_chained_row("passive", self.out_dir, {})
        self.assertFalse(result["pass"])
        self.assertEqual("ContractMismatch", result["error"]["type"])

    def test_defaults_cleanly_when_the_chain_never_reached_this_scenario(self):
        result = collect_chained_row("passive", self.out_dir, {})
        self.assertEqual("NoResult", result["error"]["type"])
        # Unlike collect()'s single-scenario NoResult, a scenario the chain never reached was
        # still part of a chained launch -- _fail_result must say so rather than defaulting to
        # the single-scenario shape's chained=False.
        self.assertTrue(result["chained"])

    def test_flags_a_truncated_result_file_instead_of_crashing(self):
        self.src.write_text('{"scenario": "passive", "pass": tru')
        result = collect_chained_row("passive", self.out_dir, {})
        self.assertEqual("CorruptResult", result["error"]["type"])
        self.assertTrue(result["chained"])

    def test_a_different_scenarios_result_file_is_not_mistaken_for_this_one(self):
        # result-scan.json existing must not satisfy a read for "passive" -- each scenario's row
        # is keyed by its own name, not by whatever happens to be newest in the directory.
        shutil.copy(GOLDEN, RUN_DIR / "gametest" / "result-scan.json")
        result = collect_chained_row("passive", self.out_dir, {})
        self.assertEqual("NoResult", result["error"]["type"])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
