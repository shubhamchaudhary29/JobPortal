# Phase 4 execution prompt

Paste everything below the line into Claude Code, opened at the repository root. The prompt can be re-run at any time: it always resumes from the first unchecked milestone.

---

You are implementing Phase 4 (source expansion, India focus) of the job-aggregation work in this repository. Work autonomously through every remaining Phase 4 milestone, one at a time, until the plan and its release checklist are complete or you hit a genuine blocker. Do not stop between milestones to ask for confirmation.

## 0. Ground rules (read first, follow always)

1. Read `AGENTS.md`, then all of `docs/aggregation-completion-plan.md`, then `docs/architecture.md` and `docs/phase-3-operations.md`. The plan is the source of truth. `AGENTS.md` rules override anything in this prompt.
2. Always work only on the **first unchecked milestone** (`- [ ]`) in the plan. Never skip ahead, never work on two milestones at once, never tick a box whose acceptance tests have not passed in this session.
3. Do not edit the "Frozen foundations", the Phase 1–3 milestones (M1A–M5), their evidence, or the "Phase 1–3 release checklist", except where a Phase 4 milestone explicitly requires a minimal extension.
4. Follow the "Source policy" section of the plan: no LinkedIn, Naukri, Indeed or HTML scraping; JSearch never scheduled; aggregator jobs keep the provider's redirect link and show attribution; API keys only from environment variables, never logged, and the provider stays disabled when its key is missing.
5. Never skip, disable, `@Disabled`, quarantine, or weaken a test to get green. Never delete existing assertions to make a refactor pass; if behavior legitimately changes, the plan must say so and the evidence must explain it.
6. Match the existing code style (package layout under `backend/backend/src/main/java/com/example/backend/integration/`, the shared reliability stack in `integration/reliability/`, `ExternalJobNormalizer`, MockServer-style provider tests, Vitest frontend tests).
7. Never commit secrets, real API keys, or `.env` files. Use obvious test-only values in tests.
8. Work on the current branch (check with `git branch --show-current`). Do not create new branches or pull requests, and do not force-push.

## 1. Environment setup (once per session)

Run and fix until each works:

```bash
java -version            # must be 17
cd backend/backend && ./mvnw -q -DskipTests compile && cd ../..
node --version           # must be 20.x
cd frontend && npm ci && cd ..
```

The backend tests need a real MongoDB on `localhost:27017` and these env vars (same as CI in `.github/workflows/verify.yml`):

```bash
export MONGODB_URI=mongodb://localhost:27017/jobportal
export JWT_SECRET=test-only-jwt-secret-that-is-at-least-thirty-two-characters
export ADZUNA_APP_ID=test-id
export ADZUNA_APP_KEY=test-key
```

Start MongoDB 7 with `docker run -d --name jobportal-mongo -p 27017:27017 mongo:7` if Docker is available, otherwise a local `mongod`. Before starting milestone work, run `cd backend/backend && ./mvnw clean verify` once to confirm the baseline is green. If the baseline is red before you changed anything, stop and report the failing tests: that is a genuine blocker.

## 2. The milestone loop

Repeat until no Phase 4 milestone is unchecked:

1. **Pick.** Find the first `- [ ]` milestone under "Phase 4 — Source expansion". Re-read its Required behavior, Expected files, Tests required, Verification commands, Dependencies and Done when. Check that its dependencies are ticked.
2. **Size check.** If it cannot be finished and verified in one focused pass (for example M11 or M16), split it into child milestones in the plan (`M11A`, `M11B`, …), each with its own required behavior, tests, verification commands and done-when, placed in order in place of the parent. Commit the plan change alone (`docs: split M11 into independently verifiable steps`), then continue with the first child. Do not refuse or stall because a milestone is large.
3. **Investigate.** Read the code the milestone touches before changing it. For provider milestones, read `GreenhouseJobSource`, `LeverJobSource`, their tests, `ProviderReliabilityMockServerTest`, `EmployerIngestionService`, and the registry scripts, and copy their patterns.
4. **Provider terms first.** For any external provider (Ashby, SmartRecruiters, Workable, Jooble, Careerjet, Adzuna changes), first record its API endpoint, auth, rate limits, attribution, link and caching rules in `docs/provider-terms.md` (create it if missing), with the date and the source URL you checked. If you have no network access, write what is known and mark it "unverified, needs a human check before enabling in production". This alone does not block implementation behind mocks.
5. **Implement** the minimal change that satisfies the milestone. Write the tests listed in "Tests required" alongside the code; every listed case needs a test.
6. **Verify.** Run the milestone's focused test command first, then all of its verification commands, then always also:
   ```bash
   cd backend/backend && ./mvnw clean verify && bash scripts/verify-employer-registry-classification.sh && cd ../..
   cd frontend && npm run lint && npm test -- --run && npm run build && cd ..
   git diff --check
   ```
   Fix and re-run until everything passes. Do not move on with anything red.
7. **Self-review.** Read your full diff (`git diff`) adversarially: missing tests, key or URL leaking into logs, provider names still hard-coded outside the catalog, lifecycle advancing after a partial or failed run, unbounded loops or pagination, behavior changes to frozen foundations. Fix what you find and re-run step 6.
8. **Record evidence.** Replace the milestone's `Evidence: _pending_.` with `Evidence (YYYY-MM-DD): …` in the same style as M1–M5: what was built, the exact commands run, exact test counts (tests run, failures, errors, skips), and anything external that could not be verified and why. Be truthful; never claim a live check you did not run. Then change its `- [ ]` to `- [x]`.
9. **Commit and push.** One commit per milestone (plan + code + tests + docs together), message `feat(aggregation): M7 Ashby ATS adapter` or similar, then `git push`. If push fails because of the network, retry up to 4 times with 2s, 4s, 8s, 16s backoff.
10. **Continue** with the next unchecked milestone. Do not ask "should I continue?".

## 3. Milestone-specific notes

- **M6:** this is a refactor with no behavior change. All existing tests must pass with the same intent. The new `GET /api/admin/aggregation/providers` endpoint must be ADMIN-only (add `401/403` tests next to the existing admin security tests). Metric tags must stay a fixed allow-list.
- **M7–M9:** follow the Greenhouse empty/malformed rules exactly. Add each new ATS to `validate-employer-registry.sh` and to the classification fixture so `verify-employer-registry-classification.sh` covers ACTIVE/EMPTY/MALFORMED/INVALID for it. Do not add real employers yet (that is M10), apart from fixtures.
- **M10:** needs live network access to the ATS APIs. Run `bash backend/backend/scripts/validate-employer-registry.sh` for real. Only add boards you actually confirmed as ACTIVE or EMPTY, and record the date and per-board results in `docs/employer-registry-verification.md`. If the network is blocked, document that truthfully as the plan allows, add no unverified entries, and move on.
- **M11:** the fingerprint has a unique index (`imported_fingerprint_unique`). The migration must default to dry-run, route collisions to the existing conflict reconciliation, never delete or merge automatically, and never touch recruiter jobs or jobs with applications. Prove dry-run, apply and idempotence against a disposable seeded Mongo database, like `verify-aggregation-migration.sh` does.
- **M12:** moving Adzuna onto the shared framework must not change its config property names, sync-history records or metrics. Keep every existing Adzuna test passing.
- **M13/M14:** build and test fully against mocks with test-only keys. A missing real `JOOBLE_API_KEY`/`CAREERJET_AFFID` is not a blocker for the code milestone; note in evidence that live ingestion is untested until a key is configured. For Careerjet, if its terms require the real end user's IP/user agent and forbid scheduled server-side caching, do not implement the scheduled ingestion: document the blocker in `docs/provider-terms.md` and in the evidence, tick M14 as the plan allows ("or the terms blocker is documented"), and continue.
- **M15:** the city rotation must not cause false deactivations. Write a test that simulates a full month of runs and proves no job that is still live gets deactivated just because its query wasn't run.
- **M16:** keep the properties registry as a seed and fallback; the Mongo import must be idempotent. Add the ADMIN page next to the existing aggregation admin page and reuse its route guard.

## 4. When to stop

Stop and report only for a genuine blocker:
- the baseline was red before your changes;
- a required tool or service (Java 17, Maven, Node 20, MongoDB) cannot be installed or started;
- a decision only the owner can make (for example, a provider's terms forbid the planned use and the plan has no documented fallback).

Missing live API keys, missing network access for live registry checks, and oversized milestones are **not** reasons to stop. Handle them as described above.

When you stop for a blocker, commit and push any completed milestones first, leave the blocked milestone unchecked with a short "Blocked (YYYY-MM-DD): …" note under it, and report exactly what is needed.

## 5. Finish

When every Phase 4 milestone is ticked:
1. Work through the "Phase 4 release checklist" in the plan: run every check, including the Docker steps (`docker compose config && docker compose build && bash backend/backend/scripts/compose-smoke-test.sh`, with the env values CI uses) and `npm audit --omit=dev`. Tick each item only when it actually passed, and add dated evidence under the checklist.
2. Update `README.md`, `docs/architecture.md`, `docs/phase-3-operations.md` (or a new `docs/phase-4-operations.md`) and `.env.example` with the new providers, env vars, schedules, budgets and migration steps.
3. Commit, push, and confirm GitHub Actions (Verify and Secret scan) pass on the branch. If CI fails, fix the root cause and push again; never skip a check.
4. Report a short summary: milestones completed, any milestones completed with a documented external blocker, live checks still needed from a human (API keys, provider terms, registry checks), and the final test counts.
