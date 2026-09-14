import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { SubscriptionBridge } from "../bridge.js";
import { createBridgeServer } from "../bridge.js";
import { CodexConnection } from "../lib/codex.js";

function fakeConnection() {
  return {
    async open() { return { type: "chatgpt", planType: "plus" }; },
    async listVoices() { return { defaultVoice: "cove", voices: ["cove", "juniper", "ember"] }; },
    async startThread() { return "thread-test"; },
    async realtimeStart(_thread, sdp, _instructions, voice) { this.selectedVoice = voice; return `${sdp}\nanswer`; },
    subscribe(listener) { this.notify = listener; return () => {}; },
    async append(_thread, kind, text) { this.last = { kind, text }; },
    async stopRealtime() {},
    async close() {},
    async response() { return { status: "completed", output: [{ type: "message", content: [{ type: "output_text", text: "ok" }] }], usage: { input_tokens: 2, output_tokens: 1, total_tokens: 3 } }; }
  };
}

test("live session contract preserves SDP and maps allowed events", async () => {
  const bridge = new SubscriptionBridge({ connectionFactory: fakeConnection, clock: () => 1 });
  const created = await bridge.createSession({ session: { instructions: "teach", audio: { output: { voice: "cove" } } }, transport: { type: "webrtc", sdp: "v=0\r\n" } });
  assert.equal(created.session.model, "gpt-live-1-codex");
  assert.match(created.transport.sdp, /answer/);
  const appended = await bridge.append(created.session.id, { type: "session.thinking.append", content: "next", event_id: "e1" });
  assert.deepEqual(appended.event, { type: "session.thinking.appended", client_event_id: "e1" });
  await assert.rejects(() => bridge.append(created.session.id, { type: "session.unknown.append", content: "x" }), /invalid/);
  await bridge.close(created.session.id);
});

test("voice selection uses the live catalog and never silently substitutes a rejected voice", async () => {
  let connection;
  const bridge = new SubscriptionBridge({ connectionFactory: () => (connection = fakeConnection()) });
  assert.deepEqual(await bridge.account(), { type: "chatgpt", planType: "plus", defaultVoice: "cove", voices: ["cove", "juniper", "ember"] });
  const session = await bridge.createSession({ session: { instructions: "teach", audio: { output: { voice: "ember" } } }, transport: { sdp: "v=0" } });
  assert.equal(connection.selectedVoice, "ember");
  assert.equal(session.session.voice, "ember");
  await bridge.close(session.session.id);
  await assert.rejects(() => bridge.createSession({ session: { instructions: "teach", audio: { output: { voice: "marin" } } }, transport: { sdp: "v=0" } }), error => error.statusCode === 422);
  assert.equal(bridge.activeLive, 0);
});

test("responses enforce model and preserve actual usage", async () => {
  const bridge = new SubscriptionBridge({ connectionFactory: fakeConnection });
  const result = await bridge.responses({ model: "gpt-5.6-luna", instructions: "teach", input: [{ role: "user", content: [{ type: "input_text", text: "hi" }] }] });
  assert.equal(result.status, "completed");
  assert.equal(result.output_text, "ok");
  assert.deepEqual(result.usage, { input_tokens: 2, output_tokens: 1, total_tokens: 3 });
  await assert.rejects(() => bridge.responses({ model: "gpt-4o", input: "x" }), /unsupported model/);
});

test("mural_support dynamic tool creates a client delegation and accepts only matching commentary", async () => {
  let connection;
  const bridge = new SubscriptionBridge({ connectionFactory: () => (connection = fakeConnection()) });
  const created = await bridge.createSession({ session: { instructions: "teach" }, transport: { sdp: "v=0\r\n" } });
  connection.notify({ method: "turn/started", params: { threadId: "thread-test", turn: { id: "turn-1", status: "inProgress", items: [] } } });
  const waiting = connection.onServerRequest({ id: 9, method: "item/tool/call", params: { threadId: "thread-test", turnId: "turn-1", namespace: null, tool: "mural_support", callId: "call-1", arguments: {} } });
  const pendingEvents = await bridge.events(created.session.id, 0);
  const delegation = pendingEvents.events.find((event) => event.type === "session.delegation.created");
  assert.equal(delegation.delegation.target, "client");
  const ack = await bridge.append(created.session.id, { type: "session.commentary.append", content: "verified help", delegation_id: delegation.delegation.id });
  assert.equal(ack.event.type, "session.commentary.appended");
  assert.deepEqual(await waiting, { success: true, contentItems: [{ type: "inputText", text: "verified help" }] });
  await bridge.close(created.session.id);
});

test("making search available does not fabricate citations when no search occurred", async () => {
  const bridge = new SubscriptionBridge({ connectionFactory: fakeConnection });
  const result = await bridge.responses({ model: "gpt-5.6-luna", instructions: "Help with language; search only when needed", input: "hello", tools: [{ type: "web_search" }] });
  assert.equal(result.output[0].content[0].annotations, undefined);
  // The original Swift currentTopic() still requires nonempty verified sources for a sourced topic.
});

test("HTTP rejects missing or wrong pairing token before protected routes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "mural-bridge-http-"));
  const token = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJ";
  const tokenFile = join(directory, "token");
  await writeFile(tokenFile, `${token}\n`, { mode: 0o600 });
  const app = await createBridgeServer({ tokenFile, port: 0, bridge: { account: async () => ({ type: "chatgpt", planType: "plus" }) } });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  const address = app.server.address();
  const base = `http://127.0.0.1:${address.port}`;
  const missing = await fetch(`${base}/v1/account`);
  assert.equal(missing.status, 401);
  const wrong = await fetch(`${base}/v1/account`, { headers: { authorization: "Bearer wrong-token-that-is-long-enough" } });
  assert.equal(wrong.status, 401);
  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  await new Promise((resolve) => app.server.close(resolve));
  await rm(directory, { recursive: true, force: true });
});

test("JSONL RPC waits for turn/completed and maps Codex input and isolation", async () => {
  const writes = [];
  const child = new EventEmitter();
  child.exitCode = null;
  child.stdout = new PassThrough();
  child.stdin = new Writable({ write(chunk, _encoding, callback) {
    const message = JSON.parse(chunk.toString("utf8")); writes.push(message);
    const result = message.method === "initialize" ? {} : message.method === "config/read" ? { config: { mcp_servers: {} } } : message.method === "account/read" ? { account: { type: "chatgpt", planType: "plus" } } : message.method === "thread/start" ? { thread: { id: "thread-rpc" }, model: "gpt-5.6-luna", reasoningEffort: "low", activePermissionProfile: { id: "mural-isolated" }, approvalPolicy: "on-request", sandbox: { type: "readOnly", networkAccess: false }, serviceTier: null } : message.method === "turn/start" ? { turn: { id: "turn-rpc", status: "inProgress" } } : {};
    setTimeout(() => {
      child.stdout.write(`${JSON.stringify({ id: message.id, result })}\n`);
      if (message.method === "turn/start") {
        child.stdout.write(`${JSON.stringify({ method: "item/completed", params: { threadId: "thread-rpc", turnId: "turn-rpc", item: { id: "message-1", type: "agentMessage", text: "hello", phase: "final_answer" } } })}\n`);
        child.stdout.write(`${JSON.stringify({ method: "thread/tokenUsage/updated", params: { threadId: "thread-rpc", turnId: "turn-rpc", tokenUsage: { total: { inputTokens: 3, outputTokens: 2, totalTokens: 5 }, last: { inputTokens: 3, outputTokens: 2, totalTokens: 5 }, modelContextWindow: null } } })}\n`);
        child.stdout.write(`${JSON.stringify({ method: "turn/completed", params: { threadId: "thread-rpc", turnId: "turn-rpc", turn: { status: "completed" } } })}\n`);
      }
    }, 20); callback();
  } });
  child.kill = () => { child.exitCode = 0; process.nextTick(() => child.emit("close")); };
  let launchArgs;
  const connection = new CodexConnection({ spawnImpl: (_executable, args) => { launchArgs = args; return child; }, cwd: "/tmp" });
  await connection.open();
  const result = await connection.response({ instructions: "follow this", input: [{ role: "user", content: [{ type: "input_text", text: "question" }] }], reasoning: { effort: "low" }, text: { format: { type: "json_schema", schema: { type: "object" } } } });
  assert.equal(result.status, "completed");
  assert.equal(result.output[0].content[0].text, "hello");
  const turn = writes.find((message) => message.method === "turn/start");
  assert.deepEqual(turn.params.input, [{ type: "text", text: "question", text_elements: [] }]);
  assert.deepEqual(turn.params.outputSchema, { type: "object" });
  assert.ok(launchArgs.includes('default_permissions="mural-isolated"'));
  assert.ok(launchArgs.includes('permissions.mural-isolated.network.enabled=false'));
  assert.equal(writes.find(message => message.method === "thread/start").params.permissions, "mural-isolated");
  assert.equal(result.usage.input_tokens, 3);
  assert.equal(writes.find(message => message.method === "thread/start").params.developerInstructions.includes("follow this"), true);
});

test("JSONL server request is rejected and closes the connection", async () => {
  const child = new EventEmitter(); child.exitCode = null; child.stdout = new PassThrough(); child.stdin = new Writable({ write(_chunk, _encoding, callback) { callback(); } }); child.kill = () => { child.exitCode = 0; process.nextTick(() => child.emit("close")); };
  const connection = new CodexConnection({ spawnImpl: () => child, cwd: "/tmp" });
  const opened = connection.open();
  child.stdout.write(`${JSON.stringify({ id: 1, method: "item/commandExecution/requestApproval", params: {} })}\n`);
  await assert.rejects(opened, /server request rejected|connection closed/);
  await connection.close();
});
