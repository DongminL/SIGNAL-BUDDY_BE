#!/usr/bin/env node
/**
 * analyze.mjs — k6 결과 분석 스크립트
 *
 * 사용법:
 *   node scripts/analyze.mjs results/<ts>-<scenario>-<topology>/
 *
 * 출력:
 *   - 콘솔: 시나리오별 주요 메트릭 요약
 *   - results/<ts>-<scenario>-<topology>/report.md 생성
 */

import { readFileSync, writeFileSync, existsSync } from 'fs';
import { join, basename } from 'path';

const resultDir = process.argv[2];
if (!resultDir) {
  console.error('Usage: node analyze.mjs <result-dir>');
  process.exit(1);
}

const dirName = basename(resultDir);
const [, scenario, topology] = dirName.match(/^\d{8}T\d{6}Z-(.+)-(\w+)$/) ?? [null, 'unknown', 'unknown'];

// ─── summary.json 파싱 ───────────────────────────────────────────────────────
const summaryPath = join(resultDir, 'summary.json');
if (!existsSync(summaryPath)) {
  console.error(`summary.json not found in ${resultDir}`);
  process.exit(1);
}

const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
const metrics = summary.metrics ?? {};

function metricValue(name, stat = 'value') {
  const m = metrics[name];
  if (!m) return 'N/A';
  if (stat === 'p95') return (m.values?.['p(95)'] ?? 'N/A');
  if (stat === 'p99') return (m.values?.['p(99)'] ?? 'N/A');
  if (stat === 'rate') return (m.values?.rate ?? m.values?.value ?? 'N/A');
  if (stat === 'count') return (m.values?.count ?? m.values?.value ?? 'N/A');
  return m.values?.[stat] ?? m.values?.value ?? 'N/A';
}

const p95ms    = (metricValue('http_req_duration', 'p95') * 1000).toFixed(0);
const p99ms    = (metricValue('http_req_duration', 'p99') * 1000).toFixed(0);
const failRate = (metricValue('http_req_failed', 'rate') * 100).toFixed(2);
const totalReqs = metricValue('http_reqs', 'count');

// ─── evictions.csv 파싱 ──────────────────────────────────────────────────────
const evictPath = join(resultDir, 'evictions.csv');
let firstEvictionTs = null;
let evictedAtMax = 0;

if (existsSync(evictPath)) {
  const lines = readFileSync(evictPath, 'utf8').trim().split('\n');
  let prevEvicted = 0;
  for (const line of lines.slice(1)) { // skip header
    const [ts, evicted] = line.split(',');
    const e = parseInt(evicted, 10);
    if (!isNaN(e) && e > 0 && prevEvicted === 0) {
      firstEvictionTs = ts;
    }
    prevEvicted = isNaN(e) ? prevEvicted : e;
    evictedAtMax = isNaN(e) ? evictedAtMax : e;
  }
}

// ─── raw.json으로 max RPS 추출 ───────────────────────────────────────────────
const rawPath = join(resultDir, 'raw.json');
let maxRps = 'N/A';
let evictionRps = 'N/A';

if (existsSync(rawPath)) {
  const rawLines = readFileSync(rawPath, 'utf8').trim().split('\n');
  const buckets = {};

  for (const line of rawLines) {
    try {
      const obj = JSON.parse(line);
      if (obj.type === 'Point' && obj.metric === 'http_reqs') {
        const sec = Math.floor(obj.data.time / 1e9); // nanoseconds → seconds
        buckets[sec] = (buckets[sec] ?? 0) + 1;
      }
    } catch { /* skip malformed */ }
  }

  const rpsList = Object.entries(buckets).sort((a, b) => a[0] - b[0]);
  if (rpsList.length > 0) {
    maxRps = Math.max(...rpsList.map(([, v]) => v));
  }

  if (firstEvictionTs) {
    const evictionSec = Math.floor(new Date(firstEvictionTs).getTime() / 1000);
    const closest = rpsList.find(([s]) => parseInt(s) >= evictionSec);
    if (closest) evictionRps = closest[1];
  }
}

// ─── 콘솔 출력 ───────────────────────────────────────────────────────────────
console.log(`\n=== ${scenario} / ${topology} ===`);
console.log(`  p95 latency     : ${p95ms} ms`);
console.log(`  p99 latency     : ${p99ms} ms`);
console.log(`  error rate      : ${failRate} %`);
console.log(`  total requests  : ${totalReqs}`);
console.log(`  max RPS (obs.)  : ${maxRps}`);
console.log(`  first eviction  : ${firstEvictionTs ?? 'none'}`);
console.log(`  eviction RPS    : ${evictionRps}`);

// ─── report.md 생성 ──────────────────────────────────────────────────────────
const report = `# Load Test Report — ${scenario} / ${topology}

Generated: ${new Date().toISOString()}

## Summary

| Metric | Value |
|--------|-------|
| Scenario | ${scenario} |
| Topology | ${topology} |
| p95 latency | ${p95ms} ms |
| p99 latency | ${p99ms} ms |
| Error rate | ${failRate} % |
| Total requests | ${totalReqs} |
| Max observed RPS | ${maxRps} |
| First eviction at | ${firstEvictionTs ?? 'none'} |
| Eviction-trigger RPS | ${evictionRps} |

## Files

- \`summary.json\` — k6 공식 요약
- \`raw.json\` — 스트리밍 메트릭
- \`evictions.csv\` — eviction 타임라인
`;

const reportPath = join(resultDir, 'report.md');
writeFileSync(reportPath, report, 'utf8');
console.log(`\n  → report written to ${reportPath}`);
