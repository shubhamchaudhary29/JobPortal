# Candidate Intelligence (Phase 1 / v1.1)

## Overview

Candidate Intelligence adds a self-service flow for authenticated candidates:

```text
Resume → secure upload → text extraction → section parsing → normalized profile
       → candidate review/editing → heuristic quality analysis
```

It does not perform job matching, semantic ranking, resume tailoring, or generate an ATS match percentage.

## Supported documents and storage

- PDF and DOCX are enabled by default.
- Files are limited to 5 MiB by default. Extension, MIME type, file signature, empty content, and parser readability are checked.
- Client filenames are never used as storage paths. Files receive random UUID names below `${UPLOAD_DIR}/candidate-profiles`.
- Candidate resumes share the existing private `backend-uploads` Docker volume and survive backend/container restarts.
- Resumes are never served statically. Download is authenticated, candidate-only, `no-store`, and ownership is derived from the token.
- Replacement is staged and parsed before the old file is removed. Re-uploading the same SHA-256 content is idempotent.
- Deleting a resume removes its stored file and parsing metadata but deliberately retains the editable structured profile to avoid data loss.

The application-resume workflow remains PDF-only and backward compatible.

## Parsing and review

PDFBox extracts PDF text and Apache POI extracts DOCX paragraphs and tables. Section aliases are case-insensitive and cover common
summary, skills, education, experience, project, and certification headings. Separate components extract contacts, links, skills,
education, experience, projects, and certifications. Raw resume text is not persisted or logged.

Status values are:

- `NOT_UPLOADED`
- `PROCESSING`
- `PARSED`
- `PARTIALLY_PARSED`
- `FAILED`
- `OCR_REQUIRED`

Image-only or nearly text-free documents become `OCR_REQUIRED`; OCR is intentionally not provided. Corrupt or encrypted documents
produce controlled errors. Warnings expose uncertain or missing fields. The authenticated account email is referenced rather than
duplicated, and a detected conflicting email never overwrites it.

## Structured profile

MongoDB collection `candidate_profiles` stores one document per user under the unique `userId` index. It contains phone, location,
summary, normalized skills, education, experience, projects, certifications, professional links, job-preference foundations,
resume metadata, parser warnings, extraction signals, and created/updated timestamps. Full name and email come from the canonical
user record.

Skill aliases include the required Java/Spring/JavaScript/TypeScript/frontend/backend/database/cloud/DevOps/messaging set. Aliases are
extendable and de-duplicated case-insensitively. Java and JavaScript are deliberately distinct.

## Resume Quality Score

The `Resume Quality Score` is an internal deterministic heuristic from 0 to 100. It checks profile completeness, contact details,
skills, education, experience or projects, description length, measurable impact signals, repetition, work-sample links, extracted
text length, page count, and suspicious character ratios. Each issue includes severity, category, a message, and a recommendation.

It is not an official ATS score and is not a prediction of hiring outcomes.

## API

All endpoints require a bearer token with role `USER`; none accepts a user ID.

| Method | Route | Behavior |
| --- | --- | --- |
| `GET` | `/api/v1/candidate-profile` | Get or initialize the current profile and quality report. |
| `PUT` | `/api/v1/candidate-profile` | Replace candidate-editable fields; server fields and ownership cannot be assigned. |
| `POST` | `/api/v1/candidate-profile/resume` | Upload and synchronously parse multipart field `file`. |
| `GET` | `/api/v1/candidate-profile/resume/status` | Get parser state, warnings, and quality report. |
| `GET` | `/api/v1/candidate-profile/resume` | Download the current private resume. |
| `POST` | `/api/v1/candidate-profile/resume/reparse` | Reparse the stored resume using the current parser version. |
| `DELETE` | `/api/v1/candidate-profile/resume` | Delete stored resume bytes and metadata; retain structured fields. |

Requests and nested collections are size-bounded and validated. Unknown JSON fields are rejected. Error responses use the repository's
RFC 9457 format, including `RESUME_TOO_LARGE`, `INVALID_RESUME_TYPE`, and `RESUME_PARSE_FAILED`.

OpenAPI JSON and Swagger UI document schemas and authentication at `/v3/api-docs` and `/swagger-ui.html` when enabled.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `UPLOAD_DIR` | `uploads` | Existing persistent resume root. |
| `CANDIDATE_RESUME_MAX_BYTES` | `5242880` | Candidate resume byte limit (validated between 1 KiB and 20 MiB). |
| `CANDIDATE_RESUME_ALLOWED_FORMATS` | `pdf,docx` | Allowed subset of `pdf,docx`. |
| `MAX_FILE_SIZE` | `5MB` | Spring multipart per-file limit. |
| `MAX_REQUEST_SIZE` | `6MB` | Multipart request limit including boundary overhead. |

Both Nginx configurations allow 10 MiB request bodies, which is safely above the backend request limit. Candidate counters are exposed
through the existing secured Micrometer endpoint as `candidate.resume.uploads` and `candidate.resume.parses` with bounded `outcome` tags.

## Limitations

- OCR is not included; image-only PDFs require conversion to a text-based document.
- Deterministic heuristics cannot perfectly infer every resume layout, column order, date range, or organization/title pairing.
- The quality score is guidance, not an ATS score, job match score, or employment guarantee.
