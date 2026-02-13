import { useState } from "react";
import { applyForJob } from "../services/application-service";

export default function ApplyJobModal({ isOpen, onClose, onSuccess, jobId, jobTitle }) {
  const [file, setFile] = useState(null);
  const [message, setMessage] = useState("");
  const [isUploading, setIsUploading] = useState(false);
  const [isDragging, setIsDragging] = useState(false);

  if (!isOpen) return null;

  const handleDragOver = (e) => { e.preventDefault(); setIsDragging(true); };
  const handleDragLeave = () => { setIsDragging(false); };
  const handleDrop = (e) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]);
  };

  const handleSubmit = async () => {
    if (!file) return;
    setIsUploading(true);
    try {
      await applyForJob(jobId, file);
      setMessage("Success! ✅");
      
      setTimeout(() => {
        setMessage("");
        setFile(null);
        onSuccess(); 
      }, 1500);

    } catch (error) {
      console.error(error);
      setMessage("Failed to apply. You might need to login. ❌");
    } finally {
      setIsUploading(false);
    }
  };
  

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-md relative overflow-hidden">
        <div className="p-5 border-b flex justify-between items-center">
          <h3 className="font-bold text-lg">Apply for {jobTitle}</h3>
          <button onClick={onClose} className="text-2xl text-gray-400 hover:text-gray-600">&times;</button>
        </div>

        <div className="p-6 space-y-4">
          <div
            className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition
              ${isDragging ? "border-blue-500 bg-blue-50" : "border-gray-300 hover:border-blue-400"}
              ${file ? "bg-green-50 border-green-500" : ""}`}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => document.getElementById("resumeInput").click()}
          >
            <input type="file" id="resumeInput" className="hidden" accept=".pdf,.doc,.docx" 
                   onChange={(e) => e.target.files[0] && setFile(e.target.files[0])} />
            
            {file ? (
              <p className="text-green-600 font-semibold">{file.name}</p>
            ) : (
              <div>
                <p className="text-4xl mb-2">📄</p>
                <p className="text-gray-600 font-medium">Click or Drag Resume here</p>
                <p className="text-xs text-gray-400 mt-1">PDF or DOCX (Max 5MB)</p>
              </div>
            )}
          </div>

          {message && <p className="text-center font-semibold text-sm">{message}</p>}

          <div className="flex gap-3">
            <button onClick={onClose} className="flex-1 py-2 border rounded-lg text-gray-600">Cancel</button>
            <button onClick={handleSubmit} disabled={!file || isUploading}
              className={`flex-1 py-2 rounded-lg text-white font-semibold ${!file || isUploading ? "bg-gray-400" : "bg-blue-600 hover:bg-blue-700"}`}>
              {isUploading ? "Uploading..." : "Submit"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}