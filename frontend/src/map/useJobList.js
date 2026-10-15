import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../services/map-service";

function initialState(key, enabled) {
  return { key, status: enabled ? "loading" : "idle", items: [], page: 0, hasNext: false, totalHint: null, extra: null,
    error: null, failedPage: null };
}

function append(items, content) {
  const seen = new Set(items.map((item) => item.id));
  return [...items, ...content.filter((item) => !seen.has(item.id))];
}

/**
 * Slice-paged job list. {@code fetcher({page, signal})} resolves to {@code {content, hasNext, totalHint, extra?}};
 * {@code extra} from page 0 is kept as-is (e.g. nearby places). {@code deps} must be JSON-serialisable: when they
 * change, the in-flight request is aborted and the list restarts at page 0; late responses for old deps are
 * ignored. A null fetcher keeps the list idle.
 * status: "idle" | "loading" | "ready" | "loadingMore" | "error".
 */
export function useJobList(fetcher, deps) {
  const enabled = typeof fetcher === "function";
  const key = `${enabled ? 1 : 0}:${JSON.stringify(deps)}`;
  const [stored, setState] = useState(() => initialState(key, enabled));
  const fetcherRef = useRef(fetcher);
  const controllerRef = useRef(null);

  // Reset during render when deps change (React's documented pattern), so stale items never flash.
  let state = stored;
  if (stored.key !== key) {
    state = initialState(key, enabled);
    setState(state);
  }

  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  const request = useCallback((page, requestKey) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    new Promise((resolve) => resolve(fetcherRef.current({ page, signal: controller.signal })))
      .then((slice) => {
        if (controller.signal.aborted) return;
        const content = Array.isArray(slice?.content) ? slice.content : [];
        setState((previous) => previous.key !== requestKey ? previous : {
          ...previous,
          status: "ready",
          error: null,
          failedPage: null,
          page,
          items: page === 0 ? content : append(previous.items, content),
          hasNext: Boolean(slice?.hasNext),
          totalHint: page === 0 ? slice?.totalHint ?? null : previous.totalHint,
          extra: page === 0 ? slice?.extra ?? null : previous.extra,
        });
      })
      .catch((error) => {
        if (controller.signal.aborted || isAbortError(error)) return;
        setState((previous) => previous.key !== requestKey ? previous
          : { ...previous, status: "error", error, failedPage: page });
      });
  }, []);

  useEffect(() => {
    if (!enabled) return undefined;
    request(0, key);
    return () => controllerRef.current?.abort();
  }, [enabled, key, request]);

  const loadMore = useCallback(() => {
    if (state.status !== "ready" || !state.hasNext) return;
    setState((previous) => ({ ...previous, status: "loadingMore" }));
    request(state.page + 1, state.key);
  }, [request, state.hasNext, state.key, state.page, state.status]);

  const retry = useCallback(() => {
    if (state.status !== "error") return;
    const page = state.failedPage ?? 0;
    setState((previous) => ({ ...previous, status: page === 0 ? "loading" : "loadingMore", error: null }));
    request(page, state.key);
  }, [request, state.failedPage, state.key, state.status]);

  return {
    items: state.items,
    status: state.status,
    hasNext: state.hasNext,
    totalHint: state.totalHint,
    extra: state.extra,
    error: state.error,
    loadMore,
    retry,
  };
}
