#!/usr/bin/env python3
"""End-to-end HTTP checks of simulated SDK decisions; never uses a real model or bank."""
import argparse
import collections
import http.cookiejar
import json
import os
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    args = parser.parse_args()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(path, method='GET', body=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(args.base_url.rstrip('/') + '/api' + path, method=method,
            data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(req, timeout=120) as response:
            data = response.read()
            return json.loads(data) if data else None

    request('/auth/login', 'POST', {'username': 'admin', 'password': os.environ.get('STUDIO_ADMIN_PASSWORD', '123456')})
    model = request('/models/mock', 'POST', {})
    assert model['baseUrl'] == 'mock://local'
    leak = request('/examples/information/demo', 'POST', {})
    for n in range(5):
        request('/runs/' + leak['id'] + '/step', 'POST', {}, f'smoke-{n}')
    events = request('/runs/' + leak['id'] + '/events')
    counts = collections.Counter(e['eventType'] for e in events)
    assert counts['AGENT_DECISION'] >= 10 and counts['WORLD_STATE_CHANGED'] >= 3
    assert counts['ACTION_REJECTED'] > 0 and counts['AGENT_TOOL_CALL'] > 0
    before = request('/runs/' + leak['id'] + '/state')
    request('/runs/' + leak['id'] + '/step', 'POST', {}, 'smoke-4')
    assert before == request('/runs/' + leak['id'] + '/state')
    assert len(events) == len(request('/runs/' + leak['id'] + '/events'))
    replay = request('/runs/' + leak['id'] + '/replay?sequence=0')
    assert replay['version'] == 0
    assert counts['AGENT_STARTED'] == sum(e['eventType'] == 'AGENT_STARTED' for e in request('/runs/' + leak['id'] + '/events'))

    last = events[-1]['sequence']
    req = urllib.request.Request(args.base_url.rstrip('/') + '/api/runs/' + leak['id'] + '/stream', headers={'Last-Event-ID': str(last - 2)})
    with client.open(req, timeout=10) as stream:
        ids = []
        while len(ids) < 2:
            line = stream.readline().decode()
            if line.startswith('id:'):
                ids.append(int(line[3:].strip()))
        assert ids == [last - 1, last]

    child = request('/runs/' + leak['id'] + '/fork', 'POST', {'sequence': 0, 'label': 'Containment branch'}, 'fork-smoke')
    assert child['id'] == request('/runs/' + leak['id'] + '/fork', 'POST', {'sequence': 0, 'label': 'Containment branch'}, 'fork-smoke')['id']
    request('/runs/' + child['id'] + '/actions', 'POST', {'agentId': 'response', 'expectedVersion': 0, 'decision': {'actionType': 'DISABLE_SYNC', 'targetId': 'sync_st', 'parameters': {}, 'reason': 'Smoke intervention'}}, str(uuid.uuid4()))
    assert before == request('/runs/' + leak['id'] + '/state')
    diff = request('/runs/compare?left=' + leak['id'] + '&right=' + child['id'])
    assert diff['entityDifferences'] or diff['relationDifferences']

    bank = request('/examples/bank/demo', 'POST', {})
    request('/runs/' + bank['id'] + '/step', 'POST', {}, 'bank-one')
    metrics = request('/runs/' + bank['id'] + '/evaluation')
    assert metrics['balancesMinor'] == {'account_a': 500000000, 'account_b': 700000000}
    assert metrics['totalMinor'] == 1200000000 and metrics['transferCount'] == 1
    request('/runs/' + bank['id'] + '/step', 'POST', {}, 'bank-two')
    assert request('/runs/' + bank['id'] + '/evaluation')['transferCount'] == 1
    print(json.dumps({'mode': 'MOCK', 'leakRun': leak['id'], 'branchRun': child['id'], 'bankRun': bank['id'], 'counts': counts, 'sseResumeIds': ids, 'bank': metrics, 'result': 'PASS'}, indent=2))


if __name__ == '__main__':
    main()
