# Provider terms and usage rules

Every job provider must be recorded here before it is enabled in production. Each entry lists the endpoint,
authentication, rate limits, attribution, link and caching rules, the date they were checked, and the source.
"Not documented" means the provider's public documentation says nothing on that point as of the check date;
re-check before raising request volume.

Phase 4 source policy (see `aggregation-completion-plan.md`): no LinkedIn, Naukri, Indeed, or HTML scraping;
JSearch is never scheduled; aggregator jobs keep the provider's redirect link and show attribution; API keys
come only from environment variables, are never logged, and a provider stays disabled when its key is missing.

## Adzuna (search API, `adzuna`)

- Checked: 2026-10-13 against the Adzuna API terms of service
  (<https://developer.adzuna.com/docs/terms_of_service>).
- Endpoint: `GET https://api.adzuna.com/v1/api/jobs/in/search/{page}` with `app_id`, `app_key`, `what`,
  optional `where`, and `results_per_page`. Credentials come only from `ADZUNA_APP_ID`/`ADZUNA_APP_KEY`. They
  appear only in the outbound query string and never in logs or errors (`RestTemplateAdzunaClientTest`).
- Permitted use: publishing Adzuna ad listings is an explicitly permitted use.
- Rate limits (defaults, may change): 25 requests/minute, 250/day, 1,000/week, 2,500/month. Our defaults stay
  inside all four: at most 33 requests per 12-hourly run (about 66/day, 462/week, 1,980/month, enforced by
  `ADZUNA_MONTHLY_REQUEST_BUDGET=2000` including retries), spaced at least `ADZUNA_MIN_REQUEST_INTERVAL_MS=2500`
  apart (24/minute). Manual ADMIN runs count against the same budget; keep them occasional.
- Attribution (required): "An API user shall label each displayed advert with the phrase 'Adzuna' at least 116 X 23
  pixels in size, wherein the word 'Adzuna' shall be hyperlinked to http://www.adzuna.co.uk or the relevant local
  domain." Every Adzuna listing shows a "Jobs by Adzuna" link to `https://www.adzuna.in` with a minimum box of
  116 × 23 px on job cards and the job detail page (`ProviderAttribution`). It was added during the Phase 4
  release check; earlier releases showed no attribution.
- Links: Adzuna's `redirect_url` is kept as the application URL. All queries go to Adzuna, never to the
  advertisers behind the listings, as the terms require.

## Greenhouse (ATS board, `greenhouse`)

- Checked: 2026-10-13 against the Job Board API documentation (<https://developers.greenhouse.io/job-board.html>).
- Endpoint: `GET https://boards-api.greenhouse.io/v1/boards/{board_token}/jobs?content=true`.
- Authentication and use: "Job Board data is publicly available, so authentication is not required for any GET
  endpoints." The API is meant for building careers pages from published jobs; we read published jobs only and
  never use the authenticated application POST.
- Rate limits, attribution, caching: not documented. We pace each board at
  `JOB_AGGREGATION_PROVIDER_REQUESTS_PER_SECOND` (default 5), make one request per board per 6-hour run, honor
  `429`/`Retry-After`, and keep each posting's `absolute_url` as the application link.

## Lever (ATS board, `lever`)

- Checked: 2026-10-13 against the Lever Postings API README (<https://github.com/lever/postings-api>).
- Endpoint: `GET https://api.lever.co/v0/postings/{site}?mode=json`.
- Authentication and use: published postings are public and "may be scraped by third parties"; unpublished jobs
  are never exposed. Only the application POST needs a key and has a documented 2 requests/second limit; we never
  call it.
- Rate limits, attribution, caching: not documented for reads. Same pacing, one request per board per run, and
  `hostedUrl` as the application link.

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

## SmartRecruiters (ATS board, `smartrecruiters`)

- Checked: 2026-10-13 against <https://developers.smartrecruiters.com/docs/posting-api>,
  <https://developers.smartrecruiters.com/docs/endpoints>, <https://developers.smartrecruiters.com/docs/rate-limiting>,
  and live read-only requests to the `BoschGroup` board.
- Endpoints: `GET https://api.smartrecruiters.com/v1/companies/{companyIdentifier}/postings?offset={n}&limit={1..100}`
  for the list and `GET .../postings/{postingId}` for one posting's detail.
- Authentication: none for public postings (verified live). The docs note API-key/OAuth access for internal
  postings, which we never request.
- Purpose stated by SmartRecruiters: the Posting API lets customers build their own career sites from postings
  they made public. We use it read-only.
- Rate limits: documented as up to 10 requests/second and 8 concurrent requests per API user, with `429` on
  excess. We use at most `JOB_AGGREGATION_PROVIDER_REQUESTS_PER_SECOND` (default 5) per board, sequentially, and
  honor `Retry-After`.
- Request volume: list pages are bounded by `SMARTRECRUITERS_MAX_PAGES` (default 20) and the provider item limit.
  List items have no description or public link, so a posting's detail is fetched once per `releasedDate` and
  cached in `smartrecruiters_posting_details` (TTL `SMARTRECRUITERS_DETAIL_RETENTION_DAYS`, default 30). At most
  `SMARTRECRUITERS_MAX_DETAIL_REQUESTS` (default 100) details are fetched per board run; postings still waiting
  for a detail are not imported yet and the run is recorded as incomplete, so lifecycle never advances on it.
- Unknown companies: the list endpoint returns `200` with an empty `content` array for a nonexistent company
  identifier (verified with a random identifier), and `/v1/companies/{id}` returns `404` even for real companies.
  Registry validation therefore reports such entries as `EMPTY`; confirm a new identifier against the employer's
  `https://careers.smartrecruiters.com/{identifier}` page before enabling it.
- Links: the detail `postingUrl` (SmartRecruiters-hosted posting page) is the application URL, falling back to
  `applyUrl`. No URL rewriting.
- Attribution and caching/retention: not documented for the Posting API. Imported postings follow the shared
  lifecycle and retention cleanup.

## Workable (ATS board, `workable`)

- Status: **unverified, needs a human check before enabling production boards.** The endpoint is public and
  returns only what employers publish, but Workable does not document it for third-party server-side use.
- Checked: 2026-10-13 with live read-only requests (`huggingface`, `blueground`, `skroutz`, `hubspot-1`), the
  help-center articles "How to embed jobs on your website (job widget)" (updated 2026-06-17), "Using the Workable
  API to create a careers page" (updated 2026-09-17), and "Comparing careers page options" (updated 2026-05-19),
  the Workable Terms of Use (<https://www.workable.com/terms>, last updated 2026-03-02), and
  <https://apply.workable.com/robots.txt>.
- Endpoint: `GET https://apply.workable.com/api/v1/widget/accounts/{account}?details=true`, the JSON backend of
  Workable's embeddable jobs widget. It returns `{"name", "description", "jobs": [...]}` with each published job's
  `shortcode`, `title`, `description` (HTML), `employment_type`, `telecommuting`, `city`/`state`/`country`,
  `published_on`, `url`, and `application_url`. An unknown account returns `404` with a plain-text body.
- Authentication: none. Workable's documented API (`/spi/v3`) requires an account-owned token with `r_jobs`, so
  it is only usable by the employer itself.
- What was found: the help center documents the widget for employers' own websites and the token API for custom
  careers pages; neither page addresses third-party aggregation. The Terms of Use bind Workable customers and do
  not address read-only access to published postings. `apply.workable.com/robots.txt` allows all paths
  (`Disallow:` empty; `Content-Signal: search=yes, ai-input=yes, ai-train=no`). No rate limit is documented.
- Decision: implemented against mocks with the shared reliability stack (5 req/s per account, `429` and
  `Retry-After` honored, one request per account per 6-hour run). Workable is not HTML scraping. No production
  registry entry should be enabled until the owner confirms this use with Workable or accepts the risk.
- Links: `url` (`https://apply.workable.com/j/{shortcode}`) is the application URL, falling back to
  `application_url`. No URL rewriting.
- Attribution and caching/retention: not documented. Imported postings follow the shared lifecycle and retention.

## Jooble (search API, `jooble`)

- Status: **disabled by default; terms unverified, needs a human check before enabling in production.** The
  Jooble API Terms of Use that the help center requires compliance with are served behind a Cloudflare browser
  challenge (`jooble.org/api/about` and `jooble.org/api/terms` returned HTTP `403` to automated requests on
  2026-10-13), so their attribution, caching, and display rules could not be read.
- Checked: 2026-10-13 against the Jooble help-center articles "REST API Documentation"
  (<https://help.jooble.org/en/support/solutions/articles/60001448238-rest-api-documentation>, modified
  2026-08-16) and "How to connect to the Jooble REST API" (<https://help.jooble.org/en/support/solutions/articles/60000922689>,
  modified 2026-08-16). No live request was made because no API key is available.
- Endpoint: `POST https://{region}.jooble.org/api/{api_key}` with a JSON body (`keywords`, `location`, `page`,
  optional `ResultOnPage`, `radius`, `salary`, `SearchMode`, `companysearch`). The response is
  `{totalCount, jobs: [{id, title, location, snippet, salary, source, type, link, company, updated}]}`.
- Authentication: an API key in the URL path. **Keys are regional**: a `jooble.org` key returns only US listings,
  so India needs a key registered on `in.jooble.org`; `JOOBLE_BASE_URL` defaults to `https://in.jooble.org/api`.
  The key comes only from `JOOBLE_API_KEY`; without it Jooble is not registered, scheduled, or available in ADMIN.
  Because the key is in the path, provider failures carry only `jooble provider request failed: <kind>`, never the
  URL. `JoobleKeyRedactionTest` proves no failure log contains the key or host, and `JoobleProperties.toString()`
  omits it.
- Rate limits: **the free plan allows 500 requests per key in total (a lifetime quota, not monthly).** Defaults are
  conservative: 3 keywords × 1 page at location `India` every 24 hours (about 90 requests/month), capped by
  `JOOBLE_MONTHLY_REQUEST_BUDGET=100`, which also counts retries. Set the budget to your plan before enabling; a
  free key lasts about five months on the defaults.
- Links and attribution: Jooble's `link` (`https://{region}.jooble.org/...`) is kept as the application URL
  without rewriting, and every Jooble listing shows "Jobs by Jooble" on job cards and a linked credit on the job
  detail page.
- Data handling: postings without a company are skipped, because they cannot be attributed or de-duplicated.
  Free-text salaries are parsed only for explicit annual rupee amounts. Listings follow the shared lifecycle and
  retention. Caching/retention limits are unknown until the terms are read.

## Careerjet (search API) — blocked, not integrated

- Status: **blocked by terms; no adapter exists and nothing is ingested.** This follows the plan's M14 rule:
  document the blocker instead of building a non-compliant integration.
- Checked: 2026-10-13 against <https://www.careerjet.co.in/partners/api/>, its cURL guide
  <https://www.careerjet.co.in/partners/api/curl>, and the publisher page
  <https://www.careerjet.co.in/partners/publishers>.
- Endpoint: `GET https://search.api.careerjet.net/v4/query` with HTTP basic authentication (API key as the user
  name) and parameters including `locale_code` (`en_IN`), `keywords`, `location`, `page`, `page_size`.
- Blocking requirement: `user_ip` and `user_agent` are **required**, documented as "the IP address of the user
  whose action(s) triggered the API call" and "the user agent string of the user whose action(s) triggered the
  API call". The cURL guide repeats "Replace <USER_IP> with your user's actual IP address" and "Replace
  <USER_AGENT> with your user's actual User Agent", and requests without them fail with `403`. The publisher
  program is described as monetising the publisher's own web traffic by displaying Careerjet listings.
- Why it is blocked here: this portal's aggregation is scheduled server-side ingestion into MongoDB (public search
  never calls a provider live; that is a frozen foundation). A scheduled run has no end user whose action
  triggered the request, so complying would mean sending the server's IP and a synthetic user agent, which is
  not what the API requires. No caching or retention permission is published either.
- What would unblock it: written permission from Careerjet for scheduled server-side retrieval and storage (with
  the values to send for `user_ip`/`user_agent`), or a product decision to add a live, user-triggered Careerjet
  search outside the Mongo-only job search. Both are owner decisions.
