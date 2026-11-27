#!/usr/bin/env python3
"""Verify the frozen GASS acceptance manifest against independent review evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


HEX_40 = re.compile(r"[0-9a-f]{40}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
CHECK_ROW = re.compile(r"^\|\s*([A-Z][A-Z0-9-]+)\s*\|.*?\b(PASSED|FAILED|BLOCKED|NOT_APPLICABLE|NOT_RUN)\s*\|$", re.M)
INFRASTRUCTURE = {"scripts/acceptance/stage_gate.py", "scripts/acceptance/test_stage_gate.py"}
COMMAND_PATTERNS = {
    "INPUT": r"git .*rev-parse",
    "STATIC": r"git .*diff",
    "EVIDENCE-REVIEW": r"SHA-256",
    "ENV": r"java -version.*mvn -version.*node --version.*pnpm --version.*python --version",
    "MAVEN": r"mvn(?:\.cmd|w\.cmd|w)?\b.*\btest$",
    "FORMAT": r"mvn(?:\.cmd|w\.cmd|w)?\b.*\bspotless:check$",
    "FRONT-INSTALL": r"\binstall\b.*--frozen-lockfile\b",
    "FRONT-TEST": r"\btest\b.*--reporter=junit\b.*--outputFile\.junit=",
    "FRONT-BUILD": r"\bbuild$",
    "HTTP-SMOKE": r"scripts[/\\]+smoke_mock\.py --base-url http://127\.0\.0\.1:\d+$",
    "GRAPH": r"java(?:\.exe)? @.*graph-fixture-arguments\.txt$",
    "EVIDENCE": r"java(?:\.exe)? @.*evidence-fixture-arguments\.txt$",
    "OBJECTIVE-ALERT": r"-Dtest=ReviewerQueryObjectiveTest#[^ ]+ .*test$",
    "CONCURRENCY": r"python(?:\.exe)? concurrency_fixture\.py$",
    "BROWSER": r"node(?:\.exe)? browser-fixture-verified\.mjs$",
    "HTTP-COMPARE": r"python(?:\.exe)? http_fixture\.py compare$",
    "DB-REOPEN": r"python(?:\.exe)? http_fixture\.py reopen$",
    "STAGE2-ONTOLOGY": r"-Dtest=ReviewerStage2OntologyTest .*test$",
    "STAGE2-COMMIT": r"-Dtest=ReviewerStage2CommitTest .*test$",
    "STAGE2-VISIBILITY": r"-Dtest=ReviewerStage2VisibilityTest .*test$",
    "STAGE2-EVIDENCE": r"-Dtest=ReviewerStage2EvidenceTest .*test$",
    "STAGE3-SCHEDULER": r"-Dtest=ReviewerStage3SchedulerTest .*test$",
    "STAGE3-RECOVERY": r"-Dtest=ReviewerStage3RecoveryTest .*test$",
    "STAGE3-EVALUATION": r"-Dtest=ReviewerStage3EvaluationTest .*test$",
    "STAGE3-TERMINATION-E2E": r"-Dtest=ReviewerStage3TerminationFlowTest .*test$",
    "STAGE4-PROPAGATION": r"-Dtest=ReviewerStage4PropagationTest .*test$",
    "STAGE4-RULE-SAFETY": r"-Dtest=ReviewerStage4RuleSafetyTest .*test$",
    "STAGE4-RECOVERY": r"-Dtest=ReviewerStage4RecoveryTest .*test$",
    "STAGE4-MANAGEMENT": r"-Dtest=ReviewerStage4ManagementTest .*test$",
    "GATE-TEST": r"python(?:\.exe)? -m unittest discover -s scripts[/\\]acceptance -p test_stage_gate\.py$",
}
REQUIRED_SUITES = {
    "MAVEN": {
        f"com.trs.studio.{name}Test"
        for name in (
            "ObjectiveGraphQuery", "HierarchyModelContract", "HierarchicalFrameworkIntegration",
            "HierarchyGatewayIntegration", "DeclarativeActionEngine", "ObjectActionRuntimeIntegration",
            "SimulationIntegration", "ContinuationIntegration", "RunTerminationIntegration",
            "ActionLibraryIntegration", "WorldCatalogMigration",
        )
    },
    "FRONT-TEST": {f"src/{name}.test.ts" for name in ("RunStudio", "HierarchyTrace", "EventDetails", "ObjectActions")},
    "OBJECTIVE-ALERT": {"com.trs.studio.ReviewerQueryObjectiveTest"},
    "STAGE2-ONTOLOGY": {"com.trs.studio.ReviewerStage2OntologyTest"},
    "STAGE2-COMMIT": {"com.trs.studio.ReviewerStage2CommitTest"},
    "STAGE2-VISIBILITY": {"com.trs.studio.ReviewerStage2VisibilityTest"},
    "STAGE2-EVIDENCE": {"com.trs.studio.ReviewerStage2EvidenceTest"},
    "STAGE3-SCHEDULER": {"com.trs.studio.ReviewerStage3SchedulerTest"},
    "STAGE3-RECOVERY": {"com.trs.studio.ReviewerStage3RecoveryTest"},
    "STAGE3-EVALUATION": {"com.trs.studio.ReviewerStage3EvaluationTest"},
    "STAGE3-TERMINATION-E2E": {"com.trs.studio.ReviewerStage3TerminationFlowTest"},
    "STAGE4-PROPAGATION": {"com.trs.studio.ReviewerStage4PropagationTest"},
    "STAGE4-RULE-SAFETY": {"com.trs.studio.ReviewerStage4RuleSafetyTest"},
    "STAGE4-RECOVERY": {"com.trs.studio.ReviewerStage4RecoveryTest"},
    "STAGE4-MANAGEMENT": {"com.trs.studio.ReviewerStage4ManagementTest"},
}


class GateError(Exception):
    """A rejected gate with an explicit classification."""

    def __init__(self, message, exit_code=1):
        super().__init__(message)
        self.exit_code = exit_code


def require(condition, message, exit_code=1):
    if not condition:
        raise GateError(message, exit_code)


def object_no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON field {key}")
        result[key] = value
    return result


def load_json(path):
    require(path.is_file(), f"missing JSON file {path}", 2)
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=object_no_duplicates)


def sha256(path):
    require(path.is_file(), f"missing evidence file {path}", 2)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, f"timezone required: {value}")
    return parsed


def index_by_id(records, context):
    require(isinstance(records, list), f"{context} must be a list")
    result = {}
    for record in records:
        ident = record["id"]
        require(isinstance(ident, str) and ident and ident not in result,
                f"missing or duplicate {context} ID: {ident}")
        result[ident] = record
    return result


def git(repo, *args, allowed=(0,)):
    argv = ["git", "-c", f"safe.directory={repo.as_posix()}", *args]
    proc = subprocess.run(argv, cwd=repo, capture_output=True, encoding="utf-8", check=False)
    require(proc.returncode in allowed, f"Git {args[0]} returned {proc.returncode}", 2)
    return proc


def git_ancestor(repo, before, after):
    return git(repo, "merge-base", "--is-ancestor", before, after, allowed=(0, 1)).returncode == 0


def verify_checkout(repo, stage, baseline, candidate):
    head = git(repo, "rev-parse", "HEAD").stdout.strip()
    require(git_ancestor(repo, baseline, candidate), "baseline is not an ancestor of the candidate")
    require(git_ancestor(repo, candidate, head), "candidate does not match the checkout history")
    stage_prefix = f"docs/development/acceptance/stage-{stage:02}/"

    def acceptance_doc(path):
        if path in {"AGENTS.md", "docs/development/PROGRESS.md"}:
            return True
        if not path.startswith(stage_prefix):
            return False
        name = path.removeprefix(stage_prefix)
        return name in {
            "check-manifest.json", "check-manifest.draft.json", "check-manifest.sha256",
            "baseline.json", "candidate.json", "development.md",
        } or bool(
            re.fullmatch(r"attempt-\d+/(?:review\.md|result\.json|evidence-index\.json|gate-report\.json)", name)
        )

    tracked = git(repo, "diff", "--name-only", "-z", candidate, "--").stdout.split("\0")
    staged = git(repo, "diff", "--cached", "--name-only", "-z", candidate, "--").stdout.split("\0")
    untracked = git(repo, "ls-files", "--others", "--exclude-standard", "-z").stdout.split("\0")
    # Only explicitly named gate implementation files belong to the independent infrastructure task.
    def allowed(path):
        return acceptance_doc(path) or (stage == 1 and path in INFRASTRUCTURE)

    changes = {path for path in (*tracked, *staged) if path and not allowed(path)}
    changes.update(path for path in untracked if path and not allowed(path) and not path.startswith(".idea/"))
    require(not changes, f"unreviewed checkout files: {', '.join(sorted(changes))}")
    return head


def resolve_candidate(stage_dir, manifest, digest):
    if manifest["candidateCommit"] is not None:
        require(manifest.get("stage", 1) == 1, "prospective manifests require separate candidate registration")
        return manifest["candidateCommit"]
    require(not manifest["retrospective"], "retrospective manifest must pin its candidate")
    registration = load_json(stage_dir / "candidate.json")
    require(registration["schemaVersion"] == 1 and registration["stage"] == manifest["stage"]
            and registration["baselineCommit"] == manifest["baselineCommit"]
            and registration["checkManifestSha256"] == digest
            and HEX_40.fullmatch(registration["candidateCommit"])
            and timestamp(registration["registeredAt"]) >= timestamp(manifest["frozenAt"]),
            "candidate registration does not match the frozen manifest")
    return registration["candidateCommit"]


def verify_manifest(repo, stage_dir, stage):
    path = stage_dir/"check-manifest.json"
    manifest = load_json(path)
    digest = sha256(path)
    reference = stage_dir/"check-manifest.sha256"
    require(reference.is_file(), "missing frozen manifest digest", 2)
    parts = reference.read_text(encoding="utf-8-sig").split()
    require(parts == [digest, "check-manifest.json"], "frozen manifest digest mismatch")
    require(type(manifest["stage"]) is int and manifest["stage"] == stage
            and manifest["schemaVersion"] == manifest["manifestVersion"] == 1
            and manifest["status"] == "FROZEN", "manifest must be frozen for the requested stage")
    baseline, candidate = manifest["baselineCommit"], resolve_candidate(stage_dir, manifest, digest)
    require(HEX_40.fullmatch(baseline) and HEX_40.fullmatch(candidate) and baseline != candidate,
            "full distinct baseline and candidate SHAs are required")
    head = verify_checkout(repo, stage, baseline, candidate)

    for plan in manifest["plans"].values():
        path = (repo/plan["path"]).resolve()
        require(path.is_relative_to(repo) and sha256(path) == plan["sha256"], "guide hash mismatch")
    approval = manifest["approval"]
    require(approval["status"] == "APPROVED" and approval["approverRole"] == "项目负责人"
            and approval["source"].strip() and approval["statement"].strip(), "owner approval missing")
    approved, frozen = timestamp(approval["approvedAt"]), timestamp(manifest["frozenAt"])
    require(approved <= frozen <= datetime.now(timezone.utc), "invalid approval or freeze chronology")
    draft_path = stage_dir/"check-manifest.draft.json"
    require(sha256(draft_path) == approval["approvedDraftSha256"], "approved draft hash mismatch")
    draft = load_json(draft_path)
    for key in ("stage", "candidateCommit", "baselineCommit", "plans", "scope", "checks", "verification", "previousStages"):
        require(draft[key] == manifest[key], f"frozen {key} differs from the approved draft")
    if stage >= 2:
        for key in ("baselineRecord", "prerequisiteRecords", "execution", "retrospective"):
            require(draft[key] == manifest[key], f"frozen {key} differs from the approved draft")
        baseline_record = manifest["baselineRecord"]
        baseline_path = (repo / baseline_record["path"]).resolve()
        require(baseline_path.is_relative_to(stage_dir) and sha256(baseline_path) == baseline_record["sha256"]
                and load_json(baseline_path)["baselineCommit"] == baseline, "baseline record hash mismatch")
    draft_conditions = index_by_id(draft["conditions"], "draft condition")
    conditions = index_by_id(manifest["conditions"], "condition")
    require(conditions.keys() == draft_conditions.keys()
            and all(entry["enabled"] is draft_conditions[key]["proposedEnabled"] for key, entry in conditions.items()),
            "frozen condition applicability differs from the approved draft")
    commit_time = timestamp(git(repo, "show", "-s", "--format=%cI", candidate).stdout.strip())
    if approved > commit_time:
        require(manifest["retrospective"] is True and "事后补录" in manifest["notice"],
                "post-candidate approval needs an explicit retrospective record")
    if manifest["candidateCommit"] is None:
        registered = timestamp(load_json(stage_dir / "candidate.json")["registeredAt"])
        require(frozen <= commit_time <= registered <= datetime.now(timezone.utc),
                "candidate registration chronology is invalid")
    check_ids = index_by_id(manifest["checks"], "frozen check")
    verification = index_by_id(manifest["verification"], "verification")
    require(all(set(check["verificationIds"]) <= verification.keys() and check["verificationIds"]
                for check in check_ids.values()), "unknown or missing verification for a frozen check")
    for ident, entry in verification.items():
        require(type(entry["required"]) is bool and (ident in COMMAND_PATTERNS or ident in conditions),
                f"unsupported verification {ident}")
    gate = conditions["STAGE-GATE"]
    require(gate["enabled"] is True and gate["applicability"] == "REQUIRED"
            and verification["STAGE-GATE"]["required"] is True
            and verification["STAGE-GATE"]["target"] == "gate",
            "automatic Stage Gate must remain required")
    return {**manifest, "candidateCommit": candidate}, digest, head


def verify_index(index, baseline, candidate):
    require(index["schemaVersion"] == 1 and index["baselineCommit"] == baseline
            and index["candidateCommit"] == candidate, "evidence index candidate/baseline mismatch")
    roots = [Path(path).resolve() for path in index["evidenceRoots"]]
    require(roots, "no evidence roots", 2)
    assets = index_by_id(index["evidence"], "evidence")
    paths = set()
    for ident, entry in assets.items():
        path = Path(entry["path"])
        resolved = path.resolve()
        require(path.is_absolute() and any(resolved.is_relative_to(root) for root in roots)
                and resolved not in paths, f"invalid or duplicate evidence path for {ident}")
        paths.add(resolved)
        source = set(entry["sourceCommits"])
        require(entry["candidateCommit"] == candidate and source and source <= {baseline, candidate},
                f"candidate binding mismatch for evidence {ident}")
        require(isinstance(entry["checkIds"], list) and isinstance(entry["kind"], str),
                f"invalid schema for evidence {ident}")
        require(HEX_64.fullmatch(entry["sha256"]) and sha256(resolved) == entry["sha256"],
                f"evidence hash mismatch: {ident}")
    return assets


def verify_review(manifest, result, review, assets, digest, attempt):
    require(result["schemaVersion"] == 1 and result["stage"] == manifest["stage"]
            and result["attempt"] == attempt, "review stage or attempt differs")
    expected = {
        "baselineCommit": manifest["baselineCommit"],
        "candidateCommit": manifest["candidateCommit"],
        "checkManifestSha256": digest,
        "planSha256": manifest["plans"]["development"]["sha256"],
        "acceptanceSha256": manifest["plans"]["acceptance"]["sha256"],
    }
    for key, value in expected.items():
        require(result[key] == value, f"review {key} mismatch")

    reviewer = result["reviewer"]
    require(reviewer["role"] == "REVIEWER" and reviewer["sessionId"].strip()
            and timestamp(reviewer["attestedAt"]) >= timestamp(manifest["frozenAt"]),
            "independent reviewer attestation is missing or stale", 2)

    frozen_checks = index_by_id(manifest["checks"], "frozen check")
    checks = index_by_id(result["checks"], "review check")
    require(checks.keys() == frozen_checks.keys(), "review check IDs differ from the frozen manifest")
    for ident, frozen in frozen_checks.items():
        check = checks[ident]
        require(type(frozen["required"]) is bool and check["required"] is frozen["required"],
                f"required flag differs for {ident}")
        if frozen["required"]:
            status = check["status"]
            require(status == "PASSED", f"required review check {ident}: {status}",
                    2 if status in {"NOT_RUN", "BLOCKED"} else 1)
        refs = check["evidenceRefs"]
        require(refs and all(ref in assets and ident in assets[ref]["checkIds"] for ref in refs),
                f"missing candidate-bound evidence for {ident}", 2)

    rows = CHECK_ROW.findall(review)
    require(len(rows) == len(checks) and dict(rows) == {ident: check["status"] for ident, check in checks.items()},
            "review Markdown check table is inconsistent")

    frozen_conditions = index_by_id(manifest["conditions"], "frozen condition")
    conditions = index_by_id(result["conditions"], "review condition")
    require(conditions.keys() == frozen_conditions.keys(), "review conditions differ from the manifest")
    for ident, cond in frozen_conditions.items():
        require(cond["approvalStatus"] == "APPROVED" and cond["reason"].strip(),
                f"missing approval for condition {ident}")
        expected = "EVALUATED_BY_GATE" if cond["enabled"] is True else "NOT_APPLICABLE"
        applicability = "REQUIRED" if cond["enabled"] is True else "NOT_APPLICABLE"
        require(cond["applicability"] == applicability and conditions[ident]["status"] == expected,
                f"condition {ident} does not match approved applicability")

    for ident, issue in index_by_id(result["issues"], "issue").items():
        require(issue["checkId"] in checks and issue["severity"] in {"BLOCKER", "MAJOR", "MINOR", "SUGGESTION"}
                and issue["status"] in {"OPEN", "CLOSED", "DEFERRED"}, f"invalid issue {ident}")
        require(issue["severity"] not in {"BLOCKER", "MAJOR"} or issue["status"] == "CLOSED",
                f"unresolved {issue['severity']} issue {ident}")
    return len(checks)


def match_commands(verification, commands):
    definitions = index_by_id(verification, "verification")
    required = set()
    for ident, rule in definitions.items():
        target = rule["target"]
        if rule["required"] and target != "gate":
            required.update((ident, entry) for entry in (("baseline", "candidate") if target == "both" else (target,)))
    matches = {}
    for command in index_by_id(commands, "command").values():
        key = (command["verificationId"], command["target"])
        require(key not in matches, f"duplicate verification and target: {key}")
        require(key in required, f"unexpected command binding {key}")
        matches[key] = command
    require(matches.keys() == required, f"missing required command runs: {sorted(required - matches.keys())}", 2)
    return matches


def verify_capture(record, capture):
    ident = record["id"]
    require(type(capture["exitCode"]) is int and capture["exitCode"] == 0
            and type(record["exitCode"]) is int and record["exitCode"] == 0
            and record["status"] == "PASSED", f"{ident} did not capture a successful exit code")
    start, end = timestamp(capture["started"]), timestamp(capture["ended"])
    require(start <= end and start == timestamp(record["startedAt"]) and end == timestamp(record["endedAt"]),
            f"{ident} capture timestamps differ")
    if "steps" in capture:
        require(all(type(step["exitCode"]) is int and not step["exitCode"] and step["argv"]
                    for step in capture["steps"]), f"{ident} contains a failed subcommand")
    if "cwd" in capture:
        require(Path(capture["cwd"]).resolve() == Path(record["cwd"]).resolve(), f"{ident} capture cwd mismatch")


def junit_totals(paths):
    require(paths, "missing JUnit XML", 2)
    counts = dict.fromkeys(("total", "failed", "errors", "skipped", "suites"), 0)
    names = set()
    for path in paths:
        root = ET.parse(path).getroot()
        require(root.tag in {"testsuite", "testsuites"}, f"invalid JUnit XML {path}")
        suites = [root] if root.tag == "testsuite" else root.findall("./testsuite")
        for suite in suites:
            name = suite.attrib["name"]
            require(name not in names, f"duplicate JUnit suite {name}")
            names.add(name)
            counts["suites"] += 1
            cases = suite.findall("./testcase")
            require(int(suite.attrib["tests"]) == len(cases), f"JUnit testcase count mismatch in {name}")
            counts["total"] += len(cases)
            for key, attr, tag in (
                ("failed", "failures", "failure"), ("errors", "errors", "error"), ("skipped", "skipped", "skipped")
            ):
                count = int(suite.get(attr, 0))
                require(count == len(suite.findall(f"./testcase/{tag}")), f"JUnit {attr} mismatch in {name}")
                counts[key] += count
            require(not int(suite.get("flakes", 0)), f"flaky retries in {name}")
    require(counts["total"] > 0, "no tests executed in the JUnit report", 2)
    require(not any(counts[key] for key in ("failed", "errors", "skipped")),
            "JUnit reports failed, errored, or skipped tests")
    return counts, names


def read_log(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig", errors="replace")


def verify_commands(manifest, result, assets):
    matches = match_commands(manifest["verification"], result["commands"])
    used = set()
    summaries = {}
    for (verification, target), command in sorted(matches.items()):
        expected_source = {
            "baseline": [manifest["baselineCommit"]],
            "candidate": [manifest["candidateCommit"]],
            "comparison": ([manifest["candidateCommit"]] if verification == "DB-REOPEN"
                           else [manifest["baselineCommit"], manifest["candidateCommit"]]),
            "repo": [manifest["candidateCommit"]],
        }[target]
        command_hash = command["sourceCommit"]
        require(command_hash == (expected_source[0] if len(expected_source) == 1 else None),
                f"command source commit does not match {verification}/{target}")
        require(re.search(COMMAND_PATTERNS[verification], command["command"], re.I),
                f"command does not match frozen verification {verification}/{target}")
        refs = command["evidenceRefs"]
        require(isinstance(refs, list) and len(refs) == len(set(refs)) and set(refs) <= assets.keys(),
                f"missing or duplicate evidence for {command['id']}", 2)
        entries = [assets[ref] for ref in refs]
        for entry in entries:
            require(set(expected_source) <= set(entry["sourceCommits"]),
                    f"wrong evidence revision for {command['id']}")
        logs = [entry for entry in entries if entry["kind"] == "COMMAND_LOG"]
        captures = [entry for entry in entries if entry["kind"] == "COMMAND_METADATA"]
        if verification == "CONCURRENCY":
            # The executable captured its start/end, outcome, and exit code in a single JSON log.
            captures = logs
        require(logs and len(captures) == 1, f"missing independent command capture for {command['id']}", 2)
        metadata = captures[0]
        require(metadata["id"] not in used, f"reused a command capture for {command['id']}")
        used.add(metadata["id"])
        capture = load_json(Path(metadata["path"]))
        verify_capture(command, capture)
        if "args" in capture:
            require(all(arg in command["command"] for arg in capture["args"]),
                    f"{command['id']} differs from captured command arguments")
        if "command" in capture:
            require(" ".join(capture["command"]) == command["command"], f"{command['id']} captured a different command")
        log = read_log(Path(logs[0]["path"]))
        if verification in REQUIRED_SUITES:
            reports = [Path(entry["path"]) for entry in entries if entry["kind"] == "TEST_REPORT"]
            totals, suites = junit_totals(reports)
            require(totals == command["testCounts"], f"{command['id']} counts differ from independently captured XML")
            require(REQUIRED_SUITES[verification] <= suites, f"required suite missing for {command['id']}", 2)
            if verification == "MAVEN":
                for project in ("studio", "common", "app", "bootstart"):
                    require(re.search(rf"^\[INFO\] {project} \.+ SUCCESS", log, re.M),
                            f"full Maven reactor not confirmed for {command['id']}")
                require(f"Tests run: {totals['total']}, Failures: 0, Errors: 0, Skipped: 0" in log,
                        f"Maven log and XML counts disagree for {command['id']}")
            if verification == "FRONT-TEST":
                require(re.search(rf"Tests\s+{totals['total']} passed", log), f"Vitest log and XML counts disagree")
            summaries[command["id"]] = totals
        if verification == "FORMAT":
            require("BUILD SUCCESS" in log and ":check (default-cli) @ app" in log
                    and ":check (default-cli) @ bootstart" in log, "Spotless did not cover app and bootstart")
        if verification == "GATE-TEST":
            require(re.search(r"^Ran [1-9]\d* tests? in ", log, re.M)
                    and re.search(r"^OK$", log, re.M), "gate regression tests were not confirmed")
        if verification == "ENV":
            require(len(capture["steps"]) == 5 and all(re.search(pattern, log, re.M) for pattern in (
                r'^(?:openjdk|java) version "21\.', r"^Apache Maven 3\.", r"^v24\.",
                r"^11\.25\.0$", r"^Python 3\.",
            )), f"required tool versions missing for {command['id']}")
        if verification == "FRONT-BUILD":
            require("vite build" in log and "built in " in log, "missing production build output")
        if verification == "HTTP-SMOKE":
            require(json.loads(log)["result"] == "PASS", f"HTTP smoke failed for {command['id']}")
        if verification in {"DB-REOPEN", "HTTP-COMPARE"}:
            comparison = [load_json(Path(entry["path"])) for entry in entries if entry["kind"] == "COMPARISON"]
            require(len(comparison) == 1 and comparison[0]["differences"] == [],
                    f"HTTP or historical corpus comparison failed for {command['id']}")
        if verification == "BROWSER":
            require(sum(line.startswith("PASS ") for line in log.splitlines()) == 9
                    and any(entry["kind"] == "SCREENSHOT" for entry in entries),
                    f"browser scenario coverage incomplete for {command['id']}")
        if verification == "GRAPH":
            require(sum(line.startswith("PASS ") for line in log.splitlines()) == 10,
                    f"graph assertions missing for {command['id']}")
        if verification == "EVIDENCE":
            require(len(log.splitlines()) == 9 and "out-of-scope query: false" in log
                    and "historical accepted Action: true" in log, f"citation checks missing for {command['id']}")
        if verification == "CONCURRENCY":
            require(capture["actual"] == capture["expected"] and capture["actual"]["codes"] == [200, 409]
                    and capture["syncEnabled"] is False, f"concurrency test results differ for {command['id']}")
    return len(matches), summaries


def verify_prerequisites(repo, required, records, candidate):
    require(len(required) == len(set(required)), "duplicate prerequisite stage")
    stages = {record["stage"]: record for record in records}
    require(len(stages) == len(records) and stages.keys() == set(required), "prerequisite records differ from manifest", 2)
    for stage in required:
        record = stages[stage]
        require(record["status"] == "PASSED" and record["nextStageAllowed"] is True and record["exitCode"] == 0,
                f"prerequisite stage {stage} has not passed")
        earlier = record["candidateCommit"]
        require(HEX_40.fullmatch(earlier) and git_ancestor(repo, earlier, candidate),
                f"prerequisite stage {stage} is not an ancestor of the candidate")


def prerequisite_reports(repo, manifest):
    if "prerequisiteRecords" not in manifest:
        return [load_json(repo / f"docs/development/acceptance/stage-{stage:02}/gate-report.json")
                for stage in manifest["previousStages"]]
    reports = []
    for reference in manifest["prerequisiteRecords"]:
        path = (repo / reference["gateReport"]).resolve()
        require(path.is_relative_to(repo) and sha256(path) == reference["gateReportSha256"],
                "prerequisite gate report hash mismatch")
        report = load_json(path)
        require(report["stage"] == reference["stage"] and report["candidateCommit"] == reference["candidateCommit"],
                "prerequisite gate report identity mismatch")
        reports.append(report)
    return reports


def verify_reuse(original_commands, commands, original_evidence, assets):
    current = index_by_id(commands, "command")
    for command in original_commands:
        ident = command["id"]
        require(ident in current and all(current[ident].get(key) == value for key, value in command.items()),
                f"reused command differs from the original capture: {ident}")
    for entry in original_evidence:
        require(assets.get(entry["id"]) == entry, f"reused evidence differs from the original index: {entry['id']}")


def verify_first_attempt(stage_dir, result, assets):
    first = stage_dir/"attempt-01"
    hashes = result["previousAttemptSha256"]
    require(hashes.keys() == {"review.md", "result.json", "evidence-index.json"}, "first attempt is not pinned")
    for file, digest in hashes.items():
        require(sha256(first/file) == digest, f"first attempt changed: {file}")
    old = load_json(first/"result.json")
    require(old["attempt"] == 1 and old["candidateCommit"] == result["candidateCommit"]
            and old["status"] == "BLOCKED" and old["nextStageAllowed"] is False,
            "retrospective approval requires retaining the original blocked result")
    index = load_json(first/"evidence-index.json")
    verify_reuse(old["commands"], result["commands"], index["evidence"], assets)


def reviewer_payload_sha256(result):
    # Publishing the gate decision only changes these three summary fields.
    payload = {key: value for key, value in result.items() if key not in {"status", "gate", "nextStageAllowed"}}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def evaluate(repo, stage, attempt):
    stage_dir = repo/f"docs/development/acceptance/stage-{stage:02}"
    attempt_dir = stage_dir/f"attempt-{attempt:02}"
    manifest, digest, head = verify_manifest(repo, stage_dir, stage)
    result = load_json(attempt_dir/"result.json")
    index = load_json(attempt_dir/"evidence-index.json")
    review = attempt_dir/"review.md"
    require(review.is_file(), "missing independent review Markdown", 2)
    assets = verify_index(index, manifest["baselineCommit"], manifest["candidateCommit"])
    if manifest["retrospective"]:
        verify_first_attempt(stage_dir, result, assets)
    checks = verify_review(manifest, result, review.read_text(encoding="utf-8-sig"), assets, digest, attempt)
    required = manifest["previousStages"]
    previous = prerequisite_reports(repo, manifest)
    verify_prerequisites(repo, required, previous, manifest["candidateCommit"])
    command_count, tests = verify_commands(manifest, result, assets)
    return {
        "baselineCommit": manifest["baselineCommit"],
        "candidateCommit": manifest["candidateCommit"],
        "checkoutHead": head,
        "checkManifestSha256": digest,
        "evidenceIndexSha256": sha256(attempt_dir/"evidence-index.json"),
        "reviewerPayloadSha256": reviewer_payload_sha256(result),
        "gateImplementationSha256": sha256(Path(__file__)),
        "reviewerSessionId": result["reviewer"]["sessionId"],
        "requiredChecks": checks,
        "requiredCommandRuns": command_count,
        "verifiedEvidenceFiles": len(assets),
        "testReports": tests,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=int, required=True)
    parser.add_argument("--attempt", type=int, default=2)
    parser.add_argument("--report", type=Path, help="alternate output file, useful for reruns")
    args = parser.parse_args()
    if args.stage not in {1, 2, 3, 4} or args.attempt < (2 if args.stage == 1 else 1):
        parser.error("supported attempts are stage 1 attempt 2+ and stages 2–4 attempt 1+")
    repo = Path(__file__).resolve().parents[2]
    output = args.report or repo/f"docs/development/acceptance/stage-{args.stage:02}/attempt-{args.attempt:02}/gate-report.json"
    started = datetime.now(timezone.utc).isoformat()
    details = {}
    reason = None
    code = 0
    try:
        details = evaluate(repo, args.stage, args.attempt)
    except GateError as error:
        code, reason = error.exit_code, str(error)
    except (KeyError, ValueError, TypeError, AttributeError, ET.ParseError) as error:
        code, reason = 1, f"Invalid acceptance input: {error}"
    except (OSError, subprocess.SubprocessError) as error:
        code, reason = 2, f"Cannot read acceptance input: {error}"
    status = {0: "PASSED", 1: "FAILED", 2: "BLOCKED"}[code]
    report = {
        "schemaVersion": 1, "stage": args.stage, "attempt": args.attempt,
        "status": status, "exitCode": code, "nextStageAllowed": code == 0,
        "startedAt": started, "endedAt": datetime.now(timezone.utc).isoformat(),
        "reason": reason, **details,
    }
    try:
        with output.open("x", encoding="utf-8") as out:
            json.dump(report, out, ensure_ascii=False, indent=2)
            out.write("\n")
    except OSError as error:
        print(f"BLOCKED: could not save {output}: {error}")
        return 2
    print(f"{status}: {reason or 'frozen scope and independent evidence verified'}")
    print(output)
    return code


if __name__ == "__main__":
    raise SystemExit(main())

