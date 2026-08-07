import { createContext, useContext } from "react";
import { getSession } from "./auth-store";

export const AuthContext = createContext(getSession());
export const useAuth = () => useContext(AuthContext);
