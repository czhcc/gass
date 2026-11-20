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
    profile = next(profile for profile in request('/scenario-profiles') if profile['domainCode'] == world['domainCode'])
    scenario = request('/scenarios', 'POST', {
        'name': 'P3 real-model acceptance', 'worldId': world['id'],
        'maxSteps': 5, 'maxEvents': 2000,
        'team': profile['team'],
        'agents': [{**agent, 'modelId': args.model_id, 'visibleEntityIds': visible, 'maxDecisions': 20}
                   for agent in profile['agents']],
    })
    run = request('/scenarios/' + scenario['id'] + '/runs', 'POST', {})
    run_id = run['id']
    request('/runs/' + run_id + '/initial-input', 'POST', {})
    for _ in range(5):
        if request('/runs/' + run_id)['status'] not in ('PAUSED', 'RUNNING'):
            break
        request('/runs/' + run_id + '/step', 'POST', {}, str(uuid.uuid4()))
    events = request('/runs/' + run_id + '/events')
    state = request('/runs/' + run_id + '/state')
    counts = collections.Counter(event['eventType'] for event in events)
    actors = collections.Counter(event['agentId'] for event in events if event['eventType'] == 'AGENT_DECISION')
    checks = {
        'master_review': counts['MASTER_REVIEW'] >= 1,
        'delegated_subtask': counts['SUBTASK_COMPLETED'] >= 1,
        'configured_llm_provenance': all(event['payload']['modelMode'] == 'LLM' for event in events if event['eventType'] == 'MODEL_REQUESTED'),
        'three_world_changes': counts['WORLD_STATE_CHANGED'] >= 3,
        'accepted_action': counts['ACTION_ACCEPTED'] >= 1,
        'autonomous_tool_call': counts['GRAPH_QUERY_RESULT'] >= 1,
        'persisted_version_matches_changes': state['version'] == counts['WORLD_STATE_CHANGED'],
        'observed_updated_state': any(event['payload']['result']['version'] > 0 for event in events if event['eventType'] == 'GRAPH_QUERY_RESULT'),
    }
    print(json.dumps({'runId': run_id, 'eventCounts': counts, 'actionActors': actors,
                      'outcome': request('/runs/' + run_id + '/outcome'),
                      'usage': request('/runs/' + run_id + '/usage'), 'checks': checks}, indent=2))
    # Actual model decisions and failed effects are reported as recorded by the engine.
    return 0 if all(checks.values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
