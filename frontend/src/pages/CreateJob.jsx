import { useState } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import { createJob } from "../services/job-service";

export default function CreateJob() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  
  const [formData, setFormData] = useState({
    title: "", company: "", location: "", salary: "", experience: "", description: "",
  });

  const handleChange = (e) => setFormData({ ...formData, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true); setError("");

    try {
      const payload = {
        ...formData,
        salary: Number(formData.salary),
        experience: Number(formData.experience)
      };
      await createJob(payload);
      navigate("/profile"); 
    } catch (err) {
      console.error(err);
      setError("Failed to create job. Make sure you are logged in as a Recruiter.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <Header />

      <main className="max-w-4xl mx-auto px-4 py-12">
        <div className="bg-white rounded-3xl shadow-sm border border-slate-200 overflow-hidden">
          
          <div className="bg-slate-900 p-8 sm:p-10 text-white">
            <h1 className="text-3xl font-extrabold mb-2">Post a New Opportunity</h1>
            <p className="text-slate-400 font-medium">Fill out the details below to reach thousands of candidates.</p>
          </div>

          <form onSubmit={handleSubmit} className="p-8 sm:p-10 space-y-8">
            {error && (
              <div className="bg-red-50 text-red-600 p-4 rounded-xl text-sm font-medium border border-red-100">
                ⚠️ {error}
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
              <div>
                <label className="block text-sm font-bold text-slate-700 mb-2">Job Title</label>
                <input required type="text" name="title" placeholder="e.g. Senior Frontend Developer" value={formData.title} onChange={handleChange}
                  className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all" />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-2">Company Name</label>
                <input required type="text" name="company" placeholder="e.g. TechCorp Inc." value={formData.company} onChange={handleChange}
                  className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all" />
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
              <div>
                <label className="block text-sm font-bold text-slate-700 mb-2">Location</label>
                <input required type="text" name="location" placeholder="e.g. Remote / Bangalore" value={formData.location} onChange={handleChange}
                  className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all" />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-2">Annual Salary (₹)</label>
                <input required type="number" name="salary" placeholder="e.g. 1200000" value={formData.salary} onChange={handleChange}
                  className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all" />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-2">Experience Required (Years)</label>
                <input required type="number" name="experience" placeholder="e.g. 3" value={formData.experience} onChange={handleChange}
                  className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all" />
              </div>
            </div>

            <div>
              <label className="block text-sm font-bold text-slate-700 mb-2">Job Description</label>
              <textarea required rows="6" name="description" placeholder="Describe the role, responsibilities, and ideal candidate..." value={formData.description} onChange={handleChange}
                className="w-full bg-slate-50 px-4 py-3 rounded-xl border border-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-500 outline-none transition-all"></textarea>
            </div>

            <div className="flex flex-col-reverse sm:flex-row justify-end gap-4 pt-6 border-t border-slate-100">
              <button type="button" onClick={() => navigate("/profile")}
                className="px-8 py-3.5 rounded-xl text-slate-600 font-bold hover:bg-slate-100 transition-colors">
                Cancel
              </button>
              <button type="submit" disabled={loading}
                className="px-8 py-3.5 bg-indigo-600 text-white rounded-xl font-bold hover:bg-indigo-700 shadow-lg shadow-indigo-200 transition-all disabled:opacity-70">
                {loading ? "Publishing Job..." : "Publish Job Post"}
              </button>
            </div>
          </form>
        </div>
      </main>
    </div>
  );
}