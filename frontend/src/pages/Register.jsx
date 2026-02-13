import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { signUpUser } from "../services/user-service";

export default function Register() {
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [formData, setFormData] = useState({
    fullName: "",
    email: "",
    password: "",
    role: "USER",
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
      setError("Registration failed. Please try a different email." + err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4">
      <div className="bg-white shadow-xl shadow-slate-200/50 rounded-3xl p-8 sm:p-10 max-w-md w-full border border-slate-100">
        
        <div className="text-center mb-8">
          <h2 className="text-3xl font-extrabold text-slate-900">Join JobPortal</h2>
          <p className="text-slate-500 mt-2 font-medium">Create an account to start applying 🚀</p>
        </div>

        {error && (
          <div className="mb-4 bg-red-50 border border-red-100 text-red-600 px-4 py-3 rounded-xl text-sm font-medium text-center">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Full Name</label>
            <input
              type="text" name="fullName" value={formData.fullName} onChange={handleChange} required
              placeholder="e.g. Jane Doe"
              className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
            />
          </div>

          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Email Address</label>
            <input
              type="email" name="email" value={formData.email} onChange={handleChange} required
              placeholder="you@example.com"
              className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
            />
          </div>

          <div>
            <label className="text-sm font-bold text-slate-700 block mb-1.5">Password</label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"} name="password" value={formData.password} onChange={handleChange} required
                placeholder="Create a strong password"
                className="w-full px-4 py-3 bg-slate-50 border border-slate-200 rounded-xl focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
              />
              <button
                type="button" onClick={() => setShowPassword(!showPassword)}
                className="absolute right-4 top-1/2 transform -translate-y-1/2 text-sm font-semibold text-slate-500 hover:text-indigo-600 transition-colors"
              >
                {showPassword ? "Hide" : "Show"}
              </button>
            </div>
          </div>

          <button
            type="submit" disabled={loading}
            className="w-full bg-indigo-600 text-white py-3.5 rounded-xl font-bold hover:bg-indigo-700 hover:shadow-lg hover:shadow-indigo-200 transition-all disabled:opacity-70"
          >
            {loading ? "Creating account..." : "Create Account"}
          </button>
        </form>

        <div className="mt-6 flex items-center justify-between">
          <span className="border-b border-slate-200 w-1/5 lg:w-1/4"></span>
          <span className="text-xs text-center text-slate-500 uppercase font-bold tracking-wider">or</span>
          <span className="border-b border-slate-200 w-1/5 lg:w-1/4"></span>
        </div>

        <Link to="/recruiter-login" className="mt-6 w-full border-2 border-slate-100 text-slate-700 py-3 rounded-xl flex justify-center items-center gap-2 hover:bg-slate-50 font-bold transition-colors">
          💼 Register as a Recruiter
        </Link>

        <p className="text-sm text-center text-slate-600 mt-8 font-medium">
          Already have an account? <Link to="/login" className="text-indigo-600 font-bold hover:underline">Log in</Link>
        </p>
      </div>
    </div>
  );
}