// Fails when src/api/schema.d.ts is not what openapi.json generates (the snapshot changed without regenerating).
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const COMMITTED = 'src/api/schema.d.ts';
const dir = mkdtempSync(join(tmpdir(), 'graphify-schema-'));
try {
  const generated = join(dir, 'schema.d.ts');
  const run = spawnSync('npx', ['--no-install', 'openapi-typescript', 'openapi.json', '-o', generated], {
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  if (run.status !== 0) {
    process.exit(run.status ?? 1);
  }
  if (readFileSync(generated, 'utf8') !== readFileSync(COMMITTED, 'utf8')) {
    console.error(`${COMMITTED} is stale: run npm run generate:api`);
    process.exit(1);
  }
} finally {
  rmSync(dir, { recursive: true, force: true });
}
