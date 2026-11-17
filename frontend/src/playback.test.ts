import { describe, it, expect } from "vitest";
import { mergeEvents, visibleVersion } from "./playback";
describe("durable event playback", () => {
  it("deduplicates SSE reconnects, sorts sequences and restores version without actions", () => {
    const events = [
      {
        sequence: 3,
        eventType: "WORLD_STATE_CHANGED",
        payload: { version: 2 },
      },
      {
        sequence: 1,
        eventType: "WORLD_STATE_CHANGED",
        payload: { version: 1 },
      },
    ];
    const result = mergeEvents(events, [
      events[0],
      { sequence: 2, eventType: "AGENT_DECISION", payload: {} },
    ]);
    expect(result.map((e) => e.sequence)).toEqual([1, 2, 3]);
    expect(visibleVersion(result, 0)).toBe(0);
    expect(visibleVersion(result, 2)).toBe(1);
    expect(visibleVersion(result, 3)).toBe(2);
  });
});
