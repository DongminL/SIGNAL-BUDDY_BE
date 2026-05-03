import { Options } from 'k6/options';
import { failoverThresholds } from '../lib/checks';
import { runLikePost } from '../lib/runners';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const targetRps = parseInt(__ENV.TARGET_RPS || '200');

// Sentinel-only staged scenario.
// run-failover-sentinel.sh --staged kills redis-master at t=120s per stage.
export const options: Options = {
  scenarios: {
    steady_load: {
      executor: 'ramping-arrival-rate',
      startRate: targetRps,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { duration: '2m', target: targetRps },  // warm up — master killed at t=120s
        { duration: '8m', target: targetRps },  // observe failover recovery
        { duration: '1m', target: 50 },         // wind down
      ],
    },
  },
  thresholds: failoverThresholds,
};

export default function (): void {
  runLikePost(BASE_URL);
}
