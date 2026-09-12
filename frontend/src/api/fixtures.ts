import preview from '../../../contracts/fixtures/preview-8.json';
import snapshot from '../../../contracts/fixtures/snapshot-8.json';
import type { components } from './schema';

// JSON literal enums are widened by TypeScript. scripts/check-contracts.mjs
// validates both these fixtures against the source OpenAPI schema before build.
export const fixturePreview = preview as components['schemas']['Preview'];
export const fixtureSnapshot = snapshot as components['schemas']['Snapshot'];
