import { useCallback, useEffect, useState } from "react";
import { getMapSnapshot, isAbortError } from "../services/map-service";

export const MAX_SNAPSHOT_RETRIES = 3;

/**
 * Loads the map snapshot once per mount. While the backend is still building it (503), retries after its
 * Retry-After, up to {@link MAX_SNAPSHOT_RETRIES} times; then status is "error" and {@code retry()} starts over.
 * status: "loading" | "retrying" | "ready" | "error".
 */
export function useMapSnapshot() {
  const [state, setState] = useState({ snapshot: null, status: "loading" });
  const [attemptKey, setAttemptKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let timer = null;
    let retries = 0;
    const load = () => {
      getMapSnapshot({ signal: controller.signal })
        .then((snapshot) => setState({ snapshot, status: "ready" }))
        .catch((error) => {
          if (controller.signal.aborted || isAbortError(error)) return;
          if (error?.kind === "unavailable" && retries < MAX_SNAPSHOT_RETRIES) {
            retries += 1;
            setState({ snapshot: null, status: "retrying" });
            timer = setTimeout(load, error.retryAfterSeconds * 1000);
            return;
          }
          setState({ snapshot: null, status: "error" });
        });
    };
    load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [attemptKey]);

  const retry = useCallback(() => {
    setState({ snapshot: null, status: "loading" });
    setAttemptKey((key) => key + 1);
  }, []);

  return { ...state, retry };
}
