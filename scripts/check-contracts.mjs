import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import openapiTS, { astToString } from 'openapi-typescript';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root = new URL('../', import.meta.url);
const spec = JSON.parse(await readFile(new URL('contracts/openapi.json', root), 'utf8'));
const generated = astToString(await openapiTS(new URL('contracts/openapi.json', root)));
const existing = await readFile(new URL('frontend/src/api/schema.d.ts', root), 'utf8');
// CLI prepends its generated-file banner; compare the actual declarations.
assert.equal(existing.slice(existing.indexOf('export interface paths')), generated.slice(generated.indexOf('export interface paths')), 'Generated TypeScript drift: npm run contracts:generate');

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'https://worldcup.local/contract', ...spec });
const cases = [['preview-8.json', 'Preview'], ['preview-image-8.json', 'Preview'], ['snapshot-8.json', 'Snapshot'], ['job-ready.json', 'GenerationJob'], ['error-quality.json', 'ApiError']];
for (const [file, schema] of cases) {
  const data = JSON.parse(await readFile(new URL(`contracts/fixtures/${file}`, root), 'utf8'));
  const validate = ajv.getSchema(`https://worldcup.local/contract#/components/schemas/${schema}`);
  assert(validate(data), `${file}: ${JSON.stringify(validate.errors)}`);
  // Verify the checker actually rejects an invalid required field.
  const invalid = { ...data }; delete invalid[spec.components.schemas[schema].required[0]];
  assert.equal(validate(invalid), false, `${file}: required field must be enforced`);
  if (data.candidates) {
    assert.equal(data.candidates.length, data.size);
    assert.equal(new Set(data.candidates.map(c => c.id)).size, data.size);
    const invalidImage = structuredClone(data);
    invalidImage.candidates[0].imageUrl = 'HTTPS://example.invalid/image.png';
    assert.equal(validate(invalidImage), false, 'Image scheme must match the canonical contract casing');
  }
  if (data.initialOrder) {
    assert.deepEqual([...data.initialOrder].sort(), data.candidates.map(c => c.id).sort());
  }
}
console.log(`Contract v${spec.info.version}: generated types and ${cases.length} fixtures verified.`);

if (process.argv.includes('--http')) {
  const samples = JSON.parse(await readFile(new URL('backend/build/contract-http-samples.json', root), 'utf8'));
  const expected = ['GenerationJob', 'Preview', 'Snapshot', 'SessionStart', 'SelectionAck', 'ShareCreated', 'SharedBracket', 'ApiError', 'Readiness'];
  for (const schema of expected) assert(samples.some(sample => sample.schema === schema), `Missing real HTTP sample: ${schema}`);
  for (const { schema, value } of samples) {
    const validate = ajv.getSchema(`https://worldcup.local/contract#/components/schemas/${schema}`);
    assert(validate(value), `HTTP ${schema}: ${JSON.stringify(validate.errors)}`);
    const snapshot = schema === 'Snapshot' ? value : value.snapshot;
    if (snapshot) {
      assert.equal(snapshot.candidates.length, snapshot.size);
      assert.deepEqual([...snapshot.initialOrder].sort(), snapshot.candidates.map(candidate => candidate.id).sort());
    }
    if (schema === 'Preview') assert.equal(value.candidates.length, value.size);
  }
  console.log(`Actual HTTP responses: ${samples.length} samples across ${expected.length} schemas verified.`);
}
