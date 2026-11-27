import { fireEvent, render, screen } from '@testing-library/vue';
import { expect, it, vi } from 'vitest';
import Rules from './components/Rules.vue';
import type { World } from './types';

const apiCall = vi.hoisted(() => vi.fn());
vi.mock('./api', () => ({ api: apiCall }));

it('validates, previews without saving, then saves and publishes a rule draft', async () => {
  const rule = { id: 'rule-1', revision: 0, worldId: 'world-1', name: 'Propagation', scope: 'WORLD' };
  apiCall.mockImplementation(async (path: string) => {
    if (path === '/rules?worldId=world-1') return [];
    if (path === '/rules/preview') return { plans: [] };
    if (path === '/rules/validate') return { valid: true };
    if (path === '/rules/rule-1/publish') return { ...rule, revision: 1 };
    return rule;
  });
  const world: World = { id: 'world-1', name: 'World', version: 1, domainCode: 'information', entityTypes: [], relationTypes: [], entities: [], relations: [] };
  render(Rules, { props: { lang: 'en', worlds: [world] } });
  await fireEvent.update(screen.getByLabelText('World'), 'world-1');
  await fireEvent.update(screen.getByLabelText('Rule definition (JSON)'), JSON.stringify(rule));
  await fireEvent.click(screen.getByRole('button', { name: 'Validate' }));
  await screen.findByText('Rule is valid.');
  await fireEvent.update(screen.getByLabelText('Hypothetical event (JSON)'), '{"objectId":"A","newValue":"FAILED"}');
  await fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
  await screen.findByText(/"plans"/);
  expect(apiCall).not.toHaveBeenCalledWith('/rules', 'POST', expect.anything());
  await fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
  await screen.findByText('Draft saved.');
  await fireEvent.click(screen.getByRole('button', { name: 'Publish revision' }));
  await screen.findByText('Published revision 1.');
  expect(apiCall).toHaveBeenCalledWith('/rules/rule-1/publish', 'POST', {});
});
