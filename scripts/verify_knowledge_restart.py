#!/usr/bin/env python3
"""Verify local MCP import, forced JVM restart, history, and legacy HTTP smoke flows."""
import argparse
import contextlib
import http.cookiejar
import http.server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class ReadOnlyMcp(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        message = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        method = message["method"]
        self.server.calls.append(method)
        if method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
            return
        if method == "initialize":
            result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}}
        elif method == "tools/list":
            result = {"tools": [{"name": "lookup_accounts", "annotations": {"readOnlyHint": True}}]}
        else:
            assert method == "tools/call" and message["params"]["name"] == "lookup_accounts"
            assert message["params"]["arguments"] == {"selector": "fixture", "depth": 0, "limit": 4}
            result = {"structuredContent": {
                "sourceVersion": "fixture-v1", "fetchedAt": "2026-01-01T00:00:00Z",
                "coverage": {"queryScope": "fixture", "completeness": "PARTIAL", "truncated": False, "hasMore": False},
                "entities": [{"externalId": "C", "type": "account", "name": "External C",
                              "properties": {"minor": 5000, "ccy": "CNY"}}], "relations": [],
            }}
        payload = json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def listener():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class App:
    def __init__(self, jar, root):
        self.jar = jar
        self.root = root
        self.password = secrets.token_urlsafe(24)

    @contextlib.contextmanager
    def process(self, name):
        port = listener()
        url = f"http://127.0.0.1:{port}"
        env = {**os.environ, "SPRING_APPLICATION_JSON": json.dumps({"studio": {"admin-password": self.password}})}
        args = [
            "java", "-jar", str(self.jar), "--spring.profiles.active=dev", "--server.address=127.0.0.1",
            f"--server.port={port}", f"--spring.datasource.url=jdbc:sqlite:{self.root / 'runs.db'}",
            f"--studio.model-key-file={self.root / 'model.key'}",
            f"--studio.knowledge.provider-config={self.root / 'sources.json'}",
        ]
        with (self.root / f"{name}.log").open("wb") as log:
            app = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, env=env, cwd=self.root)
            try:
                api = http_client(url, self.password)
                timeout = time.monotonic() + 75
                while True:
                    try:
                        api("/auth/login", "POST", {"username": "admin", "password": self.password})
                        break
                    except AssertionError as error:
                        if len(error.args) != 1 or not isinstance(error.args[0], tuple) or error.args[0][1] != 401:
                            raise
                    except OSError:
                        pass
                    if app.poll() is not None or time.monotonic() >= timeout:
                        raise RuntimeError(f"App startup failed, see {name}.log")
                    time.sleep(0.3)
                yield app.pid, url, api
            finally:
                if app.poll() is None:
                    app.kill()
                app.wait(timeout=20)


def http_client(url, password):
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(path, method="GET", payload=None, key=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        req = urllib.request.Request(url + "/api" + path, method=method, headers=headers,
                                     data=None if payload is None else json.dumps(payload).encode())
        try:
            with client.open(req, timeout=40) as response:
                assert response.status == expected, (path, response.status)
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            if error.code != expected:
                raise AssertionError((path, error.code, error.read().decode())) from error

    return request


def phase_one(api):
    world = api("/examples/bank-transfer", "POST", {})
    model = api("/models/mock", "POST", {})
    scene = api("/scenarios", "POST", {
        "name": "Local MCP restart", "worldId": world["id"], "maxSteps": 5, "maxEvents": 200,
        "scheduling": {"contractVersion": 1, "mode": "EVENT_DRIVEN", "timeUnit": "TICK", "maxWorkPerTime": 20},
        "agents": [{"id": "reader", "name": "Reader", "goal": "fixture", "modelId": model["id"],
                    "allowedActions": [], "visibleEntityIds": ["account_a"], "maxDecisions": 3}],
        "expansionPolicy": {
            "operators": ["admin"], "maxCalls": 4, "maxDepth": 2, "maxEntitiesPerCall": 4,
            "maxRelationsPerCall": 10, "maxTotalImported": 10, "sources": {"local-fixture": {
                "mapping": {"providerCode": "local-fixture", "revision": 1,
                            "entities": {"account": {"internalType": "BANK_ACCOUNT", "fields": {
                                "minor": {"internalName": "balanceMinor", "unit": "INTEGER"},
                                "ccy": {"internalName": "currency", "unit": "VALUE"}}}},
                            "relations": {}},
                "initialIdentities": {"entities": {}, "relations": {}},
                "readersByExternalId": {"C": ["reader"]},
            }},
        },
    })
    run_id = api(f"/scenarios/{scene['id']}/runs", "POST", {})["id"]
    root = f"/runs/{run_id}"
    before = api(root + "/state")
    request = {"providerCode": "local-fixture",
               "query": {"operation": "SEARCH", "selector": "fixture", "depth": 0, "limit": 4}}
    candidate = api(root + "/knowledge/query", "POST", request)
    assert api(root + "/state") == before
    assert api(root + "/knowledge/candidates/" + candidate["id"]) == candidate
    receipt = api(root + "/knowledge/imports", "POST", {"candidateId": candidate["id"]})
    state = api(root + "/state")
    assert state["version"] == before["version"] + 1
    assert state["simulationTime"] == before["simulationTime"]
    imported = next(entity for entity in state["entities"] if entity["name"] == "External C")
    assert imported["properties"]["balanceMinor"] == 5000
    assert receipt["importedBalanceMinor"] == {"CNY": 5000}
    assert not {"WORLD_STATE_CHANGED", "ENTITY_CREATED", "AUTO_ACTION"} & {
        event["eventType"] for event in api(root + "/events")
    }
    sequence = receipt["eventSequence"]
    assert api(root + f"/replay?sequence={sequence-1}") == before
    assert api(root + f"/replay?sequence={sequence}") == state
    return root, before, state, receipt, imported["id"], candidate["id"], request


def phase_two(api, case):
    root, before, state, receipt, entity, candidate, request = case
    assert api(root + "/state") == state
    assert api(root + "/knowledge/query", "POST", request, expected=409) is None
    assert api(root + "/knowledge/imports", "POST", {"candidateId": candidate}) == receipt
    source = api(root + "/knowledge/provenance?" + urllib.parse.urlencode({"objectId": entity}))
    assert source[0]["externalId"] == "C" and source[0]["receiptId"] == receipt["receiptId"]
    sequence = receipt["eventSequence"]
    for point, expected in [(sequence-1, before), (sequence, state)]:
        assert api(root + f"/replay?sequence={point}") == expected
        branch = api(root + "/fork", "POST", {"sequence": point, "label": "restart fixture"}, f"fork-{point}")
        child = f"/runs/{branch['id']}"
        assert api(child + "/state")["entities"] == expected["entities"]
        assert (entity in api(child + "/agents")[0]["visibleEntityIds"]) == (point == sequence)
        api(child + "/knowledge/imports", "POST", {"candidateId": candidate}, expected=400)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--legacy-smoke", action="store_true")
    args = parser.parse_args()
    workspace = args.evidence_dir.resolve()
    workspace.mkdir(exist_ok=False, parents=True)
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), ReadOnlyMcp) as mcp:
        mcp.calls = []
        thread = threading.Thread(target=mcp.serve_forever, daemon=True)
        thread.start()
        (workspace / "sources.json").write_text(json.dumps({"sources": [{
            "providerCode": "local-fixture", "endpoint": f"http://127.0.0.1:{mcp.server_port}/mcp",
            "readOnlyTools": {"SEARCH": "lookup_accounts"}, "timeoutMilliseconds": 4000, "maximumBytes": 32768,
        }]}), encoding="utf-8")
        app = App(args.jar.resolve(), workspace)
        try:
            with app.process("before-restart") as (first, url, api):
                if args.legacy_smoke:
                    smoke = Path(__file__).with_name("smoke_mock.py")
                    with (workspace / "legacy-smoke.log").open("wb") as log:
                        subprocess.run([os.sys.executable, str(smoke), "--base-url", url], check=True,
                                       env={**os.environ, "STUDIO_ADMIN_PASSWORD": app.password}, stdout=log)
                case = phase_one(api)
        finally:
            mcp.shutdown()
            thread.join()
        assert mcp.calls == ["initialize", "notifications/initialized", "tools/list", "tools/call"], mcp.calls
    with app.process("after-restart") as (second, _, api):
        assert first != second
        phase_two(api, case)
    report = {"result": "PASS", "beforePid": first, "afterPid": second,
              "importSequence": case[3]["eventSequence"], "mcpCalls": mcp.calls}
    (workspace / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
