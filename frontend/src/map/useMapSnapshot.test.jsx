// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const service = vi.hoisted(() => ({ getMapSnapshot: vi.fn() }));
vi.mock("../services/map-service", async (original) => ({ ...(await original()), getMapSnapshot: service.getMapSnapshot }));

import { MAX_SNAPSHOT_RETRIES, useMapSnapshot } from "./useMapSnapshot";

const unavailable = { kind: "unavailable", retryAfterSeconds: 10 };
const snapshot = { placedJobs: 3, places: [], unplaced: {} };

describe("useMapSnapshot", () => {
  beforeEach(() => service.getMapSnapshot.mockReset());
  afterEach(() => vi.useRealTimers());

  it("loads the snapshot once", async () => {
    service.getMapSnapshot.mockResolvedValue(snapshot);
    const { result } = renderHook(() => useMapSnapshot());
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.snapshot).toBe(snapshot);
    expect(service.getMapSnapshot).toHaveBeenCalledTimes(1);
  });

  it("retries after Retry-After while the snapshot is being built, then gives up with a manual retry", async () => {
    vi.useFakeTimers();
    service.getMapSnapshot.mockRejectedValue(unavailable);
    const { result } = renderHook(() => useMapSnapshot());
    await act(async () => {});
    expect(result.current.status).toBe("retrying");

    for (let retry = 1; retry <= MAX_SNAPSHOT_RETRIES; retry += 1) {
      await act(async () => vi.advanceTimersByTime(9999));
      expect(service.getMapSnapshot).toHaveBeenCalledTimes(retry);
      await act(async () => vi.advanceTimersByTime(1));
      expect(service.getMapSnapshot).toHaveBeenCalledTimes(retry + 1);
    }
    expect(result.current.status).toBe("error");

    service.getMapSnapshot.mockResolvedValue(snapshot);
    await act(async () => result.current.retry());
    expect(result.current.status).toBe("ready");
  });
});
