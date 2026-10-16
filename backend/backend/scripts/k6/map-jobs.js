// Load check for the GEO-3/GEO-6 map job lists (local, not CI), against the backend inside the Compose network
// (through nginx, one IP is capped at 5 r/s by GEO-7B; scripts/k6/rate-limits.js checks that instead).
// Seed first: python3 backend/backend/scripts/seed-map-jobs.py --offices 3 | docker compose exec -T mongodb mongosh --quiet jobportal
// docker run --rm -i --network <project>_default -e BASE_URL=http://backend:8080 grafana/k6 run - < backend/backend/scripts/k6/map-jobs.js
// <project> is the Compose project name (default: the repo directory name; see `docker network ls`).
// Mix: 65% place lists for the top places (cache-friendly), 20% nearby around fixed city centres,
// 10% place lists with a search word (more distinct keys, so more cache misses), 5% office lists when the snapshot
// has offices (otherwise those iterations become place lists).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://backend:8080';
const CENTRES = [[12.97, 77.59], [19.07, 72.88], [28.61, 77.21], [17.39, 78.49], [18.52, 73.86]];
const WORDS = ['java', 'python', 'analyst', 'senior', 'lead', 'react', 'devops', 'sales', 'support', 'qa'];

export const options = {
  vus: 200,
  duration: '60s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<150'],
  },
};

export function setup() {
  const response = http.get(`${BASE_URL}/api/v1/jobs/map/snapshot`);
  if (response.status !== 200) throw new Error(`setup: snapshot returned ${response.status}`);
  const places = response.json('places').sort((a, b) => b.jobs - a.jobs).slice(0, 10).map((p) => p.id);
  if (places.length === 0) throw new Error('setup: empty snapshot; seed jobs first');
  const offices = (response.json('offices') || []).map((o) => o.id);
  if (offices.length === 0) console.warn('setup: no offices in the snapshot; the office leg runs as place lists');
  return { places, offices };
}

const pick = (items) => items[Math.floor(Math.random() * items.length)];

export default function (data) {
  const roll = Math.random();
  let url;
  if (roll < 0.05 && data.offices.length > 0) {
    url = `${BASE_URL}/api/v1/jobs/map/offices/${pick(data.offices)}/jobs`;
  } else if (roll < 0.7) {
    url = `${BASE_URL}/api/v1/jobs/map/places/${pick(data.places)}/jobs`;
  } else if (roll < 0.9) {
    const [lat, lng] = pick(CENTRES);
    url = `${BASE_URL}/api/v1/jobs/map/nearby?lat=${lat}&lng=${lng}&radiusKm=25`;
  } else {
    url = `${BASE_URL}/api/v1/jobs/map/places/${pick(data.places)}/jobs?q=${pick(WORDS)}`;
  }
  const response = http.get(url, { headers: { 'Accept-Encoding': 'gzip' } });
  check(response, { 'status is 200': (r) => r.status === 200 });
}
