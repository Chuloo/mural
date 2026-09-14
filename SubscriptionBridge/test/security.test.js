import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, symlink, writeFile, chmod, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { constantTimeToken, isolationConfig, loadPairingToken, requireLoopback } from "../lib/security.js";
import { assertSchema } from "../lib/schema.js";

test("pairing token comparison rejects missing, wrong length, and wrong values", () => {
  assert.equal(constantTimeToken("secret", "secret"), true);
  assert.equal(constantTimeToken("secret", "secret2"), false);
  assert.equal(constantTimeToken(undefined, "secret"), false);
});

test("isolation disables high-risk capabilities and keeps workspace read-only", () => {
  const config = isolationConfig("/tmp/mural-bridge-test");
  assert.equal(config.config.features.shell_tool, false);
  assert.equal(config.config.features.plugins, false);
  assert.equal(config.config.features.apps, false);
  assert.equal(config.config.features.multi_agent, false);
  assert.equal(config.permissions, "mural-isolated");
  assert.equal(config.config.permissions["mural-isolated"].filesystem[":root"], "deny");
  assert.equal(config.config.permissions["mural-isolated"].filesystem[":minimal"], "read");
  assert.equal(config.config.permissions["mural-isolated"].network.enabled, false);
  assert.deepEqual(config.dynamicTools, []);
});

test("bridge refuses non-loopback binding", () => {
  assert.throws(() => requireLoopback("0.0.0.0"), /loopback/);
  assert.doesNotThrow(() => requireLoopback("127.0.0.1"));
});

test("schema validator enforces strict object, array, enum, and bounds", () => {
  const schema = { type: "object", additionalProperties: false, required: ["kind", "items"], properties: { kind: { type: "string", enum: ["ok"] }, items: { type: "array", minItems: 1, maxItems: 2, items: { type: "integer", minimum: 1, maximum: 4 } } } };
  assert.doesNotThrow(() => assertSchema({ kind: "ok", items: [1, 4] }, schema));
  assert.throws(() => assertSchema({ kind: "bad", items: [] }, schema), /failed/);
  assert.throws(() => assertSchema({ kind: "ok", items: [1], extra: true }, schema), /failed/);
  assert.throws(() => assertSchema(JSON.parse('{"kind":"ok","items":[1],"toString":"extra"}'), schema), /failed/);
  assert.throws(() => assertSchema({}, { type: "object", properties: { toString: { type: "string" } }, required: ["toString"], additionalProperties: false }), /failed/);
});

test("pairing token loader rejects symlinks and broad permissions", async () => {
  const directory = await mkdtemp(join(tmpdir(), "mural-token-test-"));
  const token = join(directory, "token");
  await writeFile(token, "01234567890123456789012345678901\n", { mode: 0o600 });
  assert.equal(await loadPairingToken(token), "01234567890123456789012345678901");
  await chmod(token, 0o644);
  await assert.rejects(() => loadPairingToken(token), /0600/);
  const link = join(directory, "link");
  await symlink(token, link);
  await assert.rejects(() => loadPairingToken(link), /regular file/);
  await rm(directory, { recursive: true, force: true });
});
