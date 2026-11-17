export interface PlaybackEvent {
  sequence: number;
  eventType: string;
  payload: Record<string, unknown>;
}
export function mergeEvents<T extends PlaybackEvent>(
  current: T[],
  incoming: T[],
): T[] {
  return [
    ...new Map(
      [...current, ...incoming].map((event) => [event.sequence, event]),
    ).values(),
  ].sort((a, b) => a.sequence - b.sequence);
}
export function visibleVersion(
  events: PlaybackEvent[],
  sequence: number,
  baseline = 0,
): number {
  return events
    .filter(
      (e) => e.sequence <= sequence && e.eventType === "WORLD_STATE_CHANGED",
    )
    .reduce((v, e) => Number(e.payload.version ?? v), baseline);
}
