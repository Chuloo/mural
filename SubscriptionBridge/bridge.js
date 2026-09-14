import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createConnection, toCodexInput } from "./lib/codex.js";
import { assertSchema } from "./lib/schema.js";
import { bearer, constantTimeToken, loadPairingToken, readJsonBody, safeJson, requireLoopback, SESSION_TTL_MS } from "./lib/security.js";

const tokenPath = process.env.MURAL_BRIDGE_TOKEN_FILE ?? join(fileURLToPath(new URL(".", import.meta.url)), ".local", "bridge-token");
const problem = (statusCode, message) => Object.assign(new Error(message), { statusCode });
const muralTool = { type: "function", name: "mural_support", description: "Request verified facts or detailed language help from the Mural client, using the current conversation.",
  inputSchema: { type: "object", properties: {}, required: [], additionalProperties: false }, deferLoading: false };

export function cleanResponse(raw, schema) {
  if (raw?.status !== "completed" || !Array.isArray(raw.output)) throw problem(502, "incomplete model response");
  const text = raw.output.flatMap(item => item.content ?? []).filter(item => item.type === "output_text").map(item => item.text).join("\n");
  if (!text || text.length > 200_000) throw problem(502, "invalid model response");
  if (schema) {
    let value;
    try { value = JSON.parse(text); } catch { throw problem(502, "invalid structured response"); }
    assertSchema(value, schema);
  }
  return { id: `resp_${randomUUID()}`, object: "response", ...raw, output_text: text };
}

export class SubscriptionBridge {
  constructor({ connectionFactory = createConnection, clock = () => Date.now(), maxLiveSessions = 2, maxResponses = 2,
    idleMs = 90_000, pollMs = 20_000, tombstoneMs = 30_000, delegationMs = 90_000,
    timing = message => console.info(message) } = {}) {
    Object.assign(this, { connectionFactory, clock, maxLiveSessions, maxResponses, idleMs, pollMs, tombstoneMs, delegationMs, timing });
    this.sessions = new Map(); this.tombstones = new Map(); this.activeConnections = new Set();
    this.activeLive = 0; this.activeResponses = 0;
  }
  async withResponseConnection(run, signal) {
    if (this.activeResponses >= this.maxResponses) throw problem(429, "response limit reached");
    this.activeResponses++;
    let connection;
    const abort = () => { void connection?.close(); };
    try {
      signal?.throwIfAborted(); connection = this.connectionFactory(); this.activeConnections.add(connection);
      signal?.addEventListener("abort", abort, { once: true });
      const account = await connection.open(); signal?.throwIfAborted();
      return await run(connection, account);
    } finally {
      signal?.removeEventListener("abort", abort);
      try { await connection?.close(); } finally { this.activeConnections.delete(connection); this.activeResponses--; }
    }
  }
  account({ signal } = {}) { return this.withResponseConnection(async (connection, account) => ({ ...account, ...await connection.listVoices() }), signal); }
  async responses(body, { signal } = {}) {
    const input = safeJson(body);
    if (input.model !== "gpt-5.6-luna" || (input.reasoning?.effort && input.reasoning.effort !== "low")) throw problem(400, "unsupported model or effort");
    if (typeof input.instructions !== "string" || input.instructions.length > 32_000) throw problem(400, "invalid classroom instructions");
    if (input.tools !== undefined && (!Array.isArray(input.tools) || input.tools.length > 1 || input.tools.some(tool => tool?.type !== "web_search"))) throw problem(400, "unsupported tool");
    const items = toCodexInput(input.input);
    if (items.reduce((sum, item) => sum + item.text.length, 0) > 100_000) throw problem(413, "classroom context is too large");
    let schema;
    if (input.text?.format) {
      const format = input.text.format;
      if (format.type !== "json_schema" || format.strict !== true) throw problem(400, "unsupported response format");
      schema = format.schema;
      assertSchemaDefinition(schema);
    }
    return this.withResponseConnection(async connection => cleanResponse(await connection.response(input), schema), signal);
  }
  async createSession(body, { isCancelled = () => false, signal } = {}) {
    const session = safeJson(body?.session), transport = safeJson(body?.transport);
    if (typeof session.instructions !== "string" || session.instructions.length > 32_000 || typeof transport.sdp !== "string"
        || !transport.sdp.startsWith("v=0") || transport.sdp.length > 60_000 || (transport.type && transport.type !== "webrtc")
        || (session.audio?.output?.voice !== undefined && (typeof session.audio.output.voice !== "string" || !/^[a-z][a-z0-9_-]{0,31}$/.test(session.audio.output.voice)))) throw problem(400, "invalid live session request");
    if (this.activeLive >= this.maxLiveSessions) throw problem(429, "live session limit reached");
    this.activeLive++; // Reserve before the first await, including sessions still being created.
    let connection, record;
    const cancelled = () => signal?.aborted || isCancelled();
    const abort = () => { void connection?.close(); };
    const startedAt = performance.now();
    const mark = stage => { try { this.timing(`mural_live_stage=${stage} elapsed_ms=${Math.round(performance.now() - startedAt)}`); } catch {} };
    try {
      if (cancelled()) throw problem(499, "client disconnected");
      connection = this.connectionFactory(); signal?.addEventListener("abort", abort, { once: true });
      await connection.open();
      mark("open_complete");
      if (cancelled()) throw problem(499, "client disconnected");
      const catalog = await connection.listVoices();
      mark("voices_complete");
      const voice = session.audio?.output?.voice ?? catalog.defaultVoice;
      if (!catalog.voices.includes(voice)) throw problem(422, "selected voice is unavailable");
      if (cancelled()) throw problem(499, "client disconnected");
      const threadId = await connection.startThread({ dynamicTools: [muralTool] });
      mark("thread_complete");
      if (cancelled()) throw problem(499, "client disconnected");
      record = { id: randomUUID(), connection, threadId, events: [], waiters: new Set(), cursor: 0, closed: false,
        pending: null, callIDs: new Set(), activeTurns: new Set(), createdAt: this.clock(), lastContact: this.clock() };
      connection.onServerRequest = message => this.handleDynamicTool(record, message);
      record.unsubscribe = connection.subscribe(message => this.sessionNotification(record, message));
      this.sessions.set(record.id, record);
      record.timer = setInterval(() => {
        if (this.clock() - record.lastContact >= this.idleMs || this.clock() - record.createdAt >= SESSION_TTL_MS) void this.closeSession(record, "Connection timeout");
      }, Math.min(30_000, this.idleMs));
      record.timer.unref?.();
      mark("realtime_start_call");
      const sdp = await connection.realtimeStart(threadId, transport.sdp, session.instructions, voice, cancelled);
      mark("realtime_start_return");
      if (cancelled() || record.closed) throw problem(499, "client disconnected");
      return { session: { id: record.id, model: "gpt-live-1-codex", voice }, transport: { type: "webrtc", sdp } };
    } catch (error) {
      if (record) await this.closeSession(record, "Connection failed");
      else { try { await connection?.close(); } finally { this.activeLive--; } }
      throw error;
    } finally { signal?.removeEventListener("abort", abort); }
  }
  sessionNotification(record, message) {
    if (record.closed) return;
    const p = safeJson(message.params);
    if (message.bridgeError) { void this.closeSession(record, "Connection failed"); return; }
    if (p.threadId !== record.threadId) return;
    if (message.method === "turn/started" && typeof p.turn?.id === "string") record.activeTurns.add(p.turn.id);
    if (message.method === "turn/completed") {
      record.activeTurns.delete(p.turn?.id);
      if (record.pending?.turnId === p.turn?.id) this.finishDelegation(record, false, "The classroom request ended.");
    }
    if (["thread/realtime/error", "thread/realtime/closed", "thread/closed"].includes(message.method)) void this.closeSession(record, "Connection closed");
  }
  emit(record, event) {
    record.events.push({ cursor: ++record.cursor, event });
    if (record.events.length > 64) record.events.shift();
    for (const wake of [...record.waiters]) wake();
  }
  get(id) {
    const record = this.sessions.get(id);
    if (!record || record.closed) throw problem(410, "session ended");
    record.lastContact = this.clock();
    return record;
  }
  handleDynamicTool(record, message) {
    const p = safeJson(message.params);
    if (record.closed || p.threadId !== record.threadId || !record.activeTurns.has(p.turnId) || p.namespace !== null
        || p.tool !== "mural_support" || typeof p.callId !== "string" || !p.callId || p.callId.length > 200
        || record.callIDs.has(p.callId) || record.callIDs.size >= 128 || record.pending
        || p.arguments === null || typeof p.arguments !== "object" || Array.isArray(p.arguments) || Object.keys(p.arguments).length) {
      return Promise.reject(new Error("dynamic tool request rejected"));
    }
    record.callIDs.add(p.callId);
    const id = randomUUID();
    const result = new Promise(resolve => {
      const timer = setTimeout(() => this.finishDelegation(record, false, "The Mural client did not return the requested help."), this.delegationMs);
      timer.unref?.(); record.pending = { id, turnId: p.turnId, resolve, timer };
    });
    this.emit(record, { type: "session.delegation.created", delegation: { target: "client", id } });
    return result;
  }
  finishDelegation(record, success, text) {
    const pending = record.pending;
    if (!pending) return;
    record.pending = null; clearTimeout(pending.timer);
    pending.resolve({ success, contentItems: [{ type: "inputText", text }] });
  }
  async append(id, body) {
    const record = this.get(id), input = safeJson(body);
    const kinds = { "session.instructions.append": "instructions", "session.thinking.append": "thinking", "session.commentary.append": "commentary" };
    const kind = kinds[input.type];
    if (!kind || typeof input.content !== "string" || !input.content || input.content.length > 4_000) throw problem(400, "invalid session event");
    if (input.delegation_id != null) {
      if (kind !== "commentary" || record.pending?.id !== input.delegation_id) throw problem(409, "delegation is not pending");
      this.finishDelegation(record, true, input.content);
    } else { await record.connection.append(record.threadId, kind, input.content); }
    return { event: { type: input.type.replace(".append", ".appended"), client_event_id: typeof input.event_id === "string" ? input.event_id : null } };
  }
  closeSession(record, reason = "User ended") {
    if (record.closePromise) return record.closePromise;
    record.closed = true;
    record.closePromise = (async () => {
      clearInterval(record.timer); record.unsubscribe?.();
      this.finishDelegation(record, false, "Conversation ended.");
      this.emit(record, { type: "session.closed", reason });
      const tombstone = { events: record.events, cursor: record.cursor, closed: true };
      tombstone.timer = setTimeout(() => this.tombstones.delete(record.id), this.tombstoneMs); tombstone.timer.unref?.();
      this.tombstones.set(record.id, tombstone);
      while (this.tombstones.size > 16) { const [id, old] = this.tombstones.entries().next().value; clearTimeout(old.timer); this.tombstones.delete(id); }
      try { await record.connection.stopRealtime(record.threadId); }
      finally {
        try { await record.connection.close(); }
        finally { this.sessions.delete(record.id); this.activeLive--; }
      }
    })();
    return record.closePromise;
  }
  async close(id) {
    const record = this.sessions.get(id);
    if (record) await this.closeSession(record);
    return { event: { type: "session.closed", reason: "Conversation ended" } };
  }
  async events(id, after = 0, { signal } = {}) {
    const record = this.sessions.get(id) ?? this.tombstones.get(id);
    if (!record) throw problem(410, "session ended");
    if (!Number.isSafeInteger(after) || after < 0 || after > record.cursor) throw problem(400, "invalid event cursor");
    if (!record.closed) record.lastContact = this.clock();
    const result = () => ({ cursor: record.cursor, events: record.events.filter(item => item.cursor > after).map(item => item.event) });
    if (result().events.length || record.closed) return result();
    if (record.waiters.size >= 2) throw problem(429, "too many event listeners");
    signal?.throwIfAborted();
    await new Promise(resolve => {
      const wake = () => { clearTimeout(timer); record.waiters.delete(wake); signal?.removeEventListener("abort", wake); resolve(); };
      const timer = setTimeout(wake, this.pollMs);
      record.waiters.add(wake); signal?.addEventListener("abort", wake, { once: true });
    });
    signal?.throwIfAborted();
    return result();
  }
  async dispose() {
    await Promise.allSettled([...this.sessions.values()].map(record => this.closeSession(record, "Service stopped")));
    await Promise.allSettled([...this.activeConnections].map(connection => connection.close()));
    for (const tombstone of this.tombstones.values()) clearTimeout(tombstone.timer);
    this.tombstones.clear();
  }
}

// Support only the bounded schema vocabulary used by Mural, rather than arbitrary regex or reference evaluation.
function assertSchemaDefinition(schema, depth = 0) {
  const allowed = ["type", "properties", "required", "additionalProperties", "items", "enum", "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength"];
  if (!schema || typeof schema !== "object" || Array.isArray(schema) || depth > 8 || Object.keys(schema).some(key => !allowed.includes(key))
      || !["object", "array", "string", "number", "integer", "boolean"].includes(schema.type)) throw problem(400, "unsupported schema");
  if (schema.type === "object") {
    if (!schema.properties || typeof schema.properties !== "object" || Array.isArray(schema.properties) || Object.keys(schema.properties).length > 80
        || schema.additionalProperties !== false || !Array.isArray(schema.required)) throw problem(400, "invalid object schema");
    for (const child of Object.values(schema.properties)) assertSchemaDefinition(child, depth + 1);
  }
  if (schema.type === "array") assertSchemaDefinition(schema.items, depth + 1);
}

function json(response, status, body) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }); response.end(JSON.stringify(body));
}

export async function createBridgeServer({ tokenFile = tokenPath, bridge, host = process.env.MURAL_BRIDGE_HOST ?? "127.0.0.1", port = Number(process.env.MURAL_BRIDGE_PORT ?? 8787) } = {}) {
  requireLoopback(host);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error("invalid bridge port");
  const token = await loadPairingToken(tokenFile), service = bridge ?? new SubscriptionBridge();
  const server = createServer(async (request, response) => {
    const controller = new AbortController(), signal = controller.signal;
    request.once("aborted", () => controller.abort());
    response.once("close", () => { if (!response.writableEnded) controller.abort(); });
    try {
      if (!request.url?.startsWith("/") || request.url.startsWith("//")) throw problem(400, "invalid route");
      const url = new URL(request.url, "http://127.0.0.1");
      if (request.method === "GET" && url.pathname === "/health") return json(response, 200, { ok: true });
      if (!constantTimeToken(bearer(request.headers), token)) throw problem(401, "unauthorized");
      if (request.headers.origin) throw problem(403, "browser access is disabled");
      if (request.method === "POST" && !request.headers["content-type"]?.startsWith("application/json")) throw problem(415, "JSON required");
      if (request.method === "GET" && url.pathname === "/v1/account") return json(response, 200, await service.account({ signal }));
      if (request.method === "POST" && url.pathname === "/v1/responses") return json(response, 200, await service.responses(await readJsonBody(request), { signal }));
      if (request.method === "POST" && url.pathname === "/v1/live/sessions") return json(response, 200, await service.createSession(await readJsonBody(request), { signal }));
      const match = url.pathname.match(/^\/v1\/live\/sessions\/([a-fA-F0-9-]{36})(\/events)?$/);
      if (match) {
        const id = match[1];
        if (match[2] && request.method === "GET") return json(response, 200, await service.events(id, Number(url.searchParams.get("after") ?? 0), { signal }));
        if (match[2] && request.method === "POST") return json(response, 200, await service.append(id, await readJsonBody(request)));
        if (!match[2] && request.method === "DELETE") return json(response, 200, await service.close(id));
      }
      json(response, 404, { error: "not found" });
    } catch (error) {
      const status = error.statusCode ?? (error.code === "SCHEMA_INVALID" ? 502 : 502);
      json(response, status, { error: status === 401 ? "unauthorized" : "request could not be completed" });
    }
  });
  server.requestTimeout = 15_000; server.headersTimeout = 10_000;
  return { server, service, host, port, tokenFile };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  if (process.argv.includes("--setup-token")) {
    const { setupPairingToken } = await import("./lib/security.js");
    await setupPairingToken(tokenPath); console.log("Created private pairing token file. The token is not printed.");
  } else {
    const app = await createBridgeServer();
    app.server.listen(app.port, app.host, () => console.log(`Mural bridge listening on ${app.host}:${app.port}`));
    for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, async () => { app.server.close(); await app.service.dispose(); app.server.closeAllConnections(); });
  }
}
