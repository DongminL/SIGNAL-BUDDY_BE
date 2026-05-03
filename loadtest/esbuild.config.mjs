import * as esbuild from 'esbuild';
import { readdirSync } from 'fs';
import { join, extname, basename } from 'path';

const scenarioDir = './src/scenarios';
const entryPoints = readdirSync(scenarioDir)
  .filter((f) => extname(f) === '.ts')
  .map((f) => join(scenarioDir, f));

await esbuild.build({
  entryPoints,
  bundle: true,
  outdir: 'dist',
  format: 'cjs',
  platform: 'neutral',
  external: ['k6', 'k6/*'],
  target: 'es2017',
  sourcemap: false,
});

console.log(`Built ${entryPoints.length} scenario(s): ${entryPoints.map((f) => basename(f)).join(', ')}`);
