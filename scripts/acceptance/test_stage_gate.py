import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import stage_gate as gate


CANDIDATE = "a" * 40
BASELINE = "b" * 40
DIGEST = "c" * 64
CHECK = {"id": "G-01", "required": True, "status": "PASSED", "evidenceRefs": ["EV-001"]}
REVIEW = "| G-01 | PASSED |\n"
CHECKS = {"G-01": {"id": "G-01", "required": True}}


class CandidateRegistrationTest(unittest.TestCase):
    def test_prospective_manifest_resolves_candidate_from_separate_record(self):
        with tempfile.TemporaryDirectory() as directory:
            stage_dir = Path(directory)
            manifest = {
                "stage": 2, "baselineCommit": BASELINE, "candidateCommit": None, "retrospective": False,
                "frozenAt": "2026-11-26T11:00:00+08:00",
            }
            registration = {
                "schemaVersion": 1, "stage": 2, "baselineCommit": BASELINE,
                "candidateCommit": CANDIDATE, "checkManifestSha256": DIGEST,
                "registeredAt": "2026-11-26T12:00:00+08:00",
            }
            path = stage_dir / "candidate.json"
            path.write_text(json.dumps(registration), encoding="utf-8")
            self.assertEqual(CANDIDATE, gate.resolve_candidate(stage_dir, manifest, DIGEST))
            self.assertIsNone(manifest["candidateCommit"])
            registration["checkManifestSha256"] = "d" * 64
            path.write_text(json.dumps(registration), encoding="utf-8")
            with self.assertRaisesRegex(gate.GateError, "registration"):
                gate.resolve_candidate(stage_dir, manifest, DIGEST)

    def test_historical_manifest_still_resolves_inline_candidate(self):
        manifest = {"candidateCommit": CANDIDATE, "retrospective": True}
        self.assertEqual(CANDIDATE, gate.resolve_candidate(Path("."), manifest, DIGEST))

    def test_prerequisite_report_is_pinned_by_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            report = repo / "gate-report.json"
            report.write_text(json.dumps({"stage": 1, "candidateCommit": CANDIDATE}), encoding="utf-8")
            manifest = {"previousStages": [1], "prerequisiteRecords": [{
                "stage": 1, "candidateCommit": CANDIDATE,
                "gateReport": "gate-report.json", "gateReportSha256": gate.sha256(report),
            }]}
            self.assertEqual([1], [entry["stage"] for entry in gate.prerequisite_reports(repo, manifest)])
            report.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(gate.GateError, "hash"):
                gate.prerequisite_reports(repo, manifest)


class StageThreeGateTest(unittest.TestCase):
    def test_stage_three_runs_through_the_same_evaluator(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with patch("sys.argv", ["stage_gate.py", "--stage", "3", "--attempt", "1", "--report", str(report)]):
                with patch.object(gate, "evaluate", return_value={"requiredChecks": 60}) as evaluate:
                    self.assertEqual(0, gate.main())
            self.assertEqual((3, 1), evaluate.call_args.args[1:])
            self.assertTrue(json.loads(report.read_text(encoding="utf-8"))["nextStageAllowed"])

    def test_stage_three_suites_cannot_be_omitted(self):
        stage_checks = {
            "STAGE3-SCHEDULER": "ReviewerStage3SchedulerTest",
            "STAGE3-RECOVERY": "ReviewerStage3RecoveryTest",
            "STAGE3-EVALUATION": "ReviewerStage3EvaluationTest",
            "STAGE3-TERMINATION-E2E": "ReviewerStage3TerminationFlowTest",
        }
        definitions = []
        for ident, suite in stage_checks.items():
            self.assertRegex(f"mvn -Dtest={suite} -Dsurefire.failIfNoSpecifiedTests=false test",
                             gate.COMMAND_PATTERNS[ident])
            self.assertEqual({f"com.trs.studio.{suite}"}, gate.REQUIRED_SUITES[ident])
            definitions.append({"id": ident, "target": "candidate", "required": True})
        with self.assertRaisesRegex(gate.GateError, "missing required command runs"):
            gate.match_commands(definitions, [])


class ReviewerTest(unittest.TestCase):
    def setUp(self):
        self.result = {
            "schemaVersion": 1, "stage": 1, "attempt": 2,
            "candidateCommit": CANDIDATE, "baselineCommit": BASELINE,
            "checkManifestSha256": DIGEST, "planSha256": DIGEST, "acceptanceSha256": DIGEST,
            "reviewer": {
                "role": "REVIEWER", "sessionId": "independent-test",
                "attestedAt": "2026-11-25T18:00:00+08:00",
            },
            "checks": [copy.deepcopy(CHECK)],
            "conditions": [{"id": "EXT", "status": "NOT_APPLICABLE"}],
            "issues": [],
        }
        self.manifest = {
            "stage": 1, "candidateCommit": CANDIDATE, "baselineCommit": BASELINE,
            "frozenAt": "2026-11-25T17:00:00+08:00",
            "plans": {name: {"sha256": DIGEST} for name in ("development", "acceptance")},
            "checks": list(CHECKS.values()),
            "conditions": [{"id": "EXT", "enabled": False, "approvalStatus": "APPROVED",
                            "applicability": "NOT_APPLICABLE", "reason": "fixture"}],
        }
        self.assets = {"EV-001": {"checkIds": ["G-01"]}}

    def check(self, review=REVIEW):
        return gate.verify_review(self.manifest, self.result, review, self.assets, DIGEST, 2)

    def reject(self, pattern):
        with self.assertRaisesRegex(gate.GateError, pattern):
            self.check()

    def test_consistent_independent_review_passes(self):
        self.assertEqual(self.check(), 1)

    def test_wrong_candidate_refused(self):
        self.result["candidateCommit"] = "d" * 40
        self.reject("candidateCommit")

    def test_missing_mandatory_check_refused(self):
        self.result["checks"] = []
        self.reject("check IDs")

    def test_duplicate_check_refused(self):
        self.result["checks"].append(copy.deepcopy(CHECK))
        self.reject("duplicate")

    def test_changed_required_refused(self):
        self.result["checks"][0]["required"] = False
        self.reject("required")

    def test_self_declared_pass_cannot_override_failed_review(self):
        self.result["status"] = "PASSED"
        self.result["checks"][0]["status"] = "FAILED"
        self.reject("G-01")

    def test_review_markdown_must_match(self):
        with self.assertRaisesRegex(gate.GateError, "Markdown"):
            self.check("| G-01 | BLOCKED |\n")

    def test_wrong_check_evidence_refused(self):
        self.assets["EV-001"]["checkIds"] = ["G-02"]
        self.reject("evidence")

    def test_open_major_refused(self):
        self.result["issues"] = [{"id": "I-1", "checkId": "G-01", "status": "OPEN", "severity": "MAJOR"}]
        self.reject("I-1")

    def test_unapproved_condition_refused(self):
        self.manifest["conditions"][0]["approvalStatus"] = "PENDING"
        self.reject("approval")


class CommandTest(unittest.TestCase):
    def setUp(self):
        self.verification = [{"id": "MAVEN", "target": "both", "required": True}]
        self.commands = [
            {"id": f"CMD-{target.upper()}", "verificationId": "MAVEN", "target": target}
            for target in ("baseline", "candidate")
        ]

    def test_both_versions_required(self):
        self.assertEqual(len(gate.match_commands(self.verification, self.commands)), 2)

    def test_missing_command_refused(self):
        with self.assertRaisesRegex(gate.GateError, "candidate"):
            gate.match_commands(self.verification, self.commands[:1])

    def test_one_run_cannot_satisfy_two_required_entries(self):
        self.commands[1]["target"] = "baseline"
        with self.assertRaisesRegex(gate.GateError, "duplicate"):
            gate.match_commands(self.verification, self.commands)

    def test_recorded_exit_must_match_capture(self):
        meta = {"exitCode": 1, "started": "2026-11-25T17:30:00+08:00", "ended": "2026-11-25T17:31:00+08:00"}
        record = {"id": "CMD", "exitCode": 0, "status": "PASSED",
                  "startedAt": meta["started"], "endedAt": meta["ended"]}
        with self.assertRaisesRegex(gate.GateError, "exit code"):
            gate.verify_capture(record, meta)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.path = self.root/"report.xml"
        self.path.write_bytes(b"<testsuite/>")
        self.entry = {"id": "EV-001", "kind": "TEST_REPORT", "path": str(self.path),
                      "sha256": hashlib.sha256(self.path.read_bytes()).hexdigest(),
                      "candidateCommit": CANDIDATE, "sourceCommits": [CANDIDATE], "checkIds": ["G-01"]}
        self.index = {"schemaVersion": 1, "candidateCommit": CANDIDATE,
                      "baselineCommit": BASELINE, "evidenceRoots": [str(self.root)], "evidence": [self.entry]}

    def test_hash_bound_evidence_passes(self):
        self.assertEqual(len(gate.verify_index(self.index, BASELINE, CANDIDATE)), 1)

    def test_missing_report_refused(self):
        self.path.unlink()
        with self.assertRaisesRegex(gate.GateError, "missing"):
            gate.verify_index(self.index, BASELINE, CANDIDATE)

    def test_hash_mismatch_refused(self):
        self.path.write_bytes(b"changed")
        with self.assertRaisesRegex(gate.GateError, "hash"):
            gate.verify_index(self.index, BASELINE, CANDIDATE)

    def test_unrun_tests_refused(self):
        self.path.write_text('<testsuite name="suite" tests="0"/>', encoding="utf-8")
        with self.assertRaisesRegex(gate.GateError, "no tests"):
            gate.junit_totals([self.path])

    def test_skipped_tests_refused(self):
        self.path.write_text(
            '<testsuite name="suite" tests="1" skipped="1"><testcase name="x"><skipped/></testcase></testsuite>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(gate.GateError, "skipped"):
            gate.junit_totals([self.path])

    def test_real_xml_counts_recomputed(self):
        self.path.write_text('<testsuite name="suite" tests="1"><testcase name="x"/></testsuite>', encoding="utf-8")
        self.assertEqual(gate.junit_totals([self.path])[0]["total"], 1)


class PrerequisiteTest(unittest.TestCase):
    def test_unpassed_prior_stage_refused(self):
        with self.assertRaisesRegex(gate.GateError, "stage 1"):
            gate.verify_prerequisites(self, [1], [{"stage": 1, "status": "BLOCKED"}], CANDIDATE)

    def test_prior_candidate_must_be_an_ancestor(self):
        record = {"stage": 1, "status": "PASSED", "nextStageAllowed": True, "exitCode": 0,
                  "candidateCommit": BASELINE}
        with patch.object(gate, "git_ancestor", return_value=False):
            with self.assertRaisesRegex(gate.GateError, "ancestor"):
                gate.verify_prerequisites(self, [1], [record], CANDIDATE)


class CheckoutTest(unittest.TestCase):
    def check(self, changes, staged=""):
        replies = [CANDIDATE, changes, staged, ".idea/compiler.xml\0"]
        with patch.object(gate, "git_ancestor", return_value=True), patch.object(
            gate, "git", side_effect=[SimpleNamespace(stdout=value) for value in replies]
        ):
            return gate.verify_checkout(Path.cwd(), 1, BASELINE, CANDIDATE)

    def test_acceptance_process_documents_are_allowed(self):
        self.assertEqual(self.check("AGENTS.md\0docs/development/PROGRESS.md\0"), CANDIDATE)

    def test_unreviewed_staged_product_file_is_rejected(self):
        with self.assertRaisesRegex(gate.GateError, "backend/pom.xml"):
            self.check("", "backend/pom.xml\0")


class PreviousEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.original = {"id": "CMD-OLD", "exitCode": 0, "evidenceRefs": ["EV-001"]}
        self.reused = {**self.original, "verificationId": "MAVEN", "target": "candidate"}
        self.entry = {"id": "EV-001", "sha256": DIGEST}

    def test_reuse_adds_binding_without_rewriting_original_capture(self):
        gate.verify_reuse([self.original], [self.reused], [self.entry], {"EV-001": self.entry})

    def test_rewritten_old_capture_refused(self):
        self.reused["exitCode"] = 1
        with self.assertRaisesRegex(gate.GateError, "CMD-OLD"):
            gate.verify_reuse([self.original], [self.reused], [self.entry], {"EV-001": self.entry})

    def test_rewritten_old_evidence_reference_refused(self):
        with self.assertRaisesRegex(gate.GateError, "EV-001"):
            gate.verify_reuse([self.original], [self.reused], [self.entry], {})


if __name__ == "__main__":
    unittest.main()

