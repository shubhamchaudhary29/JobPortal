# Job Matching Engine v1.2

## Meaning of the score

The match score is a deterministic **profile-to-job compatibility** measure. It is not a probability of being hired, an ATS acceptance probability, a recruiter approval prediction, or an official ATS score. Resume Quality Score remains a separate profile-quality heuristic.

The same structured candidate profile, canonical job, configuration, and scoring version produce the same result. Matching does not use paid APIs, LLMs, randomness, resume rewriting, or application automation.

## Pipeline and persistence

Recruiter-created and imported jobs use the same provider-neutral `JobFeatureExtractor`. It derives versioned invariant `matchFeatures` from the canonical job title, bounded description, location, employment metadata, and existing experience field. Ordinary repository saves run the extraction callback; the canonical aggregation upsert invokes the same service because Mongo aggregation updates bypass entity callbacks.

Candidate-specific scores are calculated on demand and are never stored as candidate/job pair documents. Existing jobs without features are extracted lazily when matched. Lazy persistence uses bounded, conditional, feature-only Mongo updates: if canonical source content changed after it was read, the stale extraction is not written, and lifecycle/source-listing fields are never replaced. Extraction is idempotent: `featureExtractionVersion` and a source hash prevent repeated work and invalidate features after relevant job content changes. There is no destructive migration and no provider call in a match request.

Personalized ranking queries only active, non-conflicted canonical jobs. Mongo prefilters location and source and returns a freshness-ordered window, configured by `matching.candidate-window` (default 500). The application scores that bounded window, applies structured role/work-mode/employment/minimum-score filters, and orders deterministically by score, freshness, then job ID. This limit is appropriate for the current project scale; a search/index ranking implementation can replace the repository boundary if the corpus substantially grows.

## Feature extraction

The deterministic extraction layer provides:

- normalized skills using the Phase 1 `SkillNormalizer`, preserving distinctions such as Java versus JavaScript;
- required/preferred/neutral skill inference from local section and phrase markers;
- experience ranges including `0-2 years`, `2+ years`, `3-5 years`, fresher, internship, and structured years;
- title-first seniority (`INTERN`, `ENTRY`, `JUNIOR`, `MID`, `SENIOR`, `LEAD`, `UNKNOWN`) with conservative explicit-experience fallback;
- common bachelor/master and CS/CSE/IT-equivalent education concepts;
- remote, hybrid, onsite, and employment-type signals;
- backend, frontend, full-stack, general software, DevOps/platform, cloud, data, ML/AI, security, QA, and mobile role families without collapsing unrelated technical roles.

Required/preferred inference is deliberately heuristic. When wording is ambiguous, a skill remains neutral rather than being falsely labeled mandatory.

## Weighting and missing data

Defaults are centralized, validated at startup, and must sum to 100:

| Dimension | Weight |
| --- | ---: |
| Required/preferred skills | 40 |
| Experience compatibility | 20 |
| Role/title relevance | 15 |
| Stated education requirement | 10 |
| Location/work-mode preference | 10 |
| Employment type | 5 |

Required skills dominate the skill component. Preferred skills affect the result without being treated as mandatory. Candidate experience is the union of valid structured month intervals, so overlaps are not double-counted; current work uses the calculation clock and year-only dates use deterministic boundaries.

Missing job information is not scored as zero. Unavailable job dimensions are excluded and their weights are normalized across dimensions with evidence. By contrast, an explicit job requirement that is absent from the candidate profile remains a real scored gap; it is not normalized away. Responses expose the normalized weights and `HIGH`, `MEDIUM`, or `LOW` confidence. Sparse candidate/job evidence yields `LOW_DATA`, and the UI asks the candidate to add skills and preferences rather than presenting an overstated percentage.

Scoring version: `job-match-1.2.0`. Feature version: `job-features-1.2.0`.

## API and authorization

- `GET /api/v1/jobs/{jobId}/match` calculates one visible job match.
- `GET /api/v1/jobs/matched` returns a paginated personalized feed with `page`, `size`, `minMatch`, `location`, `source`, `employmentType`, `workMode`, `role`, and `sort=matchScore|newest|oldest`.

Both endpoints require role `USER`. Candidate identity always comes from the authenticated email and user record; no candidate ID parameter is accepted or needed. Recruiters, administrators, and anonymous callers cannot retrieve personalized results. Public job responses contain no profile data, and metrics/logging use only fixed outcome labels—never candidate IDs, job IDs, email, tokens, resume text, or job descriptions.

## Observability and limitations

Micrometer records feature extraction successes/failures, match calculation successes/failures, and calculation duration without high-cardinality labels. Deterministic language heuristics cannot understand every unusual title or prose format, infer unlisted skills, or predict recruiter judgment. Results depend on profile accuracy, recognizable posting evidence, the bounded candidate window, and the current normalization vocabulary.
