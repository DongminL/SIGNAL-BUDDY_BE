#!/usr/bin/env node
/**
 * analyze-raw.mjs — raw.json 스트리밍 분석 (summary.json 없이)
 *
 * 사용법: node scripts/analyze-raw.mjs <result-dir>
 */

import { createReadStream } from 'fs';
import { createInterface } from 'readline';
import { join, basename } from 'path';
import { writeFileSync, existsSync } from 'fs';

const resultDir = process.argv[2];
if (!resultDir) { console.error('Usage: node analyze-raw.mjs <result-dir>'); process.exit(1); }

const rawPath = join(resultDir, 'raw.json');
if (!existsSync(rawPath)) { console.error('raw.json not found'); process.exit(1); }

const dirName = basename(resultDir);
const [, scenario, topology] = dirName.match(/^\d{8}T\d{6}Z-(.+)-(\w+)$/) ?? [null, 'unknown', 'unknown'];

// Streaming accumulators
let httpReqCount = 0;
let httpReqFailedCount = 0;
let httpReqFailedPasses = 0;
let checkPasses = 0;
let checkFails = 0;

// Duration samples for percentile calculation
const durationSamples = [];
let minTime = Infinity;
let maxTime = -Infinity;
const rpsBuckets = {}; // second → count

const rl = createInterface({ input: createReadStream(rawPath), crlfDelay: Infinity });

let linesProcessed = 0;

rl.on('line', (line) => {
  if (!line.trim()) return;
  linesProcessed++;
  try {
    const obj = JSON.parse(line);
    if (obj.type !== 'Point') return;

    const metric = obj.metric;
    const val = obj.data?.value;
    const timeStr = obj.data?.time;

    if (timeStr) {
      const ts = new Date(timeStr).getTime();
      if (ts < minTime) minTime = ts;
      if (ts > maxTime) maxTime = ts;
    }

    if (metric === 'http_reqs') {
      httpReqCount += (val ?? 1);
      if (timeStr) {
        const sec = Math.floor(new Date(timeStr).getTime() / 1000);
        rpsBuckets[sec] = (rpsBuckets[sec] ?? 0) + 1;
      }
    } else if (metric === 'http_req_failed') {
      if (val === 1) httpReqFailedCount++;
      else httpReqFailedPasses++;
    } else if (metric === 'checks') {
      if (val === 1) checkPasses++;
      else checkFails++;
    } else if (metric === 'http_req_duration') {
      // Only sample 1 in 10 to keep memory manageable
      if (linesProcessed % 10 === 0) {
        durationSamples.push(val);
      }
    }
  } catch { /* skip malformed */ }
});

rl.on('close', () => {
  // Compute percentiles from sampled durations
  durationSamples.sort((a, b) => a - b);
  const p = (pct) => {
    if (durationSamples.length === 0) return 0;
    const idx = Math.ceil(durationSamples.length * pct / 100) - 1;
    return durationSamples[Math.max(0, idx)];
  };

  const avgDuration = durationSamples.length > 0
    ? durationSamples.reduce((s, v) => s + v, 0) / durationSamples.length
    : 0;

  const totalDurationSec = (maxTime - minTime) / 1000;
  const rpsAvg = totalDurationSec > 0 ? httpReqCount / totalDurationSec : 0;

  const rpsList = Object.values(rpsBuckets);
  const maxRps = rpsList.length > 0 ? Math.max(...rpsList) : 0;

  const failRate = httpReqCount > 0 ? httpReqFailedCount / httpReqCount : 0;

  console.log(`\n=== ${scenario} / ${topology} (from raw.json) ===`);
  console.log(`  total requests  : ${httpReqCount}`);
  console.log(`  error rate      : ${(failRate * 100).toFixed(2)} %`);
  console.log(`  avg duration    : ${avgDuration.toFixed(1)} ms`);
  console.log(`  p95 duration    : ${p(95).toFixed(1)} ms`);
  console.log(`  p99 duration    : ${p(99).toFixed(1)} ms`);
  console.log(`  avg RPS         : ${rpsAvg.toFixed(1)}`);
  console.log(`  max RPS (1s)    : ${maxRps}`);
  console.log(`  test duration   : ${(totalDurationSec / 60).toFixed(1)} min`);
  console.log(`  check passes    : ${checkPasses}`);
  console.log(`  check fails     : ${checkFails}`);

  // Write a synthetic summary.json
  const fakeSummary = {
    metrics: {
      http_reqs: { count: httpReqCount, rate: rpsAvg },
      http_req_failed: {
        passes: httpReqFailedPasses,
        fails: httpReqFailedCount,
        value: failRate,
      },
      http_req_duration: {
        avg: avgDuration,
        min: durationSamples[0] ?? 0,
        max: durationSamples[durationSamples.length - 1] ?? 0,
        'p(90)': p(90),
        'p(95)': p(95),
        'p(99)': p(99),
      },
      checks: { passes: checkPasses, fails: checkFails, value: checkPasses / (checkPasses + checkFails || 1) },
    },
    _note: 'Generated from raw.json (k6 force-killed after test; failover @Retryable retry chains extended graceful-stop)',
  };

  const summaryPath = join(resultDir, 'summary.json');
  writeFileSync(summaryPath, JSON.stringify(fakeSummary, null, 2), 'utf8');
  console.log(`\n  → summary.json written to ${summaryPath}`);
});
