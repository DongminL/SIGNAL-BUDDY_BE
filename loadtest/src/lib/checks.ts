import { Thresholds } from 'k6/options';

export const defaultThresholds: Thresholds = {
  http_req_failed: ['rate<0.05'],
  http_req_duration: ['p(95)<800', 'p(99)<2000'],
};

// Relaxed thresholds for the failover scenario — allows up to 10% errors
// during the Sentinel failover window (~5–10 s).
export const failoverThresholds: Thresholds = {
  http_req_failed: ['rate<0.10'],
  http_req_duration: ['p(95)<2000', 'p(99)<5000'],
};
