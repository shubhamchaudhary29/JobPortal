import { Routes, Route } from "react-router-dom";

import Home from "../pages/Home";
import Jobs from "../pages/Jobs";
import JobDetails from "../pages/JobDetails";
import Login from "../pages/Login";
import Register from "../pages/Register";
import RecruiterLogin from "../pages/RecruiterLogin";
import Profile from "../pages/Profile";
import CreateJob from "../pages/CreateJob";
import MyApplications from "../pages/MyApplications";
import MyProfile from "../pages/MyProfile";
import ChatList from "../pages/ChatList";
import ChatRoom from "../pages/ChatRoom";
import AggregationAdmin from "../pages/AggregationAdmin";
import ProtectedRoute from "../components/ProtectedRoute";

export default function AppRoutes() {
  return (
    <Routes>
      {/* Public routes */}
      <Route path="/" element={<Home />} />
      <Route path="/jobs" element={<Jobs />} />
      <Route path="/jobs/:jobId" element={<JobDetails />} />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/recruiter-login" element={<RecruiterLogin />} />

      {/* Recruiter-only routes */}
      <Route
        path="/profile"
        element={
          <ProtectedRoute requiredRole="RECRUITER">
            <Profile />
          </ProtectedRoute>
        }
      />
      <Route
        path="/post-job"
        element={
          <ProtectedRoute requiredRole="RECRUITER">
            <CreateJob />
          </ProtectedRoute>
        }
      />

      {/* Candidate-only routes */}
      <Route
        path="/my-applications"
        element={
          <ProtectedRoute requiredRole="USER">
            <MyApplications />
          </ProtectedRoute>
        }
      />
      <Route
        path="/my-profile"
        element={
          <ProtectedRoute requiredRole="USER">
            <MyProfile />
          </ProtectedRoute>
        }
      />

      {/* Shared protected chat routes */}
      <Route
        path="/chat"
        element={
          <ProtectedRoute>
            <ChatList />
          </ProtectedRoute>
        }
      />
      <Route
        path="/chat/:roomId"
        element={
          <ProtectedRoute>
            <ChatRoom />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/aggregation"
        element={
          <ProtectedRoute requiredRole="ADMIN">
            <AggregationAdmin />
          </ProtectedRoute>
        }
      />
    </Routes>
  );
}
