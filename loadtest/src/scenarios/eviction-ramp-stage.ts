import { Options } from 'k6/options';
import { runLikePost } from '../lib/runners';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const targetRps = parseInt(__ENV.TARGET_RPS || '300');
const duration = __ENV.DURATION || '4m';

// No thresholds — measurement-only stage run.
export const options: Options = {
  scenarios: {
    eviction_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: targetRps,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1200,
      stages: [{ duration, target: targetRps }],
    },
  },
};

export default function (): void {
  runLikePost(BASE_URL);
}
