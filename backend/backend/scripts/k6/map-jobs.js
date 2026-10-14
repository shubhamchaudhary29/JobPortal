// Load check for the GEO-3 map job lists (local, not CI), through nginx.
// Seed first: python3 backend/backend/scripts/seed-map-jobs.py | docker compose exec -T mongodb mongosh --quiet jobportal
// docker run --rm -i --network host -e BASE_URL=http://localhost grafana/k6 run - < backend/backend/scripts/k6/map-jobs.js
// Mix: 70% place lists for the top places (cache-friendly), 20% nearby around fixed city centres,
// 10% place lists with a search word (more distinct keys, so more cache misses).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
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
  return { places };
}

const pick = (items) => items[Math.floor(Math.random() * items.length)];

export default function (data) {
  const roll = Math.random();
  let url;
  if (roll < 0.7) {
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
