#!/usr/bin/env python3
"""Verify the Java runtime against a user-configured real model; never simulates decisions."""
import argparse
import collections
import http.cookiejar
import json
import os
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--model-id', required=True)
    parser.add_argument('--username', default='admin')
    parser.add_argument('--password-env', default='STUDIO_ADMIN_PASSWORD')
    args = parser.parse_args()
    password = os.environ.get(args.password_env)
    if not password:
        parser.error(f'Set {args.password_env} securely before running (development default: 123456)')
    client = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )

    def request(path, method='GET', body=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(
            args.base_url.rstrip('/') + '/api' + path,
            data=None if body is None else json.dumps(body).encode(),
            headers=headers, method=method,
        )
        with client.open(req, timeout=180) as response:
            data = response.read()
            return json.loads(data) if data else None

    request('/auth/login', 'POST', {'username': args.username, 'password': password})
    models = request('/models')
    if not any(model['id'] == args.model_id for model in models):
        raise RuntimeError('Model not found; configure it in the Models page first')
    world = request('/examples/information-leak', 'POST', {})
    visible = [entity['id'] for entity in world['entities']]
    scenario = request('/scenarios', 'POST', {
        'name': 'P3 real-model acceptance', 'worldId': world['id'],
        'maxSteps': 5, 'maxEvents': 500,
        'agents': [
            {'id': 'investigator', 'name': 'Investigator',
             'goal': 'Use graph tools to investigate the potential leak. Review currently unreviewed entities. Re-observe current state before every decision. Never assume propagation is a fact.',
             'modelId': args.model_id, 'allowedActions': ['ANALYZE_RELATIONS', 'MARK_REVIEWED'],
             'visibleEntityIds': visible, 'maxDecisions': 5},
            {'id': 'response', 'name': 'Response Agent',
             'goal': 'Reduce risk by disabling active sync and sharing channels; review remaining unreviewed systems when appropriate. Adapt decisions to the current graph and action feedback.',
             'modelId': args.model_id, 'allowedActions': ['DISABLE_SYNC', 'REVOKE_SHARING', 'MARK_REVIEWED'],
             'visibleEntityIds': visible, 'maxDecisions': 5},
        ],
    })
    run = request('/scenarios/' + scenario['id'] + '/runs', 'POST', {})
    run_id = run['id']
    request('/runs/' + run_id + '/inject', 'POST', {
        'eventType': 'POTENTIAL_LEAK', 'targetId': 'document_f',
        'simulationTime': 0, 'parameters': {'confidence': 0.8},
    }, str(uuid.uuid4()))
    for _ in range(5):
        request('/runs/' + run_id + '/step', 'POST', {}, str(uuid.uuid4()))
    events = request('/runs/' + run_id + '/events')
    state = request('/runs/' + run_id + '/state')
    counts = collections.Counter(event['eventType'] for event in events)
    actors = collections.Counter(event['agentId'] for event in events if event['eventType'] == 'AGENT_DECISION')
    checks = {
        'two_agents_five_decisions_each': len(actors) == 2 and all(n >= 5 for n in actors.values()),
        'three_world_changes': counts['WORLD_STATE_CHANGED'] >= 3,
        'accepted_action': counts['ACTION_ACCEPTED'] >= 1,
        'rejected_action': counts['ACTION_REJECTED'] >= 1,
        'autonomous_tool_call': counts['AGENT_TOOL_CALL'] >= 1,
        'persisted_version_matches_changes': state['version'] == counts['WORLD_STATE_CHANGED'],
        'observed_updated_state': any(event['payload']['observation']['version'] > 0 for event in events if event['eventType'] == 'AGENT_OBSERVATION'),
    }
    print(json.dumps({'runId': run_id, 'eventCounts': counts, 'checks': checks}, indent=2))
    # Actual model decisions are not scripted. A model that never proposes an invalid action
    # may fail the rejection criterion; this is reported honestly, not replaced with fake events.
    return 0 if all(checks.values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
