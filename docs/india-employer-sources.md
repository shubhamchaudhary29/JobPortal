# India employer sources

Research for M10, done 2026-10-13 with read-only requests. For each employer we checked the public careers
page for a link to a supported ATS (Greenhouse, Lever, Ashby, SmartRecruiters, Workable) and probed those ATS
APIs with likely board names. A board was accepted only when its identity was confirmed by one of:

- **link**: the careers page (or its redirect) links to that exact board;
- **content**: the careers page is rendered by JavaScript and shows no link, but the board's own name or job
  descriptions name the company (quoted below).

Only boards that the live validator classified `ACTIVE` were added (see
[`employer-registry-verification.md`](employer-registry-verification.md) for the dated run and counts). Boards
with the right name that belong to unrelated companies were rejected. India listing counts are from the same
day and change constantly.

## Candidates named in the plan

| Company | Careers page checked | ATS detected | Board ID | Identity | Result | Registry |
| --- | --- | --- | --- | --- | --- | --- |
| Razorpay | razorpay.com/careers | Greenhouse | `razorpaysoftwareprivatelimited` | link; board name "Razorpay Software Private Limited" | ACTIVE, 28 jobs (24 in India) | added |
| CRED | careers.cred.club (JS) | Lever | `cred` | content: "what is CRED? CRED is an exclusive community for india's most trustworthy…" | ACTIVE, 7 (7) | added |
| Zepto | zeptonow.com/careers (HTTP 202, no content) | none found | — | — | no supported board found for `zepto`, `zeptonow`, `kiranakart` | not added |
| Groww | groww.in/careers | Greenhouse (EU-hosted board) | `groww` | link to `job-boards.eu.greenhouse.io/groww`; board name "Groww" | ACTIVE, 10 (10) | added |
| Meesho | meesho.io/jobs (JS) | Lever | `meesho` | content: "…new bets we are taking at Meesho…" | ACTIVE, 57 (56) | added |
| PhonePe | phonepe.com/careers | none found | — | — | no supported board found | not added |
| Swiggy | careers.swiggy.in (JS) | SmartRecruiters | `swiggy` | content: posting company name "SWIGGY" | ACTIVE, 183 (100 of the first 100 in India) | added |
| Zomato | zomato.com/careers → eternal.com/careers | none found | — | Lever `eternal` is an unrelated US company (NYC/SF performance roles) | rejected | not added |
| Postman | postman.com/company/careers | none found | — | — | no supported board found for `postman`, `postmanlabs` | not added |
| BrowserStack | browserstack.com/careers | Workday (unsupported) | — | page references `myworkdayjobs` | unsupported ATS | not added |
| Freshworks | careers.freshworks.com → careers.smartrecruiters.com/Freshworks | SmartRecruiters | `Freshworks` | link (redirect); company name "Freshworks" | ACTIVE, 122 (29 of the first 100) | added |
| Zerodha | careers.zerodha.com | own site | — | — | no supported board found | not added |
| Atlassian India | atlassian.com/company/careers | none found | — | — | no supported board found | not added |
| Nykaa | careers.nykaa.com | none found | — | — | no supported board found | not added |
| Ola | olacabs.com/careers | Skillate/TurboHire (unsupported) | — | page references `skillate`, `turbohire` | unsupported ATS | not added |
| Dream11 | dreamsports.group/careers | Lever (link) | `dreamsports` | link | Lever API returns `404` "Document not found" (board removed) | not added |
| InMobi | inmobi.com/company/careers (JS) | Greenhouse | `inmobi` | content: board name "InMobi" | ACTIVE, 75 (39) | added |
| ShareChat | sharechat.com/careers | none found | — | — | no supported board found for `sharechat`, `mohalla`, `mohallatech` | not added |
| Unacademy | unacademy.com/careers | Darwinbox (unsupported); SmartRecruiters account also exists | `unacademy` | company name "Unacademy" | ACTIVE, 3 (including a "Test Jerin" posting) | not added: careers page uses Darwinbox, so the SmartRecruiters account is not the live board |
| Upstox | upstox.com/careers | SmartRecruiters account exists | `upstox` | company name "upstox" | ACTIVE, 1 posting ("demat account opening", not a job) | not added |
| Slice | sliceit.com/careers → slice.bank.in/careers | none found | — | Greenhouse `slice` is an unrelated US company (slice.careers, no India jobs) | rejected | not added |
| Jar | myjar.app/careers | none found | — | — | no supported board found | not added |
| Rapido | rapido.bike/Careers | Darwinbox (unsupported) | — | page references `darwinbox` | unsupported ATS | not added |
| Urban Company | careers.urbancompany.com | none found | — | Workable `urbanclap` exists but is empty | no active board | not added |

## Additional India employers found

These were found by probing about 150 further Indian employers on the supported ATS APIs.

| Company | Careers page checked | ATS | Board ID | Identity | Result | Registry |
| --- | --- | --- | --- | --- | --- | --- |
| Paytm | paytm.com/careers | Lever | `paytm` | link; content "Paytm is India's…" | ACTIVE, 177 (146) | added |
| Hevo Data | hevodata.com/careers | Lever | `hevodata` | link; content "About Hevo Data" | ACTIVE, 52 (50) | added |
| Mindtickle | mindtickle.com/careers → jobs.lever.co/mindtickle | Lever | `mindtickle` | link (redirect) | ACTIVE, 20 (19) | added (a smaller SmartRecruiters `mindtickle` account exists; only one entry per company may be enabled) |
| Zeta | zeta.tech/in/careers (JS) | Lever | `zeta` | content: "Zeta is a next-generation banking technology company…" | ACTIVE, 25 (22) | added |
| Fam (FamPay) | famapp.in/careers (JS) | Lever | `fampay` | content: "About Fam (previously FamPay) Fam is India's first payments app…" | ACTIVE, 17 (17) | added |
| Pocket FM | pocketfm.com/careers (JS) | Lever | `pocketfm` | content: "Pocket FM is the world's largest audio-series platform…" | ACTIVE, 11 (2) | added |
| Glance | glance.com/careers | Greenhouse | `glance` | link; board name "Glance" | ACTIVE, 36 (20) | added |
| Druva | druva.com/why-druva/explore/careers | Greenhouse | `druva` | link (`gh_jid` postings on druva.com); board name "Druva" | ACTIVE, 34 (13) | added |
| Sarvam AI | sarvam.ai/careers | Ashby | `sarvam` | link; content "Sarvam is building the bedrock of Sovereign AI for India" | ACTIVE, 41 (41) | added |
| Atlan | atlan.com/careers (JS) | Ashby | `atlan` | content: "Atlan is building the context layer for enterprise…" | ACTIVE, 7 (5) | added |
| ixigo | ixigo.com/careers → careers.ixigo.com (JS) | SmartRecruiters | `ixigo` | content: company name "ixigo"; posting "AVP – People & Culture \| ixigo" | ACTIVE, 10 (10) | added |
| Tekion | — | Ashby | `tekion` | content names Tekion | ACTIVE, 95 (72 in India) | not added: US-headquartered |
| Truecaller | — | Greenhouse | `truecaller` | board name "Truecaller" | ACTIVE, 30 (12) | not added: Sweden-headquartered |
| Cars24, Freecharge, NoBroker, InterviewBit, Whatfix | — | SmartRecruiters | various | company names match | ACTIVE with one posting each (Whatfix's is "test") | not added: careers pages were not shown to use these accounts |

Rejected look-alikes (same board name, different company): Greenhouse `clear` (CLEAR, US), Ashby `navi`
(pilot-debrief startup), Lever `hevo` (wireless EV charging), Lever `fi` (US), Greenhouse `verse`, Ashby `leap`
(US benefits), Greenhouse/Lever `porter` (US), Ashby `bounce` (US storage).

## Result

18 India employers were added and enabled: 5 on Greenhouse, 8 on Lever, 2 on Ashby, and 3 on SmartRecruiters.
All were live-validated `ACTIVE` on 2026-10-13. No Workable employer is enabled because Workable's terms are
unverified. Employers on unsupported ATSes (Darwinbox, Workday, Skillate/TurboHire, own sites) are recorded
above and not added.
