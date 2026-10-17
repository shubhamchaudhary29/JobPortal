// Load check for GET /api/v1/jobs/map/snapshot (local, not CI).
// Capacity runs target the backend INSIDE the Compose network: through nginx, 200 VUs from one IP would only measure
// GEO-7B's per-IP rate limits (scripts/k6/rate-limits.js checks those). <project> is the Compose project name
// (default: the repo directory name, e.g. jobportal → network jobportal_default; see `docker network ls`).
// docker run --rm -i --network <project>_default -e BASE_URL=http://backend:8080 grafana/k6 run - < backend/backend/scripts/k6/map-snapshot.js
// Half the iterations revalidate with If-None-Match (expect 304), all ask for gzip (expect the precomputed body).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://backend:8080';
const URL = `${BASE_URL}/api/v1/jobs/map/snapshot`;

export const options = {
  vus: 200,
  duration: '60s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100'],
  },
};

export function setup() {
  const response = http.get(URL, { headers: { 'Accept-Encoding': 'gzip' } });
  if (response.status !== 200) throw new Error(`setup: expected 200, got ${response.status}`);
  return { etag: response.headers['Etag'] || response.headers['ETag'] };
}

export default function (data) {
  const revalidate = __ITER % 2 === 0;
  const headers = { 'Accept-Encoding': 'gzip' };
  if (revalidate) headers['If-None-Match'] = data.etag;
  const response = http.get(URL, { headers, responseCallback: http.expectedStatuses(200, 304) });
  check(response, {
    'status is 200 or 304': (r) => r.status === 200 || r.status === 304,
    'revalidation hits 304': (r) => !revalidate || r.status === 304,
  });
}
