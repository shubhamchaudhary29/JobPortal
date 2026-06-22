import { Routes, Route } from "react-router-dom";

import Home from "../pages/Home";
import Jobs from "../pages/Jobs";
import JobDetails from "../pages/JobDetails";
import Login from "../pages/Login";
import Register from "../pages/Register";
import RecruiterLogin from "../pages/RecruiterLogin";
import Profile from "../pages/Profile";
import CreateJob from "../pages/CreateJob";

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/jobs" element={<Jobs />} />
      <Route path="/jobs/:jobId" element={<JobDetails />} />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/recruiter-login" element={<RecruiterLogin />} />
      <Route path="/profile" element={<Profile />} />
      <Route path="/post-job" element={<CreateJob />} />
    </Routes>
  );
}
