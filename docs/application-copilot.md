# Application Copilot v1.3

## Purpose and score semantics

Application Copilot turns a job match into a candidate-owned preparation package: readiness analysis, keyword evidence, a tailoring plan, a versioned resume, a versioned cover letter, and personal application tracking.

The three candidate scores are intentionally separate:

- **Resume Quality Score** evaluates the general completeness and quality signals of the canonical profile/resume.
- **Job Match Score** evaluates candidate-to-job compatibility using Phase 2 job features.
- **Application Readiness** evaluates whether the current documented profile is ready to present for one job. It weighs required/preferred coverage, strength of concrete evidence, experience and role compatibility, resume completeness, and professional links.

Application Readiness is a deterministic preparation aid. It is **not** an official ATS score, recruiter judgment, interview probability, or hiring probability. Its version is `application-readiness-1.3.0`; tailoring uses `resume-tailoring-1.3.0`.

## Evidence and truthfulness guarantee

All generated candidate-specific content comes from the structured profile. Evidence is classified by source field (skill list, summary, experience, project, education, or certification) and carries a bounded source excerpt internally. Public responses do not expose persistence ownership IDs.

Keyword evidence levels are:

- **STRONG**: work experience associates the skill with the candidate.
- **SUPPORTED**: a project, education item, or certification supports it.
- **UNDERREPRESENTED**: the skill is listed or mentioned but has no concrete work/project support.
- **MISSING**: no candidate evidence contains the normalized skill.

Importance is reused from Phase 2 job extraction: `REQUIRED`, `PREFERRED`, then `CONTEXTUAL`. Job descriptions are not reparsed independently by the Copilot.

Generation never adds a missing skill, employer, project, degree, certification, date, responsibility, result, technology, duration, or metric. Resume generation performs evidence-based reordering and preserves source descriptions verbatim. Cover letters quote bounded profile evidence and mention only present keywords. Existing metrics such as `20%` remain unchanged; no number is synthesized. Candidates can edit their own drafts, but generation never silently changes the canonical profile.

No OpenAI, Anthropic, Gemini, local model, API key, paid API, or external company research is required.

## Readiness and tailoring

Readiness weights available evidence dynamically:

| Component | Configured contribution |
| --- | ---: |
| Required skill coverage | 30 |
| Preferred skill coverage | 10 |
| Evidence strength | 30 |
| Experience compatibility | 15 |
| Role relevance | 10 |
| Resume completeness | 10 |
| Professional links | 5 |

Unavailable job-specific components are excluded. Required skills with only list-level evidence receive partial—not full—coverage. A missing required skill caps readiness below `NEARLY_READY`. Sparse profiles or unusable job descriptions produce `LOW_DATA`. Inactive jobs produce `INACTIVE` and score zero while retaining historical data.

The tailoring plan can emphasize/reorder evidenced skills, prioritize relevant projects/experience, recommend a supported summary focus, flag underrepresented skills, and disclose missing requirements. Missing requirements explicitly say they will not be inserted.

## Resume and cover-letter versions

Tailored resumes are separate documents in `tailored_resume_versions`; cover letters use `cover_letter_versions`. Each generation creates a new version and never overwrites an earlier version. A maximum of 25 resume and 25 cover-letter versions per candidate/job bounds storage.

Resume snapshots include contact/profile content, job metadata, matching/tailoring versions, keyword analysis, actions, timestamps, and the base profile update time. Editing permits summary text, ordering/selection of already-present skills, experience/project descriptions, and section ordering. Ownership, evidence metadata, employer/project identity, dates, and technologies are server-controlled. Skills cannot be injected through the tailored-resume edit API.

If the canonical profile changes later—or the stored base resume is deleted—the historical snapshot remains unchanged and is returned as `OUTDATED`. Regeneration creates a new current snapshot. A candidate with a manually entered profile does not need an uploaded resume.

DOCX export uses Apache POI in memory. Output is selectable Office Open XML with a single column and standard headings. The endpoint is private, sends `Cache-Control: no-store`, and uses a sanitized attachment filename. The original uploaded resume is never altered.

## Application workspace and statuses

`candidate_job_workspaces` contains one candidate-owned record per job. It connects compact job/match/readiness/keyword snapshots with the selected resume and cover-letter versions, personal stage, private notes, external-application state, applied/follow-up timestamps, and audit timestamps.

Personal stages are `SAVED`, `PREPARING`, `APPLIED`, `OA`, `INTERVIEW`, `OFFER`, `REJECTED`, and `WITHDRAWN`. These do not replace recruiter-controlled `ApplicationStatus`. When an in-platform application exists, its recruiter status is displayed separately and remains authoritative; the personal tracker is synchronized to at least `APPLIED`. External outcomes are candidate-entered and are never inferred by the portal.

Inactive or deleted jobs retain a compact snapshot (title, company, location, source, employment type, and safe application URL). History stays accessible and shows `No longer active`; new resume/cover-letter generation is blocked. Saved jobs are unique per candidate/job and an unused `SAVED` item can be removed.

Analytics count personal stages. Rates use all stages that represent an application as their denominator; response, interview, and offer numerators include only stages that actually reached those outcomes. Zero denominators return no percentage, and small samples display a warning.

## Candidate API

- `GET /api/v1/jobs/{jobId}/application-readiness`
- `GET /api/v1/jobs/{jobId}/tailoring-plan`
- `POST, GET /api/v1/jobs/{jobId}/resume-versions`
- `GET, PUT /api/v1/resume-versions/{versionId}`
- `GET /api/v1/resume-versions/{versionId}/export`
- `POST, GET /api/v1/jobs/{jobId}/cover-letters`
- `GET, PUT /api/v1/cover-letters/{versionId}`
- `GET /api/v1/application-workspace`
- `GET /api/v1/application-workspace/analytics`
- `GET, PUT, DELETE /api/v1/application-workspace/{jobId}`

Identity always comes from the access token. No route accepts an ownership user ID. Collections are paginated and bounded; workspace search is regex-quoted and runs in MongoDB. Viewing analysis does not create a version or workspace record.

## Privacy, security, and observability

Spring Security restricts all Copilot routes to `USER`. Repositories resolve private versions using both version ID and authenticated user ID; cross-candidate access returns privacy-preserving `404`. Recruiters and administrators do not receive implicit draft access. Requests cannot control storage paths, ownership, job snapshots, evidence IDs, score versions, or audit metadata.

Summaries, descriptions, notes, cover letters, URLs, pages, and list sizes are bounded. Unknown JSON fields are rejected by the global Jackson policy. Logs contain no resume/letter text or candidate identifiers. Metrics are low-cardinality counters only: readiness calculations, tailored resumes created, and cover letters created.

## Limitations

Keyword extraction and evidence classification are deterministic heuristics over the current normalization vocabulary. They cannot infer unlisted skills, understand every unusual prose format, validate candidate-authored edits as independently true, research a company, or predict recruiter decisions. Application Copilot does not submit applications, scrape forms, send email, answer screening questions, or provide interview assistance.
