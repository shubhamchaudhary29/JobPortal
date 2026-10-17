// GEO-7B rate-limit check THROUGH nginx (local, not CI). Every request comes from one IP, so this measures the
// per-IP limits, not capacity (map-snapshot.js and map-jobs.js do that inside the Compose network).
// docker run --rm -i --network host -e BASE_URL=http://localhost grafana/k6 run - < backend/backend/scripts/k6/rate-limits.js
// Expectations, encoded as thresholds:
//   - nearby at 15 r/s (zone map_list: 5 r/s, burst 10) gets a good share of 429s, each with the JSON ProblemDetail body;
//   - the snapshot at 10 r/s (zone api: 20 r/s) and /tiles/manifest.json at 50 r/s (no limit) are never throttled.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const nearbyLimited = new Rate('nearby_rate_limited');
const snapshotOk = new Rate('snapshot_ok');
const manifestLimited = new Counter('manifest_rate_limited');
const accepted = http.expectedStatuses(200, 304, 429);

const constant = (exec, rate) => ({
  executor: 'constant-arrival-rate', exec, rate, timeUnit: '1s', duration: '20s', preAllocatedVUs: 5, maxVUs: 20,
});

export const options = {
  scenarios: {
    nearby: constant('nearby', 15),
    snapshot: constant('snapshot', 10),
    manifest: constant('manifest', 50),
  },
  thresholds: {
    // 300 requests against ~110 allowed (5 r/s × 20 s + burst 10): roughly 60% should be 429.
    nearby_rate_limited: ['rate>0.3'],
    snapshot_ok: ['rate==1'],
    manifest_rate_limited: ['count==0'],
    'checks{check:429 body is a RATE_LIMITED problem}': ['rate==1'],
  },
};

export function nearby() {
  const response = http.get(`${BASE_URL}/api/v1/jobs/map/nearby?lat=12.95&lng=77.6&radiusKm=25`,
    { responseCallback: accepted });
  nearbyLimited.add(response.status === 429);
  if (response.status === 429) {
    check(response, { '429 body is a RATE_LIMITED problem': (r) => r.json('code') === 'RATE_LIMITED' });
  }
}

export function snapshot() {
  const response = http.get(`${BASE_URL}/api/v1/jobs/map/snapshot`, { responseCallback: accepted });
  snapshotOk.add(response.status === 200 || response.status === 304);
}

export function manifest() {
  const response = http.get(`${BASE_URL}/tiles/manifest.json`, { responseCallback: accepted });
  manifestLimited.add(response.status === 429 ? 1 : 0);
}
