import { Options } from 'k6/options';
import { failoverThresholds } from '../lib/checks';
import { runLikePost } from '../lib/runners';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Sentinel-only scenario.
// run.sh kills redis-master at t=120 s (end of warm-up).
// Error rate during the ~5–10 s failover window is expected and tolerated
// by failoverThresholds (< 10%).
export const options: Options = {
  scenarios: {
    steady_load: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { duration: '2m', target: 200 },  // warm up — master killed here by run.sh at t=120s
        { duration: '8m', target: 200 },  // observe failover recovery
        { duration: '3m', target: 200 },  // steady post-failover
        { duration: '2m', target: 50 },   // wind down
      ],
    },
  },
  thresholds: failoverThresholds,
};

export default function (): void {
  runLikePost(BASE_URL);
}
