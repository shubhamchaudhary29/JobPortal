import { Navigate } from "react-router-dom";
import { useAuth } from "../auth/auth-context";
import { restoreSession } from "../services/user-service";

export default function ProtectedRoute({ children, requiredRole }) {
  const { accessToken, role, initialized, unknown } = useAuth();
  if (!initialized) return null;
  if (!accessToken && unknown) {
    return (
      <div role="alert" className="mx-auto mt-16 max-w-md rounded-2xl border border-amber-200 bg-amber-50 px-6 py-8 text-center">
        <p className="font-medium text-slate-700">We couldn't confirm your session right now.</p>
        <button type="button" onClick={() => restoreSession()}
          className="mt-4 rounded-lg bg-indigo-600 px-5 py-2 text-sm font-semibold text-white hover:bg-indigo-700">
          Retry
        </button>
      </div>
    );
  }
  if (!accessToken) return <Navigate to="/login" replace />;
  if (requiredRole && role !== requiredRole) {
    const destination = role === "ADMIN" ? "/admin/aggregation"
      : role === "RECRUITER" ? "/profile" : "/my-profile";
    return <Navigate to={destination} replace />;
  }
  return children;
}
