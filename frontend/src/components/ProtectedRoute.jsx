import { Navigate } from "react-router-dom";
import { useAuth } from "../auth/auth-context";

export default function ProtectedRoute({ children, requiredRole }) {
  const { accessToken, role, initialized } = useAuth();
  if (!initialized) return null;
  if (!accessToken) return <Navigate to="/login" replace />;
  if (requiredRole && role !== requiredRole) {
    const destination = role === "ADMIN" ? "/admin/aggregation"
      : role === "RECRUITER" ? "/profile" : "/my-profile";
    return <Navigate to={destination} replace />;
  }
  return children;
}
