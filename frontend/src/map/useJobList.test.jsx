// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useJobList } from "./useJobList";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

const jobs = (...ids) => ids.map((id) => ({ id, title: `Job ${id}` }));

describe("useJobList", () => {
  it("loads the first page with its totalHint and extra data", async () => {
    const fetcher = vi.fn().mockResolvedValue({ content: jobs("a", "b"), hasNext: true, totalHint: 40, extra: { places: [1] } });
    const { result } = renderHook(() => useJobList(fetcher, ["place-1"]));

    expect(result.current.status).toBe("loading");
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.items.map((job) => job.id)).toEqual(["a", "b"]);
    expect(result.current.hasNext).toBe(true);
    expect(result.current.totalHint).toBe(40);
    expect(result.current.extra).toEqual({ places: [1] });
    expect(fetcher).toHaveBeenCalledWith({ page: 0, signal: expect.any(AbortSignal) });
  });

  it("appends the next page on loadMore and drops duplicates", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce({ content: jobs("a", "b"), hasNext: true, totalHint: 3 })
      .mockResolvedValueOnce({ content: jobs("b", "c"), hasNext: false, totalHint: 3 });
    const { result } = renderHook(() => useJobList(fetcher, ["place-1"]));
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.loadMore());
    expect(result.current.status).toBe("loadingMore");
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(fetcher).toHaveBeenLastCalledWith({ page: 1, signal: expect.any(AbortSignal) });
    expect(result.current.items.map((job) => job.id)).toEqual(["a", "b", "c"]);
    expect(result.current.hasNext).toBe(false);
  });

  it("aborts the in-flight request when deps change and ignores its late response", async () => {
    const first = deferred();
    const second = deferred();
    const fetcher = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(({ q }) => useJobList(fetcher, [q]), { initialProps: { q: "java" } });
    const firstSignal = fetcher.mock.calls[0][0].signal;

    rerender({ q: "python" });
    expect(firstSignal.aborted).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(2);

    await act(async () => second.resolve({ content: jobs("py"), hasNext: false, totalHint: null }));
    await act(async () => first.resolve({ content: jobs("stale"), hasNext: true, totalHint: 99 }));
    expect(result.current.items.map((job) => job.id)).toEqual(["py"]);
    expect(result.current.totalHint).toBeNull();
  });

  it("reports errors, ignores aborts and retries the failed page", async () => {
    const fetcher = vi.fn()
      .mockRejectedValueOnce({ kind: "error" })
      .mockResolvedValueOnce({ content: jobs("a"), hasNext: false });
    const { result } = renderHook(() => useJobList(fetcher, ["x"]));
    await waitFor(() => expect(result.current.status).toBe("error"));
    expect(result.current.error).toEqual({ kind: "error" });

    act(() => result.current.retry());
    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(fetcher).toHaveBeenLastCalledWith({ page: 0, signal: expect.any(AbortSignal) });
    expect(result.current.items).toHaveLength(1);
  });

  it("stays idle without a fetcher", () => {
    const { result } = renderHook(() => useJobList(null, ["x"]));
    expect(result.current.status).toBe("idle");
    expect(result.current.items).toEqual([]);
  });
});
