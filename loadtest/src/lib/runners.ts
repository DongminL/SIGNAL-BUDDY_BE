import http from 'k6/http';
import { check } from 'k6';
import { loginByVu, AuthData } from './auth';
import { feedbackId, latLng } from './data';

// Per-VU state — k6 gives each VU its own module instance
let _auth: AuthData | null = null;
const likedFids = new Set<number>();

function getAuth(): AuthData {
  if (!_auth) _auth = loginByVu();
  return _auth;
}

// Like toggle: POST first visit, DELETE on repeat (prevents 409 conflicts)
export function runLikeToggle(baseUrl: string): void {
  const fid = feedbackId(__VU, __ITER);
  const headers = { Authorization: getAuth().token, 'Content-Type': 'application/json' };

  if (likedFids.has(fid)) {
    const res = http.del(`${baseUrl}/api/feedbacks/${fid}/like`, null, { headers });

    check(res, { 'like DELETE 2xx': (r) => r.status >= 200 && r.status < 500 });

    if (res.status >= 200 && res.status < 300) likedFids.delete(fid);
  } else {
    const res = http.post(`${baseUrl}/api/feedbacks/${fid}/like`, null, { headers });

    check(res, { 'like POST 2xx': (r) => r.status >= 200 && r.status < 500 });

    if (res.status >= 200 && res.status < 300) likedFids.add(fid);
  }
}

// Mixed: 60% like toggle + 40% geo lookup
export function runMixed(baseUrl: string): void {
  const vu = __VU;
  const iter = __ITER;
  const roll = ((vu * 31 + iter * 7) >>> 0) % 10;

  if (roll < 6) {
    runLikeToggle(baseUrl);
  } else {
    const { lat, lng } = latLng(vu, iter);
    const res = http.get(`${baseUrl}/api/crossroads/around?lat=${lat}&lng=${lng}`);

    check(res, { 'geo 2xx': (r) => r.status >= 200 && r.status < 500 });
  }
}

// Pure like POST (no toggle) — used by eviction-ramp and failover
export function runLikePost(baseUrl: string): void {
  const fid = feedbackId(__VU, __ITER);
  const headers = { Authorization: getAuth().token, 'Content-Type': 'application/json' };
  const res = http.post(`${baseUrl}/api/feedbacks/${fid}/like`, null, { headers });

  check(res, { 'like 2xx': (r) => r.status >= 200 && r.status < 500 });
}
