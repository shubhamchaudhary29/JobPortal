import { Link } from "react-router-dom";

export default function Footer() {
  return (
    <footer className="bg-slate-900 border-t border-slate-800 pt-16 pb-8 px-6 md:px-12 w-full mt-auto">
      <div className="max-w-7xl mx-auto flex flex-col md:flex-row justify-between gap-12">
        
        <div className="flex flex-col justify-between min-h-[140px]">
          <Link to="/" className="flex-shrink-0 flex items-center gap-2 group">
            <div className="w-9 h-9 bg-indigo-600 rounded-xl flex items-center justify-center shadow-lg shadow-indigo-500/30 group-hover:scale-105 transition-transform">
              <span className="text-white font-bold text-xl">J</span>
            </div>
            <span className="text-2xl font-extrabold text-white tracking-tight">JobPortal</span>
          </Link>
          
          <div className="text-slate-400 text-sm font-medium mt-auto">
            JobPortal © {new Date().getFullYear()}
          </div>
        </div>

        <div className="flex-1 flex md:justify-center">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-x-12 gap-y-10 lg:gap-x-20">
            
            <div className="flex flex-col gap-3">
              <h3 className="text-lg font-bold text-white mb-2">About</h3>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Company</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Team</Link>
            </div>

            <div className="flex flex-col gap-3">
              <h3 className="text-lg font-bold text-white mb-2">Discover</h3>
              <Link to="/jobs" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Jobs</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Talent Solutions</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Healthcare</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Search</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Staffing</Link>
            </div>

            <div className="flex flex-col gap-3">
              <h3 className="text-lg font-bold text-white mb-2">Legal</h3>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Privacy & Cookies</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Terms & Conditions</Link>
            </div>

            <div className="flex flex-col gap-3">
              <h3 className="text-lg font-bold text-white mb-2">Contact</h3>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Recruit Talent</Link>
              <Link to="#" className="text-slate-400 hover:text-indigo-400 font-medium transition-colors">Find Your Dream Job</Link>
            </div>
            
          </div>
        </div>

        <div className="flex flex-row md:flex-col gap-5 items-center justify-start md:pt-1">
          <a href="#" className="text-slate-400 hover:text-indigo-400 transition-colors">
            <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
               <path d="M7.8 2h8.4C19.4 2 22 4.6 22 7.8v8.4a5.8 5.8 0 0 1-5.8 5.8H7.8C4.6 22 2 19.4 2 16.2V7.8A5.8 5.8 0 0 1 7.8 2m-.2 2A3.6 3.6 0 0 0 4 7.6v8.8C4 18.39 5.61 20 7.6 20h8.8a3.6 3.6 0 0 0 3.6-3.6V7.6C20 5.61 18.39 4 16.4 4H7.6m9.65 1.5a1.25 1.25 0 0 1 1.25 1.25A1.25 1.25 0 0 1 17.25 8 1.25 1.25 0 0 1 16 6.75a1.25 1.25 0 0 1 1.25-1.25M12 7a5 5 0 0 1 5 5 5 5 0 0 1-5 5 5 5 0 0 1-5-5 5 5 0 0 1 5-5m0 2a3 3 0 0 0-3 3 3 3 0 0 0 3 3 3 3 0 0 0 3-3 3 3 0 0 0-3-3z"/>
            </svg>
          </a>
          
          <a href="#" className="text-slate-400 hover:text-indigo-400 transition-colors">
            <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M22 12c0-5.52-4.48-10-10-10S2 6.48 2 12c0 4.84 3.44 8.87 8 9.8V15H8v-3h2V9.5C10 7.57 11.57 6 13.5 6H16v3h-2c-.55 0-1 .45-1 1v2h3v3h-3v6.95c5.05-.5 9-4.76 9-9.95z" />
            </svg>
          </a>
          
          <a href="#" className="text-slate-400 hover:text-indigo-400 transition-colors">
            <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M19 3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h14m-.5 15.5v-5.3a3.26 3.26 0 0 0-3.26-3.26c-.85 0-1.84.52-2.32 1.3v-1.11h-2.79v8.37h2.79v-4.93c0-.77.62-1.4 1.39-1.4a1.4 1.4 0 0 1 1.4 1.4v4.93h2.79M6.88 8.56a1.68 1.68 0 0 0 1.68-1.68c0-.93-.75-1.69-1.68-1.69a1.69 1.69 0 0 0-1.69 1.69c0 .93.76 1.68 1.69 1.68m1.39 9.94v-8.37H5.5v8.37h2.77z"/>
            </svg>
          </a>
        </div>

      </div>
    </footer>
  );
}