# Employer registry verification

## 2026-10-13: Phase 4 India expansion (M10)

Verification time: **2026-10-13 04:30:05 UTC**

The registry, including 18 newly added India employers, was checked with outbound network access using:

```bash
date -u +%FT%TZ
bash backend/backend/scripts/validate-employer-registry.sh
```

The command exited `0`. All 38 enabled boards returned HTTP 200 with a structurally valid, non-empty payload.
No board was `EMPTY`, `MALFORMED`, `INVALID`, or `UNREACHABLE`, so nothing was disabled. Counts are a
point-in-time observation. For SmartRecruiters the count is the API's `totalFound`. An unknown SmartRecruiters
company also returns an empty list, so every SmartRecruiters entry here was confirmed ACTIVE, not just non-empty.
How each employer and board was identified is recorded in [`india-employer-sources.md`](india-employer-sources.md).

| Provider | Board | Classification | Listings |
| --- | --- | --- | ---: |
| Greenhouse | `greenhouse` | ACTIVE | 18 |
| Greenhouse | `airbnb` | ACTIVE | 160 |
| Greenhouse | `stripe` | ACTIVE | 721 |
| Greenhouse | `datadog` | ACTIVE | 436 |
| Greenhouse | `cloudflare` | ACTIVE | 434 |
| Greenhouse | `figma` | ACTIVE | 150 |
| Greenhouse | `reddit` | ACTIVE | 144 |
| Greenhouse | `discord` | ACTIVE | 49 |
| Greenhouse | `coinbase` | ACTIVE | 225 |
| Greenhouse | `affirm` | ACTIVE | 201 |
| Greenhouse | `asana` | ACTIVE | 102 |
| Greenhouse | `mongodb` | ACTIVE | 393 |
| Greenhouse | `okta` | ACTIVE | 379 |
| Greenhouse | `lyft` | ACTIVE | 186 |
| Greenhouse | `duolingo` | ACTIVE | 60 |
| Greenhouse | `instacart` | ACTIVE | 117 |
| Greenhouse | `roblox` | ACTIVE | 262 |
| Greenhouse | `dropbox` | ACTIVE | 32 |
| Lever | `palantir` | ACTIVE | 311 |
| Lever | `jobvite` | ACTIVE | 4 |
| Greenhouse | `razorpaysoftwareprivatelimited` | ACTIVE | 28 |
| Greenhouse | `groww` | ACTIVE | 10 |
| Greenhouse | `inmobi` | ACTIVE | 75 |
| Greenhouse | `glance` | ACTIVE | 36 |
| Greenhouse | `druva` | ACTIVE | 34 |
| Lever | `cred` | ACTIVE | 7 |
| Lever | `meesho` | ACTIVE | 57 |
| Lever | `paytm` | ACTIVE | 177 |
| Lever | `hevodata` | ACTIVE | 52 |
| Lever | `mindtickle` | ACTIVE | 20 |
| Lever | `zeta` | ACTIVE | 25 |
| Lever | `fampay` | ACTIVE | 17 |
| Lever | `pocketfm` | ACTIVE | 11 |
| Ashby | `sarvam` | ACTIVE | 41 |
| Ashby | `atlan` | ACTIVE | 7 |
| SmartRecruiters | `swiggy` | ACTIVE | 183 |
| SmartRecruiters | `Freshworks` | ACTIVE | 122 |
| SmartRecruiters | `ixigo` | ACTIVE | 10 |

Summary: Greenhouse `ACTIVE=23`; Lever `ACTIVE=10`; Ashby `ACTIVE=2`; SmartRecruiters `ACTIVE=3`; Workable has
no enabled entries (its terms are unverified, see [`provider-terms.md`](provider-terms.md)); all other
classification counts are zero.

## 2026-08-16: Phase 1–3 release

Verification date: **2026-08-16 17:04:46 UTC**

The repository registry was checked with outbound network access using:

```bash
date -u +%FT%TZ
bash backend/backend/scripts/validate-employer-registry.sh
```

The command exited `0`. All 20 enabled boards returned HTTP 200 with a structurally valid,
non-empty payload. No board was classified `EMPTY`, `MALFORMED`, `INVALID`, or `UNREACHABLE`, so no
registry entry was disabled. Counts are a point-in-time observation and may change independently of
the repository.

| Provider | Board | Classification | Listings |
| --- | --- | --- | ---: |
| Greenhouse | `greenhouse` | ACTIVE | 14 |
| Greenhouse | `airbnb` | ACTIVE | 186 |
| Greenhouse | `stripe` | ACTIVE | 578 |
| Greenhouse | `datadog` | ACTIVE | 425 |
| Greenhouse | `cloudflare` | ACTIVE | 304 |
| Greenhouse | `figma` | ACTIVE | 161 |
| Greenhouse | `reddit` | ACTIVE | 151 |
| Greenhouse | `discord` | ACTIVE | 50 |
| Greenhouse | `coinbase` | ACTIVE | 167 |
| Greenhouse | `affirm` | ACTIVE | 194 |
| Greenhouse | `asana` | ACTIVE | 132 |
| Greenhouse | `mongodb` | ACTIVE | 409 |
| Greenhouse | `okta` | ACTIVE | 333 |
| Greenhouse | `lyft` | ACTIVE | 171 |
| Greenhouse | `duolingo` | ACTIVE | 70 |
| Greenhouse | `instacart` | ACTIVE | 112 |
| Greenhouse | `roblox` | ACTIVE | 230 |
| Greenhouse | `dropbox` | ACTIVE | 35 |
| Lever | `palantir` | ACTIVE | 308 |
| Lever | `jobvite` | ACTIVE | 4 |

Summary: Greenhouse `ACTIVE=18`; Lever `ACTIVE=2`; all other classification counts are zero.

The offline classifier harness covers active, legitimately empty, malformed, invalid, unreachable,
and disabled fixtures:

```bash
bash backend/backend/scripts/verify-employer-registry-classification.sh
```

Live validation is read-only. A network failure must be recorded as `UNREACHABLE`, not treated as a
successful verification or evidence that a board is invalid. Only provider-confirmed `404`/`410`
entries should be disabled after review.
