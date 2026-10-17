import { useEffect, useSyncExternalStore } from "react";
import { getSession, subscribeAuth } from "./auth-store";
import { restoreSession } from "../services/user-service";
import { AuthContext } from "./auth-context";

export function AuthProvider({ children }) {
  // useSyncExternalStore re-reads the store after subscribing, so a session set between the first render and the
  // subscription (for example a token another tab broadcast while this one was loading) is never missed.
  const session = useSyncExternalStore(subscribeAuth, getSession);
  useEffect(() => { restoreSession(); }, []);
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>;
}
