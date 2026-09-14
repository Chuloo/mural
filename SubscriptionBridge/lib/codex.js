import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { DISABLED_FEATURES, DISABLED_SETTINGS, isolationConfig, safeJson, CLASSROOM_POLICY, SUPPORT_POLICY } from "./security.js";

const ENV_KEYS = new Set(["PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "USER", "CODEX_HOME"]);
const MAX_LINE = 8 * 1024 * 1024;
const MAX_TEXT = 200_000;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

export class CodexConnection {
  constructor({ executable = process.env.CODEX_BIN ?? "codex", spawnImpl = spawn, timeoutMs = 30_000, turnTimeoutMs = 55_000, cwd } = {}) {
    Object.assign(this, { executable, spawnImpl, timeoutMs, turnTimeoutMs, cwd });
    this.nextId = 1; this.pending = new Map(); this.listeners = new Set();
    this.closed = false; this.onServerRequest = null; this.closePromise = null;
  }
  async open() {
    this.temp = this.cwd ?? await mkdtemp(`${tmpdir()}/mural-bridge-`);
    // Turn overrides can reload configuration. Keep this process's named profile
    // at CLI scope as well as thread scope so that reloads cannot lose it.
    const args = ["app-server", "--stdio", "-c", "mcp_servers={}",
      "-c", 'permissions.mural-isolated.filesystem={":root"="deny",":minimal"="read",":workspace_roots"={"."="read"}}',
      "-c", "permissions.mural-isolated.network.enabled=false", "-c", 'default_permissions="mural-isolated"'];
    for (const name of DISABLED_FEATURES) args.push("-c", `features.${name}=false`);
    for (const name of DISABLED_SETTINGS) args.push("-c", `${name}=false`);
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => ENV_KEYS.has(key)));
    try {
      await this.launch(args, env);
      // An empty table is merged with inherited MCP configuration in this CLI.
      // Inspect configuration before starting any thread, then restart with each
      // inherited server explicitly disabled. Configuration values stay private.
      const configured = await this.readMcpConfiguration();
      const names = Object.keys(configured);
      this.mcpOverrides = Object.fromEntries(names.map(name => [name, { enabled: false }]));
      if (names.length) {
        const first = this.child; this.child = null;
        await this.stopChild(first);
        if (this.closed) throw new Error("Codex connection cancelled");
        const disabled = ["-c", `mcp_servers={${names.map(name => `${JSON.stringify(name)}={enabled=false}`).join(",")}}`];
        await this.launch([...args, ...disabled], env);
      }
      const checked = await this.readMcpConfiguration();
      if (Object.values(checked).some(server => server?.enabled !== false)) {
        throw new Error("Inherited external tools could not be disabled");
      }
      this.externalToolsDisabled = true;
      const { account } = await this.request("account/read", { refreshToken: false });
      if (account?.type !== "chatgpt") throw Object.assign(new Error("ChatGPT sign-in required"), { statusCode: 401 });
      return { type: "chatgpt", planType: account.planType ?? "unknown" };
    } catch (error) { await this.close(); throw error; }
  }
  async readMcpConfiguration() {
    const result = await this.request("config/read", { includeLayers: false });
    const config = result?.config;
    if (!config || typeof config !== "object" || Array.isArray(config)) throw new Error("Effective configuration is unavailable");
    const servers = config.mcp_servers;
    if (!servers || typeof servers !== "object" || Array.isArray(servers)) throw new Error("External tool configuration is invalid");
    if (Object.values(servers).some(server => !server || typeof server !== "object" || Array.isArray(server))) throw new Error("External tool configuration is invalid");
    return servers;
  }
  async launch(args, env) {
      if (this.closed) throw new Error("Codex connection cancelled");
      const child = this.spawnImpl(this.executable, args, { cwd: this.temp, env, stdio: ["pipe", "pipe", "pipe"] });
      this.child = child;
      child.stderr?.resume?.();
      child.stdout.setEncoding("utf8");
      let buffer = "";
      child.stdout.on("data", chunk => {
        if (this.closed || this.child !== child) return;
        buffer += chunk;
        if (buffer.length > MAX_LINE) { this.fail(new Error("Codex message too large")); return; }
        let end;
        while ((end = buffer.indexOf("\n")) >= 0 && !this.closed) {
          const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
          if (!line.trim()) continue;
          try { this.accept(JSON.parse(line)); }
          catch { this.fail(new Error("Codex returned invalid data")); }
        }
      });
      child.once("error", () => { if (this.child === child) this.fail(new Error("Codex unavailable")); });
      child.stdin.on("error", () => { if (this.child === child) this.fail(new Error("Codex connection closed")); });
      child.once("close", () => { if (this.child === child) this.fail(new Error("Codex connection closed")); });
      await this.request("initialize", { clientInfo: { name: "mural-subscription-bridge", version: "0.1.0" }, capabilities: { experimentalApi: true } });
      this.write({ method: "initialized" });
  }
  async stopChild(child) {
    if (!child || child.exitCode !== null || child.signalCode != null) return;
    await new Promise(resolve => {
      const timer = setTimeout(() => child.kill("SIGKILL"), 1_500);
      child.once("close", () => { clearTimeout(timer); resolve(); });
      child.kill("SIGTERM");
    });
  }
  fail(error) {
    if (this.closed) return;
    for (const listener of this.listeners) listener({ bridgeError: error });
    void this.close();
  }
  write(message) {
    if (this.closed) throw new Error("Codex connection closed");
    this.child.stdin.write(JSON.stringify(message) + "\n");
  }
  request(method, params, timeoutMs = this.timeoutMs) {
    if (this.closed) return Promise.reject(new Error("Codex connection closed"));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`Codex request timed out: ${method}`)); }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write({ id, method, params }); }
      catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  subscribe(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  accept(message) {
    if (message.method === "mcpServer/startupStatus/updated" && message.params?.status === "ready") {
      this.fail(new Error("Unexpected external tool startup")); return;
    }
    if (message.id !== undefined && typeof message.method === "string") { void this.handleServerRequest(message); return; }
    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id); clearTimeout(pending.timer);
      if (message.error) pending.reject(Object.assign(new Error("Codex request rejected"), { rpcCode: message.error.code }));
      else pending.resolve(message.result);
      return;
    }
    for (const listener of this.listeners) listener(message);
  }
  async handleServerRequest(message) {
    try {
      if (message.method !== "item/tool/call" || !this.onServerRequest) throw new Error("server request rejected");
      const result = await this.onServerRequest(message);
      if (!this.closed) this.write({ id: message.id, result });
    } catch {
      if (!this.closed) {
        try { this.write({ id: message.id, error: { code: -32001, message: "Classroom capability unavailable" } }); }
        catch { /* The peer can exit before the rejection is written. */ }
        finally { this.fail(new Error("Codex server request rejected")); }
      }
    }
  }
  async startThread(options = {}) {
    const params = isolationConfig(this.temp, options);
    params.config.mcp_servers = this.mcpOverrides ?? {};
    const result = await this.request("thread/start", params);
    if (typeof result.thread?.id !== "string" || result.model !== params.model || result.reasoningEffort !== "low"
        || result.activePermissionProfile?.id !== "mural-isolated" || result.approvalPolicy !== "on-request"
        || result.sandbox?.type !== "readOnly" || result.sandbox.networkAccess !== false
        || (result.serviceTier != null && result.serviceTier !== "default")) {
      throw new Error("Codex did not apply the requested classroom isolation and model");
    }
    this.threadSettings = { model: result.model, reasoningEffort: result.reasoningEffort, serviceTier: result.serviceTier,
      activePermissionProfile: result.activePermissionProfile, sandbox: result.sandbox, approvalPolicy: result.approvalPolicy };
    return result.thread.id;
  }
  async listVoices() {
    const result = await this.request("thread/realtime/listVoices", {});
    // The current Codex v3 transport uses the v1 voice family. This is also
    // the family used by the existing reader; v2's Marin is rejected by v3.
    const voices = result?.voices?.v1, defaultVoice = result?.voices?.defaultV1;
    if (!Array.isArray(voices) || !voices.length || voices.length > 40
        || voices.some(voice => typeof voice !== "string" || !/^[a-z][a-z0-9_-]{0,31}$/.test(voice))
        || !voices.includes(defaultVoice)) throw new Error("Codex voice catalog is unavailable");
    return { voices: [...new Set(voices)], defaultVoice };
  }
  async realtimeStart(threadId, sdp, instructions, voice = "cove", isCancelled = () => false) {
    if (typeof sdp !== "string" || !sdp.startsWith("v=0") || sdp.length > 60_000) throw new Error("invalid WebRTC offer");
    let answer, failure;
    const unsubscribe = this.subscribe(message => {
      if (message.bridgeError) failure = message.bridgeError;
      const p = safeJson(message.params);
      if (p.threadId !== threadId) return;
      if (message.method === "thread/realtime/sdp" && typeof p.sdp === "string") answer = p.sdp;
      if (["thread/realtime/error", "thread/realtime/closed"].includes(message.method)) failure = new Error("Voice session closed during connection");
    });
    const deadline = Date.now() + 45_000;
    try {
      const started = this.request("thread/realtime/start", { threadId, outputModality: "audio", includeStartupContext: false,
        version: "v3", model: "gpt-live-1-codex", voice, codexResponseHandoffMode: "bemTags",
        prompt: instructions, realtimeStartInstructions: CLASSROOM_POLICY + "\n" + SUPPORT_POLICY, transport: { type: "webrtc", sdp } }, 45_000);
      let accepted = false;
      started.then(() => { accepted = true; }, error => { failure = error; });
      while (!accepted || !answer) {
        if (failure) throw failure;
        if (this.closed || isCancelled()) throw new Error("Voice connection cancelled");
        if (Date.now() >= deadline) throw new Error("Voice answer timed out");
        await wait(50);
      }
      return answer;
    } finally { unsubscribe(); }
  }
  append(threadId, kind, text) {
    if (typeof text !== "string" || text.length > 4_000) throw new Error("invalid conversation update");
    if (kind === "commentary") return this.request("thread/realtime/appendSpeech", { threadId, text }, 10_000);
    if (!["thinking", "instructions"].includes(kind)) throw new Error("unsupported conversation update");
    return this.request("thread/realtime/appendText", { threadId, role: "developer", text }, 10_000);
  }
  async stopRealtime(threadId) {
    try { await this.request("thread/realtime/stop", { threadId }, 1_500); }
    catch { /* Killing this session's dedicated child below is the cleanup fallback. */ }
  }
  async response(body) {
    const threadId = await this.startThread({ developerInstructions: body.instructions, webSearch: Boolean(body.tools?.length) });
    const collector = new TurnCollector(threadId);
    const unsubscribe = this.subscribe(message => collector.accept(message));
    try {
      const result = await this.request("turn/start", { threadId, input: toCodexInput(body.input), model: "gpt-5.6-luna",
        effort: "low", serviceTierForTurn: "default",
        ...(body.text?.format?.type === "json_schema" ? { outputSchema: body.text.format.schema } : {}) });
      collector.bind(result.turn?.id);
      const deadline = Date.now() + this.turnTimeoutMs;
      while (!collector.done) {
        if (collector.error) throw collector.error;
        if (this.closed || Date.now() >= deadline) throw new Error("Classroom response did not complete");
        await wait(20);
      }
      if (collector.error) throw collector.error;
      return collector.result();
    } finally { unsubscribe(); await this.close(); }
  }
  close() {
    if (this.closePromise) return this.closePromise;
    this.closed = true;
    this.closePromise = (async () => {
      for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error("Codex connection closed")); }
      this.pending.clear(); this.listeners.clear();
      await this.stopChild(this.child);
      if (this.temp && !this.cwd) await rm(this.temp, { recursive: true, force: true });
    })();
    return this.closePromise;
  }
}

export function toCodexInput(input) {
  const values = typeof input === "string" ? [{ role: "user", content: input }] : input;
  if (!Array.isArray(values) || !values.length || values.length > 20) throw new Error("invalid response input");
  const result = [];
  for (const value of values) {
    if (value?.role !== "user") throw new Error("only classroom user text is accepted");
    const parts = Array.isArray(value.content) ? value.content : [value.content];
    for (const part of parts) {
      const text = typeof part === "string" ? part : part?.type === "input_text" ? part.text : null;
      if (typeof text !== "string" || !text || text.length > MAX_TEXT) throw new Error("invalid classroom text");
      result.push({ type: "text", text, text_elements: [] });
    }
  }
  if (!result.length) throw new Error("response input is empty");
  return result;
}

export class TurnCollector {
  constructor(threadId) {
    this.threadId = threadId; this.turnId = null; this.buffer = []; this.size = 0;
    this.messages = new Map(); this.searches = new Map(); this.citations = new Map(); this.usage = null; this.done = false; this.error = null;
  }
  bind(turnId) {
    if (typeof turnId !== "string" || !turnId) throw new Error("Codex did not return a turn handle");
    this.turnId = turnId;
    const buffered = this.buffer; this.buffer = [];
    for (const message of buffered) this.consume(message);
  }
  accept(message) {
    if (message.bridgeError) { this.error = message.bridgeError; return; }
    if (message.params?.threadId !== this.threadId) return;
    this.size += JSON.stringify(message).length;
    if (this.size > 2_000_000) { this.error = new Error("Classroom response exceeded its size limit"); return; }
    if (!this.turnId) this.buffer.push(message); else this.consume(message);
  }
  citation(value) {
    if (this.citations.size >= 30 || typeof value?.url !== "string") return;
    try {
      const url = new URL(value.url);
      if (url.protocol !== "https:" || url.username || url.password) return;
      this.citations.set(url.href, { type: "url_citation", url: url.href, title: String(value.title ?? "Source").slice(0, 300) });
    } catch { /* Invalid provider source metadata is not an attributable source. */ }
  }
  item(item) {
    if (item?.type === "agentMessage" && typeof item.text === "string" && item.phase !== "commentary") {
      this.messages.set(item.id, { text: item.text, phase: item.phase });
    }
    if (item?.type === "webSearch") {
      this.searches.set(item.id, { type: "web_search_call", id: item.id, status: "completed" });
      for (const source of item.results ?? []) this.citation(source);
    }
  }
  consume(message) {
    const p = message.params;
    const eventTurn = p.turnId ?? p.turn?.id;
    if (eventTurn && eventTurn !== this.turnId) return;
    if (message.method === "item/completed") this.item(p.item);
    if (message.method === "rawResponseItem/completed") {
      // Use provider annotations when available; never parse model-written URLs as citations.
      for (const content of p.item?.content ?? []) for (const source of content.annotations ?? []) if (source.type === "url_citation") this.citation(source);
    }
    if (message.method === "thread/tokenUsage/updated" && p.tokenUsage?.total) this.usage = p.tokenUsage.total;
    if (message.method === "error" && !p.willRetry) this.error = new Error("Codex could not complete the classroom response");
    if (message.method === "turn/completed") {
      if (p.turn?.status !== "completed" || p.turn.error) this.error = new Error("Codex classroom turn failed or was interrupted");
      for (const item of p.turn?.items ?? []) this.item(item);
      this.done = true;
    }
  }
  result() {
    if (!this.done || this.error) throw this.error ?? new Error("Turn is incomplete");
    const messages = [...this.messages.values()];
    const final = messages.filter(message => message.phase === "final_answer");
    const text = (final.length ? final : messages).map(message => message.text).join("\n");
    if (!text || text.length > MAX_TEXT) throw new Error("No complete classroom answer");
    const result = { status: "completed", output: [...this.searches.values(), { type: "message", role: "assistant",
      content: [{ type: "output_text", text, annotations: [...this.citations.values()] }] }] };
    if (this.usage) {
      const { inputTokens, outputTokens, totalTokens } = this.usage;
      if ([inputTokens, outputTokens, totalTokens].every(value => Number.isSafeInteger(value) && value >= 0)) {
        result.usage = { input_tokens: inputTokens, output_tokens: outputTokens, total_tokens: totalTokens };
      }
    }
    return result;
  }
}

export function createConnection(options) { return new CodexConnection(options); }
