#!/usr/bin/env node
// Golden-corpus check for the JS preview renderer.
//
// Runs `src/main/resources/preview/ddot-preview.js` (renderBody) against
// every case in the cross-implementation corpus at
// `../ddot.it/test-data/cases/` and asserts byte-identical output with
// `expected.body.html` — the same contract PreviewHtmlGoldenTest enforces
// for the Java renderer, so the JS can never silently drift again.
//
// Usage:
//   node scripts/test-preview-js.mjs
//   DDOT_TEST_DATA_DIR=/abs/path/to/ddot.it/test-data node scripts/test-preview-js.mjs
//
// Exit code 0 = all cases match; 1 = at least one mismatch (diff printed).
// Not wired into the Maven build on purpose: the build must not require a
// Node installation. Run it whenever ddot-preview.js or the corpus changes.

import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const jsPath = join(repoRoot, "src", "main", "resources", "preview", "ddot-preview.js");
const testDataDir = process.env.DDOT_TEST_DATA_DIR
  || join(repoRoot, "..", "ddot.it", "test-data");
const casesDir = join(testDataDir, "cases");

if (!existsSync(casesDir)) {
  console.error(`Corpus not found at ${casesDir} — clone https://github.com/calpano/ddot.it`
    + ` as a sibling of this repo, or set DDOT_TEST_DATA_DIR.`);
  process.exit(1);
}

// ddot-preview.js is a browser IIFE; run it in a sandbox with a stub DOM.
// It exposes its pure renderer as globalThis.ddotPreview.renderBody.
const sandbox = {
  document: {
    readyState: "complete",
    addEventListener() {},
    querySelectorAll() { return []; },
    body: null,
    documentElement: null
  },
  console
};
vm.createContext(sandbox);
vm.runInContext(readFileSync(jsPath, "utf8"), sandbox, { filename: jsPath });
const renderBody = sandbox.ddotPreview && sandbox.ddotPreview.renderBody;
if (typeof renderBody !== "function") {
  console.error("ddot-preview.js did not expose globalThis.ddotPreview.renderBody");
  process.exit(1);
}

let failures = 0;
let checked = 0;
for (const name of readdirSync(casesDir).sort()) {
  const caseDir = join(casesDir, name);
  if (!statSync(caseDir).isDirectory()) continue;
  const inputPath = join(caseDir, "input.ddot");
  const expectedPath = join(caseDir, "expected.body.html");
  if (!existsSync(inputPath) || !existsSync(expectedPath)) {
    console.warn(`skip  ${name}: missing input.ddot or expected.body.html`);
    continue;
  }
  checked++;
  const actual = renderBody(readFileSync(inputPath, "utf8"));
  const expected = readFileSync(expectedPath, "utf8");
  if (actual === expected) {
    console.log(`ok    ${name}`);
  } else {
    failures++;
    console.error(`FAIL  ${name}`);
    const a = actual.split("\n");
    const e = expected.split("\n");
    for (let i = 0; i < Math.max(a.length, e.length); i++) {
      if (a[i] !== e[i]) {
        console.error(`  line ${i + 1}`);
        console.error(`    expected: ${e[i] === undefined ? "<missing>" : e[i]}`);
        console.error(`    actual  : ${a[i] === undefined ? "<missing>" : a[i]}`);
      }
    }
  }
}

if (failures > 0) {
  console.error(`\n${failures}/${checked} case(s) diverged from the corpus.`);
  process.exit(1);
}
console.log(`\nAll ${checked} cases match.`);
