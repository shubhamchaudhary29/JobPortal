import { useState } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";

export default function Home() {
  const [role, setRole] = useState("");
  const [location, setLocation] = useState("");
  const navigate = useNavigate();

  const handleSearch = (e) => {
    e.preventDefault();
    if (!role && !location) return;
    navigate(`/jobs?role=${role}&location=${location}`);
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      
      
      <main className="flex-1 flex items-center justify-center min-h-[calc(100vh-64px)] px-4 sm:px-6 lg:px-8 relative overflow-hidden">
        
        <div className="absolute top-[-10%] left-[-10%] w-96 h-96 bg-indigo-200 rounded-full mix-blend-multiply filter blur-3xl opacity-50"></div>
        <div className="absolute top-[20%] right-[-10%] w-96 h-96 bg-purple-200 rounded-full mix-blend-multiply filter blur-3xl opacity-50"></div>

        <div className="text-center max-w-4xl w-full relative z-10">
          <span className="inline-block py-1.5 px-4 rounded-full bg-indigo-50 text-indigo-700 text-sm font-bold mb-6 border border-indigo-100 shadow-sm">
            Over 10,000+ tech jobs available
          </span>
          <h1 className="text-5xl md:text-7xl font-extrabold text-slate-900 mb-6 tracking-tight">
            Find your next <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-600 to-purple-600">dream job.</span>
          </h1>
          <p className="text-lg md:text-xl text-slate-500 mb-10 max-w-2xl mx-auto leading-relaxed">
            Connect with top companies and startups looking for talent just like you. Your next big career move starts right here.
          </p>

          <form onSubmit={handleSearch} className="bg-white shadow-xl shadow-slate-200/50 rounded-2xl p-3 flex flex-col md:flex-row gap-2 items-center border border-slate-100 max-w-3xl mx-auto">
            <div className="flex-1 w-full relative">
              <span className="absolute left-4 top-1/2 transform -translate-y-1/2 text-xl">🔍</span>
              <input type="text" placeholder="Job title or keyword" value={role} onChange={(e) => setRole(e.target.value)}
                className="w-full pl-12 pr-4 py-4 bg-transparent focus:outline-none text-slate-900 font-medium placeholder-slate-400" />
            </div>
            
            <div className="hidden md:block w-px h-10 bg-slate-200"></div>

            <div className="flex-1 w-full relative">
              <span className="absolute left-4 top-1/2 transform -translate-y-1/2 text-xl">📍</span>
              <input type="text" placeholder="City or remote" value={location} onChange={(e) => setLocation(e.target.value)}
                className="w-full pl-12 pr-4 py-4 bg-transparent focus:outline-none text-slate-900 font-medium placeholder-slate-400" />
            </div>

            <button type="submit" className="w-full md:w-auto bg-indigo-600 hover:bg-indigo-700 text-white font-bold px-8 py-4 rounded-xl transition-all shadow-md">
              Search
            </button>
          </form>
        </div>
      </main>

      <Footer />
    </div>
  );
}