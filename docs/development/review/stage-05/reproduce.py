"""Run an archived independent fixture against its original Stage 5 candidate."""

import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import zipfile


REVIEW = Path(__file__).resolve().parent
REPOSITORY = REVIEW.parents[3]
STUDIO_FIXTURE = Path("com/trs/studio")
CASES = {
    "attempt-01": (
        "28b903253a2f73da33b60a9a0e836eaf8daae92e",
        "attempt-01",
        STUDIO_FIXTURE / "ReviewerStage5GlobalClaimTest.java",
    ),
    "attempt-02": (
        "177f62657eb800760a9263ed23989f1fb30596af",
        "attempt-02",
        STUDIO_FIXTURE / "ReviewerStage5ScopeMatrixTest.java",
    ),
    "attempt-03": (
        "c5293b03c59a5f4909a87d24d52ce1fcdb3ec259",
        "attempt-03",
        STUDIO_FIXTURE / "ReviewerStage5NaturalScopeTest.java",
    ),
    "point-witness": (
        "c5293b03c59a5f4909a87d24d52ce1fcdb3ec259",
        "attempt-03",
        Path("com/trs/simulation/runtime/ReviewerStage5ProofTest.java"),
    ),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=CASES, default="attempt-03")
    parser.add_argument("--output", type=Path, required=True, help="Directory for new logs and JSON.")
    parser.add_argument("--maven", default="mvn", help="Maven command or full path.")
    args = parser.parse_args()

    maven = shutil.which(args.maven)
    if maven is None:
        parser.error(f"Maven command not found: {args.maven}")

    candidate, attempt, fixture = CASES[args.case]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = Path(tempfile.mkdtemp(prefix=f"{args.case}-", dir=output))
    print(f"Candidate {candidate}", flush=True)
    print(f"Results: {results}", flush=True)
    env = os.environ.copy()
    env["REVIEWER_FIXTURE_PASSWORD"] = secrets.token_urlsafe(24)
    env["REVIEWER_STAGE5_OUTPUT"] = str(results)
    env["REVIEWER_REPAIR_OUTPUT"] = str(results)

    with tempfile.TemporaryDirectory(prefix="candidate-", dir=output) as temporary:
        isolated = Path(temporary)
        archive = isolated / "candidate.zip"
        subprocess.run(
            [
                "git",
                "-c",
                f"safe.directory={REPOSITORY.as_posix()}",
                "archive",
                "--format=zip",
                f"--output={archive}",
                candidate,
                "backend",
            ],
            cwd=REPOSITORY,
            check=True,
        )
        with zipfile.ZipFile(archive) as files:
            files.extractall(isolated)

        backend = isolated / "backend"
        module = backend / "bootstart"
        test = module / "src/test/java" / fixture
        test.parent.mkdir(parents=True, exist_ok=True)
        with (REVIEW / "fixtures" / attempt / fixture.name).open("rb") as source, test.open("xb") as target:
            shutil.copyfileobj(source, target)
        with (results / "maven.log").open("wb") as log:
            completed = subprocess.run(
                [
                    maven, "-B", "-pl", "bootstart", "-am", "test",
                    f"-Dtest={fixture.stem}", "-Dsurefire.failIfNoSpecifiedTests=false",
                ],
                cwd=backend,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        for report in (module / "target/surefire-reports").glob(f"*{fixture.stem}*"):
            shutil.copy2(report, results)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
