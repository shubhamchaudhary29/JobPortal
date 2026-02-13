import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { signUpUser } from "../services/user-service";

export default function RecruiterLogin() {
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [formData, setFormData] = useState({
    fullName: "",
    email: "",
    password: "",
    role: "RECRUITER", // Hardcoded for Recruiter
  });

  function handleChange(e) {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError("");

    try {
      await signUpUser(formData);
      navigate("/login");
    } catch (err) {
      console.error(err);
      setError("Registration failed. Email might already be in use.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4">
      <div className="bg-white shadow-xl shadow-slate-200/50 rounded-3xl p-8 sm:p-10 max-w-md w-full border border-slate-100">
        
        <div className="text-center mb-8">
          <div className="w-12 h-12 bg-indigo-100 rounded-full flex items-center justify-center mx-auto mb-4 text-2xl">💼</div>
          <h2 className="text-3xl font-extrabold text-slate-900">Hire Top Talent</h2>
          <p className="text-slate-500 mt-2 font-medium">Create a recruiter account to post jobs.</p>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-100 text-red-600 px-4 py-3 rounded-xl text-sm font-medium text-center">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Company / Full Name</label>
            <input
              type="text" name="fullName" value={formData.fullName} onChange={handleChange} required
              placeholder="e.g. TechCorp HR"
              className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all"
            />
          </div>

          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Work Email Address</label>
            <input
              type="email" name="email" value={formData.email} onChange={handleChange} required
              placeholder="hr@company.com"
              className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all"
            />
          </div>

          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Password</label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"} name="password" value={formData.password} onChange={handleChange} required
                placeholder="Create a strong password"
                className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all"
              />
              <button
                type="button" onClick={() => setShowPassword(!showPassword)}
                className="absolute right-4 top-1/2 transform -translate-y-1/2 text-sm font-semibold text-slate-500 hover:text-indigo-600"
              >
                {showPassword ? "Hide" : "Show"}
              </button>
            </div>
          </div>

          <button
            type="submit" disabled={loading}
            className="w-full bg-slate-900 text-white py-3.5 rounded-xl font-bold hover:bg-indigo-600 hover:shadow-lg hover:shadow-indigo-200 transition-all disabled:opacity-70"
          >
            {loading ? "Registering..." : "Register as Recruiter"}
          </button>
        </form>

        <div className="mt-6 flex items-center justify-between">
          <span className="border-b border-slate-200 w-1/5 lg:w-1/4"></span>
          <span className="text-xs text-center text-slate-500 uppercase font-bold tracking-wider">or</span>
          <span className="border-b border-slate-200 w-1/5 lg:w-1/4"></span>
        </div>

        <Link to="/register" className="mt-6 w-full border-2 border-slate-100 text-slate-700 py-3 rounded-xl flex justify-center items-center gap-2 hover:bg-slate-50 font-bold transition-colors">
          🧑‍💻 Register as a Candidate
        </Link>

        <p className="text-sm text-center text-slate-600 mt-8 font-medium">
          Already have an account? <Link to="/login" className="text-indigo-600 font-bold hover:underline">Log in</Link>
        </p>
      </div>
    </div>
  );
}