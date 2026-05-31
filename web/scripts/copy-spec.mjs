// Stage the canonical OpenAPI spec as a web-local file so codegen never has to
// cross the web/ build-context boundary (Option C). codegen reads ./openapi.json;
// this script keeps that file in sync with the canonical source.
//
// Three contexts must all work:
//   * LOCAL / CI  — full repo on disk: ../api/.../openapi.json exists, so copy it.
//   * Docker web-build stage — only web/ is in the stage, but the Dockerfile
//     COPYs the spec to web/openapi.json before `npm run build`, so the source is
//     absent yet ./openapi.json already exists: use it as-is.
//   * Neither present — fail loudly with remediation instead of a confusing
//     downstream codegen ENOENT.
import { existsSync, copyFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.resolve(webRoot, '../api/src/main/resources/openapi/openapi.json');
const dest = path.resolve(webRoot, 'openapi.json');

if (existsSync(source)) {
  copyFileSync(source, dest);
  console.log('[copy-spec] copied canonical api spec -> web/openapi.json');
} else if (existsSync(dest)) {
  console.log('[copy-spec] canonical api spec not in context (Docker web-build stage); using existing web/openapi.json');
} else {
  console.error(
    '[copy-spec] ERROR: no OpenAPI spec found.\n' +
      '  Expected either ../api/src/main/resources/openapi/openapi.json (run `mvn -pl api verify`)\n' +
      '  or a pre-staged web/openapi.json (Docker web-build stage COPYs it before npm run build).'
  );
  process.exit(1);
}
