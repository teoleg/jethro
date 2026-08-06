#!/usr/bin/env python3
"""Tests for the scorer's BAD-verdict revert (ADR-0143).

Run: python3 scripts/test-score-change.py

These are NOT covered by `./gradlew -Pci test` — the scorer is Python, outside the Gradle build. They
matter because this code path is the loop's ONLY self-correction arm: when it silently fails, every
change graded ❌ BAD stays live in the running book. It failed silently 9 times in a row before
ADR-0143, so it gets a reproduction test.

No third-party dependencies (stdlib only) — the loop box has no pytest.
"""

import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

_spec = importlib.util.spec_from_file_location("score_change", os.path.join(HERE, "score-change.py"))
sc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sc)

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print(f"  ok   {name}")
    else:
        print(f"  FAIL {name} {detail}")
        FAILURES.append(name)


def run(repo, *args, **kw):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, **kw)


def new_repo(tmp):
    repo = os.path.join(tmp, "repo")
    os.makedirs(os.path.join(repo, "reports"))
    os.makedirs(os.path.join(repo, "docs", "adr"))
    os.makedirs(os.path.join(repo, "app"))
    run(repo, "init", "--quiet", "--initial-branch=main")
    run(repo, "config", "user.email", "loop@jethro.test")
    run(repo, "config", "user.name", "Loop Test")
    return repo


def write(repo, rel, text):
    path = os.path.join(repo, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def commit(repo, msg):
    run(repo, "add", "-A")
    run(repo, "commit", "--quiet", "--no-verify", "-m", msg)
    return run(repo, "rev-parse", "HEAD").stdout.strip()


def read(repo, rel):
    with open(os.path.join(repo, rel), encoding="utf-8") as f:
        return f.read()


# --------------------------------------------------------------------------- pure classification

def test_classification():
    print("classification — the loop's record is kept, code is revertable")
    for p in ("reports/improvement-ledger.md", "reports/last-analysis.md", "reports/must-fix.md",
              "reports/attribution/x.json", "reports/run-status.json", "docs/loop-findings.md",
              "docs/loop-playbook.md", "docs/adr/0143-x.md", "docs/adr/README.md"):
        check(f"kept: {p}", sc.is_record_path(p))
    for p in ("app/src/main/java/io/jethro/app/fusion/PositionBuffer.java",
              "app/src/main/resources/application.properties",
              "trading-core/algo-engine/src/main/java/X.java", "modules/order/build.gradle",
              "scripts/score-change.py", "docs/architecture/overview.md", "CLAUDE.md"):
        check(f"revertable: {p}", not sc.is_record_path(p))
    # ops/ is loop machinery: NOT the app binary (so ADR-0142 won't redeploy for it), but it IS code
    # and a bad change to it must be revertable. The two predicates answer different questions.
    check("revertable: ops/improve-loop.sh", not sc.is_record_path("ops/improve-loop.sh"))
    check("revertable: ops/improve-prompt.md", not sc.is_record_path("ops/improve-prompt.md"))
    # A path that merely starts with a kept name must not be swept up.
    check("not kept: reports-archive/x.md", not sc.is_record_path("reports-archive/x.md"))
    check("not kept: docs/adr-notes.md", not sc.is_record_path("docs/adr-notes.md"))

    mixed = ["reports/must-fix.md", "app/src/main/java/A.java", "docs/loop-findings.md",
             "docs/adr/0143-x.md", "ops/improve-loop.sh", ""]
    check("revertable_paths filters and drops blanks",
          sc.revertable_paths(mixed) == ["app/src/main/java/A.java", "ops/improve-loop.sh"],
          sc.revertable_paths(mixed))


# --------------------------------------------------------------------------- the real failure mode

def test_reproduces_the_nine_failures(tmp):
    print("reproduction — a BAD commit scored 6 cycles later (the ADR-0116 window)")
    repo = new_repo(tmp)
    sc.REPO = repo

    write(repo, "app/Strategy.java", "int width() { return 10; }\n")
    write(repo, "reports/must-fix.md", "# register\ncycle 0\n")
    write(repo, "reports/last-analysis.md", "analysis at cycle 0\n")
    write(repo, "docs/loop-findings.md", "# findings\n- cycle 0\n")
    commit(repo, "baseline")

    # The change that will later be graded BAD — code AND, per the design-first rule, its ADR, plus
    # the mandated per-cycle record writes. This is the exact shape of all 9 real failures.
    write(repo, "app/Strategy.java", "int width() { return 99; }\n")
    write(repo, "docs/adr/0999-bad-idea.md", "**Status:** Implemented\n")
    write(repo, "reports/must-fix.md", "# register\ncycle 1\n")
    write(repo, "reports/last-analysis.md", "analysis at cycle 1\n")
    write(repo, "docs/loop-findings.md", "# findings\n- cycle 0\n- cycle 1\n")
    bad = commit(repo, "feat: the bad idea (ADR-0999)")

    # MIN_CYCLES of mandated record rewrites on top, exactly as ops/improve-prompt.md requires.
    findings = "# findings\n- cycle 0\n- cycle 1\n"
    for c in range(2, 2 + sc.MIN_CYCLES):
        findings += f"- cycle {c}\n"
        write(repo, "reports/must-fix.md", f"# register\ncycle {c}\n")
        write(repo, "reports/last-analysis.md", f"analysis at cycle {c}\n")
        write(repo, "docs/loop-findings.md", findings)
        commit(repo, f"docs(loop): cycle {c}")

    # (a) The OLD behaviour — a whole-commit revert — must conflict, and only on the record.
    plain = run(repo, "revert", "--no-commit", "--no-edit", bad)
    conflicted = sorted(run(repo, "diff", "--name-only", "--diff-filter=U").stdout.split())
    run(repo, "revert", "--abort")
    run(repo, "reset", "--quiet", "--hard")
    check("whole-commit revert conflicts (the 9x failure)", plain.returncode != 0)
    check("...and conflicts ONLY on the loop's record",
          conflicted == ["docs/loop-findings.md", "reports/last-analysis.md", "reports/must-fix.md"],
          conflicted)

    # (b) The fix — scoped to code — applies cleanly.
    applied, detail = sc.revert_code_paths(bad, bad[:9])
    check("scoped revert applies", applied is True, detail)
    check("the bad CODE is out of the running tree",
          read(repo, "app/Strategy.java") == "int width() { return 10; }\n")
    check("the ADR is KEPT (rejected decisions stay on the record)",
          os.path.exists(os.path.join(repo, "docs", "adr", "0999-bad-idea.md")))
    check("the findings memory is intact at its LATEST content",
          read(repo, "docs/loop-findings.md") == findings)
    check("must-fix is intact at its LATEST content",
          read(repo, "reports/must-fix.md") == f"# register\ncycle {1 + sc.MIN_CYCLES}\n")
    check("a revert commit was made",
          run(repo, "log", "-1", "--format=%s").stdout.startswith('Revert "feat: the bad idea'))
    check("the tree is clean afterwards", not run(repo, "status", "--porcelain").stdout.strip())


# --------------------------------------------------------------------------- edges

def test_record_only_commit(tmp):
    print("a record-only commit has nothing in the running code to pull")
    repo = new_repo(tmp)
    sc.REPO = repo
    write(repo, "app/Strategy.java", "int width() { return 10; }\n")
    write(repo, "reports/must-fix.md", "cycle 0\n")
    commit(repo, "baseline")
    write(repo, "reports/must-fix.md", "cycle 1\n")
    sha = commit(repo, "docs(loop): no change")
    applied, detail = sc.revert_code_paths(sha, sha[:9])
    check("reports True (nothing was live)", applied is True, detail)
    check("says so plainly", "record-only" in detail, detail)
    check("the record is untouched", read(repo, "reports/must-fix.md") == "cycle 1\n")
    check("no commit was invented", run(repo, "log", "-1", "--format=%s").stdout.strip()
          == "docs(loop): no change")


def test_genuine_code_conflict_leaves_tree_clean(tmp):
    print("a GENUINE code conflict fails honestly and leaves nothing half-applied")
    repo = new_repo(tmp)
    sc.REPO = repo
    write(repo, "app/Strategy.java", "a\nb\nc\n")
    commit(repo, "baseline")
    write(repo, "app/Strategy.java", "a\nBAD\nc\n")
    sha = commit(repo, "feat: bad")
    write(repo, "app/Strategy.java", "totally\ndifferent\nfile\n")  # drift the same lines away
    commit(repo, "feat: later work")
    applied, detail = sc.revert_code_paths(sha, sha[:9])
    check("reports False", applied is False, detail)
    check("blames a CODE path, not the record", "CODE" in detail, detail)
    check("the tree is clean (nothing half-applied)",
          not run(repo, "status", "--porcelain").stdout.strip())
    check("the working file is untouched",
          read(repo, "app/Strategy.java") == "totally\ndifferent\nfile\n")


def test_refuses_on_dirty_tree(tmp):
    print("a dirty tree is refused rather than discarded")
    repo = new_repo(tmp)
    sc.REPO = repo
    write(repo, "app/Strategy.java", "a\n")
    commit(repo, "baseline")
    write(repo, "app/Strategy.java", "b\n")
    sha = commit(repo, "feat: bad")
    write(repo, "app/Strategy.java", "uncommitted work\n")
    applied, detail = sc.revert_code_paths(sha, sha[:9])
    check("reports False", applied is False, detail)
    check("the uncommitted work survives",
          read(repo, "app/Strategy.java") == "uncommitted work\n")


def main():
    real_repo = sc.REPO
    try:
        test_classification()
        for fn in (test_reproduces_the_nine_failures, test_record_only_commit,
                   test_genuine_code_conflict_leaves_tree_clean, test_refuses_on_dirty_tree):
            with tempfile.TemporaryDirectory() as tmp:
                fn(tmp)
    finally:
        sc.REPO = real_repo
    print()
    if FAILURES:
        print(f"FAILED ({len(FAILURES)}): " + ", ".join(FAILURES))
        return 1
    print("all scorer revert tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
