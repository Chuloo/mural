import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import { CodexConnection, TurnCollector, toCodexInput } from "../lib/codex.js";
import { SubscriptionBridge } from "../bridge.js";

const isolation = {
  model: "gpt-5.6-luna",
  modelProvider: "openai",
  serviceTier: "default",
  approvalPolicy: "on-request",
  reasoningEffort: "low",
  activePermissionProfile: { id: "mural-isolated" },
  sandbox: { type: "readOnly", networkAccess: false },
};

function fakeCodex({ startResult = {}, turnEvents = [], turnResult = { id: "turn-1" }, configResult = { config: { mcp_servers: {} } }, emitServerRequest } = {}) {
  const writes = [];
  const child = new EventEmitter();
  child.exitCode = null;
  child.stdout = new PassThrough();
  child.stdin = new Writable({
    write(chunk, _encoding, callback) {
      const message = JSON.parse(chunk.toString("utf8"));
      writes.push(message);
      const result = message.method === "initialize" ? {}
        : message.method === "config/read" ? configResult
        : message.method === "account/read" ? { account: { type: "chatgpt", planType: "plus" } }
        : message.method === "thread/start" ? { thread: { id: "thread-1" }, ...isolation, ...startResult }
        : message.method === "turn/start" ? { turn: { status: "inProgress", ...turnResult } }
        : {};
      setImmediate(() => {
        if (message.method === "item/tool/call" && emitServerRequest) emitServerRequest(message);
        child.stdout.write(`${JSON.stringify({ id: message.id, result })}\n`);
        if (message.method === "turn/start") {
          for (const event of turnEvents) child.stdout.write(`${JSON.stringify(event)}\n`);
        }
      });
      callback();
    },
  });
  child.kill = () => {
    child.exitCode = 0;
    setImmediate(() => child.emit("close"));
  };
  return { child, writes };
}

function connectionFor(options) {
  const fake = fakeCodex(options);
  return { fake, connection: new CodexConnection({ spawnImpl: () => fake.child, cwd: "/tmp" }) };
}

test("RPC batch preserves agent item, text_elements, search source and usage", async () => {
  const events = [
    { method: "item/completed", params: { threadId: "thread-1", turnId: "turn-1", item: { type: "agentMessage", id: "m-1", text: "答案", phase: "final_answer" } } },
    { method: "item/completed", params: { threadId: "thread-1", turnId: "turn-1", item: { type: "webSearch", id: "search-1", results: [{ url: "https://docs.example/source", title: "真实来源" }] } } },
    { method: "rawResponseItem/completed", params: { threadId: "thread-1", turnId: "turn-1", item: { content: [{ annotations: [{ type: "url_citation", url: "https://annotated.example/page", title: "注释来源" }] }] } } },
    { method: "thread/tokenUsage/updated", params: { threadId: "thread-1", turnId: "turn-1", tokenUsage: { total: { inputTokens: 11, outputTokens: 7, totalTokens: 18 }, last: { inputTokens: 4, outputTokens: 3, totalTokens: 7 } } } },
    { method: "turn/completed", params: { threadId: "thread-1", turnId: "turn-1", turn: { id: "turn-1", status: "completed" } } },
  ];
  const { fake, connection } = connectionFor({ turnEvents: events });
  await connection.open();
  const result = await connection.response({
    instructions: "回答问题",
    input: [{ role: "user", content: [{ type: "input_text", text: "你好" }] }],
    text: { format: { type: "json_schema", schema: { type: "object", properties: {}, required: [], additionalProperties: false } } },
  });
  const turn = fake.writes.find(message => message.method === "turn/start");
  assert.deepEqual(turn.params.input, [{ type: "text", text: "你好", text_elements: [] }]);
  assert.deepEqual(turn.params.outputSchema, { type: "object", properties: {}, required: [], additionalProperties: false });
  const answer = result.output.find(item => item.type === "message");
  assert.equal(answer.content[0].text, "答案");
  assert.deepEqual(result.usage, { input_tokens: 11, output_tokens: 7, total_tokens: 18 });
  assert.equal(answer.content[0].annotations.length, 2);
  assert.deepEqual(answer.content[0].annotations.map(item => item.url), ["https://docs.example/source", "https://annotated.example/page"]);
  await connection.close();
});

test("text input follows the generated UserInput union and rejects non-user content", () => {
  assert.deepEqual(toCodexInput("hello"), [{ type: "text", text: "hello", text_elements: [] }]);
  assert.deepEqual(toCodexInput([{ role: "user", content: [{ type: "input_text", text: "one" }, "two"] }]), [
    { type: "text", text: "one", text_elements: [] }, { type: "text", text: "two", text_elements: [] },
  ]);
  assert.throws(() => toCodexInput([{ role: "assistant", content: "no" }]), /only classroom user text/);
});

test("thread start rejects an actual profile, model, or effort mismatch", async () => {
  for (const mismatch of [{ model: "gpt-4o" }, { reasoningEffort: "high" }, { activePermissionProfile: { id: "default" } }]) {
    const { connection } = connectionFor({ startResult: mismatch });
    await connection.open();
    await assert.rejects(() => connection.startThread(), /isolation and model/);
    await connection.close();
  }
});

test("a missing or non-completed turn status cannot become a successful response", async () => {
  for (const turn of [{ status: "inProgress" }, {}]) {
    const { connection } = connectionFor({ turnResult: turn, turnEvents: [{ method: "turn/completed", params: { threadId: "thread-1", turnId: "turn-1", turn } }] });
    await connection.open();
    await assert.rejects(() => connection.response({ instructions: "x", input: "hello" }), /failed|incomplete|complete|turn handle/);
    await connection.close();
  }
});

test("collector ignores model-written URLs and does not double-count total usage", () => {
  const collector = new TurnCollector("thread-1");
  collector.bind("turn-1");
  collector.accept({ method: "item/completed", params: { threadId: "thread-1", turnId: "turn-1", item: { type: "agentMessage", id: "m", text: "see https://model.example", phase: "final_answer" } } });
  collector.accept({ method: "item/completed", params: { threadId: "thread-1", turnId: "turn-1", item: { type: "webSearch", id: "s", results: [{ url: "https://search.example", title: "Search" }] } } });
  collector.accept({ method: "thread/tokenUsage/updated", params: { threadId: "thread-1", turnId: "turn-1", tokenUsage: { total: { inputTokens: 10, outputTokens: 5, totalTokens: 15 }, last: { inputTokens: 10, outputTokens: 5, totalTokens: 15 } } } });
  collector.accept({ method: "thread/tokenUsage/updated", params: { threadId: "thread-1", turnId: "turn-1", tokenUsage: { total: { inputTokens: 12, outputTokens: 6, totalTokens: 18 }, last: { inputTokens: 2, outputTokens: 1, totalTokens: 3 } } } });
  collector.accept({ method: "turn/completed", params: { threadId: "thread-1", turnId: "turn-1", turn: { status: "completed" } } });
  const result = collector.result();
  assert.deepEqual(result.usage, { input_tokens: 12, output_tokens: 6, total_tokens: 18 });
  const answer = result.output.find(item => item.type === "message");
  assert.deepEqual(answer.content[0].annotations.map(item => item.url), ["https://search.example/"]);
});

test("dynamic support requires the active turn, null namespace, and unique call id", async () => {
  let connection;
  const fake = () => (connection = {
    async open() { return { type: "chatgpt", planType: "plus" }; },
    async listVoices() { return { defaultVoice: "cove", voices: ["cove", "juniper"] }; },
    async startThread() { return "thread-1"; },
    async realtimeStart(_thread, sdp) { return sdp; },
    subscribe() { return () => {}; },
    async close() {}, async stopRealtime() {},
  });
  const bridge = new SubscriptionBridge({ connectionFactory: fake, delegationMs: 1000 });
  const created = await bridge.createSession({ session: { instructions: "x" }, transport: { sdp: "v=0" } });
  const record = bridge.sessions.get(created.session.id);
  await assert.rejects(() => connection.onServerRequest({ params: { threadId: "thread-1", turnId: "inactive", namespace: null, tool: "mural_support", callId: "a", arguments: {} } }), /rejected/);
  record.activeTurns.add("turn-1");
  const pending = connection.onServerRequest({ params: { threadId: "thread-1", turnId: "turn-1", namespace: "wrong", tool: "mural_support", callId: "b", arguments: {} } });
  await assert.rejects(() => pending, /rejected/);
  const accepted = connection.onServerRequest({ params: { threadId: "thread-1", turnId: "turn-1", namespace: null, tool: "mural_support", callId: "c", arguments: {} } });
  await assert.rejects(() => connection.onServerRequest({ params: { threadId: "thread-1", turnId: "turn-1", namespace: null, tool: "mural_support", callId: "c", arguments: {} } }), /rejected/);
  await bridge.close(created.session.id);
  await accepted;
});

test("cancelling an RPC connection stops its child process", async () => {
  const { fake, connection } = connectionFor();
  await connection.open();
  await connection.close();
  assert.equal(fake.child.exitCode, 0);
});

test("an unexpected external tool startup fails closed", async () => {
  const { connection } = connectionFor();
  await connection.open();
  connection.accept({ method: "mcpServer/startupStatus/updated", params: { name: "unexpected", status: "ready" } });
  assert.equal(connection.closed, true);
  await connection.close();
});

test("missing or malformed effective external-tool configuration fails closed", async () => {
  for (const configResult of [{}, { config: {} }, { config: { mcp_servers: null } }, { config: { mcp_servers: [] } }, { config: { mcp_servers: { bad: [] } } }]) {
    const { connection, fake } = connectionFor({ configResult });
    await assert.rejects(() => connection.open(), /configuration is unavailable|configuration is invalid/);
    assert.equal(connection.closed, true);
    assert.equal(fake.writes.some(m => m.method === "thread/start"), false);
  }
});

test("a broken error-reply pipe still closes without an unhandled rejection", async () => {
  const { connection } = connectionFor();
  await connection.open();
  connection.write = () => { throw new Error("pipe closed"); };
  await assert.doesNotReject(() => connection.handleServerRequest({ id: 42, method: "item/commandExecution/requestApproval" }));
  assert.equal(connection.closed, true);
  await connection.close();
});
