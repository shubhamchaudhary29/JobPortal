# Provider terms and usage rules

Every job provider must be recorded here before it is enabled in production. Each entry lists the endpoint,
authentication, rate limits, attribution, link and caching rules, the date they were checked, and the source.
"Not documented" means the provider's public documentation says nothing on that point as of the check date;
re-check before raising request volume.

Phase 4 source policy (see `aggregation-completion-plan.md`): no LinkedIn, Naukri, Indeed, or HTML scraping;
JSearch is never scheduled; aggregator jobs keep the provider's redirect link and show attribution; API keys
come only from environment variables, are never logged, and a provider stays disabled when its key is missing.

## Ashby (ATS board, `ashby`)

- Checked: 2026-10-13 against <https://developers.ashbyhq.com/docs/public-job-posting-api> (page updated
  2026-05-26) and live read-only requests to the `ashby` board.
- Endpoint: `GET https://api.ashbyhq.com/posting-api/job-board/{JOB_BOARD_NAME}?includeCompensation=true`.
  The board name is the last path segment of the employer's `https://jobs.ashbyhq.com/{name}` page.
- Authentication: none; the API returns only published postings that the employer exposes publicly.
- Purpose stated by Ashby: lets organizations populate their own careers pages. It is a public, unauthenticated
  read API; we use it read-only at a 6-hour default interval with the shared per-board rate limiter.
- Rate limits: not documented. We stay at `JOB_AGGREGATION_PROVIDER_REQUESTS_PER_SECOND` (default 5) per
  board, one request per board per run, and honor `429`/`Retry-After`.
- Listing rules: `isListed=false` postings "should only be available via direct link", so they are skipped and
  never shown in search. An unknown board returns HTTP `404` (`INVALID` in registry validation).
- Links: `jobUrl` (Ashby-hosted posting page) is kept as the application URL, falling back to `applyUrl`.
  No URL rewriting.
- Attribution: not documented. Job pages already link to the employer's Ashby posting.
- Caching/retention: not documented. Imported postings follow the shared lifecycle (deactivated after
  `JOB_AGGREGATION_MISSING_THRESHOLD` successful misses) and retention cleanup.
- Compensation: mapped to `salaryMin`/`salaryMax` only for an annual (`1 YEAR`) `Salary` component in `INR`,
  because jobs store no currency and the UI displays salaries in rupees. Other currencies are not stored.
