# Employer registry verification

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
