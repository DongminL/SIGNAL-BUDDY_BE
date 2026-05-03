import { Options } from 'k6/options';
import { defaultThresholds } from '../lib/checks';
import { runLikeToggle } from '../lib/runners';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const targetRps = parseInt(__ENV.TARGET_RPS || '200');
const duration = __ENV.DURATION || '3m';

export const options: Options = {
  scenarios: {
    like_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: targetRps,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 600,
      stages: [{ duration, target: targetRps }],
    },
  },
  thresholds: defaultThresholds,
};

export default function (): void {
  runLikeToggle(BASE_URL);
}
