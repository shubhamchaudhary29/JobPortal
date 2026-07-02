import { Navigate } from "react-router-dom";

/**
 * ProtectedRoute — Guards routes requiring authentication and/or a specific role.
 *
 * Usage:
 *   <ProtectedRoute>                          — any authenticated user
 *   <ProtectedRoute requiredRole="USER">      — candidates only
 *   <ProtectedRoute requiredRole="RECRUITER"> — recruiters only
 *
 * Redirect logic:
 *   - No token                → /login
 *   - Wrong role (RECRUITER trying USER route) → /profile
 *   - Wrong role (USER trying RECRUITER route) → /my-profile
 */
export default function ProtectedRoute({ children, requiredRole }) {
  const token = localStorage.getItem("token");
  const role  = localStorage.getItem("role");

  // Not logged in at all
  if (!token) {
    return <Navigate to="/login" replace />;
  }

  // Logged in but wrong role
  if (requiredRole && role !== requiredRole) {
    // Recruiter trying to access a candidate-only page → send to recruiter dashboard
    if (role === "RECRUITER") {
      return <Navigate to="/profile" replace />;
    }
    // Candidate trying to access a recruiter-only page → send to candidate dashboard
    return <Navigate to="/my-profile" replace />;
  }

  return children;
}
