import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEED_USER_COUNT = 4000;

export interface AuthData {
  token: string;
}

export function login(email: string, password: string): AuthData {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ id: email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status !== 200) {
    throw new Error(`Login failed: status=${res.status} body=${res.body}`);
  }

  // Token is returned in the Authorization response header (e.g. "Bearer eyJ...")
  const token = res.headers['Authorization'] || res.headers['authorization'];
  if (!token) {
    throw new Error('Authorization header missing from login response');
  }

  return { token };
}

// Each VU authenticates as its own seed user (test001 ~ test1000) so that
// concurrent VUs never share a (member_id, feedback_id) pair and duplicate
// like errors cannot occur.
export function loginByVu(): AuthData {
  const n = ((__VU - 1) % SEED_USER_COUNT) + 1;
  const email = `test${String(n).padStart(4, '0')}@signal-buddy.com`;
  return login(email, __ENV.TEST_PASSWORD || 'password');
}
