import type { Lang } from './types';

/** Keeps SDK transport metadata out of human-readable reports. Original records remain untouched. */
export function publicNarrative(value: unknown, lang: Lang): string {
  if (typeof value !== 'string') return '';
  const text = value.replace(/^session_id:[^\r\n]*\r?\n\s*/, '').trim();
  if (lang === 'zh') {
    const chinese = (text.match(/\p{Script=Han}/gu) ?? []).length;
    const english = (text.match(/[A-Za-z]/g) ?? []).length;
    const words = (text.match(/[A-Za-z]{3,}/g) ?? []).length;
    if (!chinese || (words > 12 && english > chinese * 4)) return '';
  }
  return text.replace(/\*\*([^*\n]+)\*\*/g, '$1').replace(/`([^`\n]+)`/g, '$1');
}

/** Summarizes only persisted tool evidence, without inventing a translation or a model finding. */
export function executionSummary(results: Record<string, unknown>[], lang: Lang): string {
  const queries = results.reduce((n, r) => n + (Array.isArray(r.queryResults) ? r.queryResults.length : 0), 0);
  const receipts = results.flatMap(r => Array.isArray(r.receipts) ? r.receipts as {accepted?: boolean}[] : []);
  const accepted = receipts.filter(r => r.accepted === true).length;
  const rejected = receipts.filter(r => r.accepted === false).length;
  return lang === 'zh' ? `记录 ${queries} 次查询，引擎接受 ${accepted} 次操作、拒绝 ${rejected} 次操作。`
    : `${queries} queries recorded; ${accepted} Actions accepted and ${rejected} rejected by the engine.`;
}
