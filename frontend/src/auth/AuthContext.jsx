import { useEffect, useState } from "react";
import { getSession, subscribeAuth } from "./auth-store";
import { restoreSession } from "../services/user-service";
import { AuthContext } from "./auth-context";

export function AuthProvider({ children }) {
  const [session, setSession] = useState(getSession());
  useEffect(() => subscribeAuth(() => setSession(getSession())), []);
  useEffect(() => { restoreSession(); }, []);
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>;
}
