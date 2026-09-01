const dates = (value) => [value.startDate, value.currentlyWorking ? "Present" : value.endDate].filter(Boolean).join(" – ");

export default function ResumePreview({ content }) {
  if (!content) return <p className="text-slate-500">Generate a tailored resume to preview it.</p>;
  const sections = {
    summary: content.summary && <section><h2>Professional Summary</h2><p>{content.summary}</p></section>,
    skills: content.skills?.length > 0 && <section><h2>Skills</h2><p>{content.skills.join(" • ")}</p></section>,
    experience: content.experience?.length > 0 && <section><h2>Experience</h2>{content.experience.map((item, index) => <article key={`${item.organization}-${index}`}><h3>{item.title}{item.organization ? ` — ${item.organization}` : ""}</h3><p className="resume-meta">{dates(item)}</p><p>{item.description}</p>{item.technologies?.length > 0 && <p>Technologies: {item.technologies.join(", ")}</p>}</article>)}</section>,
    projects: content.projects?.length > 0 && <section><h2>Projects</h2>{content.projects.map((item, index) => <article key={`${item.name}-${index}`}><h3>{item.name}</h3><p>{item.description}</p>{item.technologies?.length > 0 && <p>Technologies: {item.technologies.join(", ")}</p>}</article>)}</section>,
    education: content.education?.length > 0 && <section><h2>Education</h2>{content.education.map((item, index) => <article key={`${item.institution}-${index}`}><h3>{[item.degree, item.institution].filter(Boolean).join(" — ")}</h3><p>{[item.fieldOfStudy, item.grade].filter(Boolean).join(" | ")}</p></article>)}</section>,
    certifications: content.certifications?.length > 0 && <section><h2>Certifications</h2>{content.certifications.map((item, index) => <p key={`${item.name}-${index}`}>{[item.name, item.issuer].filter(Boolean).join(" — ")}</p>)}</section>,
    links: content.links && <section><h2>Links</h2>{[content.links.linkedIn, content.links.github, content.links.portfolio, content.links.website, ...(content.links.other || [])].filter(Boolean).map((link) => <p key={link}>{link}</p>)}</section>,
  };
  return <article className="resume-preview mx-auto max-w-[800px] bg-white p-8 sm:p-12 text-slate-900 shadow-sm border border-slate-200 print:border-0 print:shadow-none" aria-label="ATS-friendly tailored resume preview">
    <header className="text-center border-b border-slate-300 pb-4"><h1 className="text-3xl font-bold">{content.fullName}</h1><p className="mt-2 text-sm">{[content.email, content.phone, content.location].filter(Boolean).join(" | ")}</p></header>
    {(content.sectionOrder || Object.keys(sections)).map((name) => sections[name] ? <div key={name} className="mt-6 [&_h2]:text-lg [&_h2]:font-bold [&_h2]:uppercase [&_h2]:tracking-wide [&_h2]:border-b [&_h2]:border-slate-300 [&_h2]:mb-2 [&_h3]:font-bold [&_article]:mb-4 [&_p]:whitespace-pre-wrap">{sections[name]}</div> : null)}
  </article>;
}
