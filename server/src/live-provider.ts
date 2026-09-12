import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { ServiceError } from './errors.js';

export type VoiceUsage = { type: 'session.usage.updated' | 'session.closed'; usage: { seconds: number } };
export interface Sideband { closeSession(): void; disconnect(): void }
export interface LiveProvider {
  create(sdp: string, language: string): Promise<{ sessionID: string; sdp: string }>;
  attach(sessionID: string, onUsage: (event: VoiceUsage) => void, onLoss: () => void): Promise<Sideband>;
  hangup(sessionID: string): Promise<void>;
}
const languages: Record<string, string> = { 'nb-NO': 'Norwegian Bokmål with an Eastern Norwegian pronunciation',
  'es-ES': 'Spanish from Spain', 'en-US': 'English', 'fr-FR': 'French from France',
  'zh-CN': 'Standard Mandarin with Simplified Chinese usage' };
export const supportsLanguage = (language: string) => Object.hasOwn(languages, language);
const sessionPath = (id: string) => {
  if (!id || id.length > 256 || /[\x00-\x20]/.test(id)) throw new ServiceError('invalid_provider_session', 502);
  return `/v1/live/sessions/${encodeURIComponent(id)}`;
};

/** Production URLs are fixed. Tests may inject a loopback-only transport origin. */
export class OpenAILiveProvider implements LiveProvider {
  private readonly origin: URL;
  constructor(private readonly key: string, options: { testOrigin?: string; timeoutMilliseconds?: number } = {}) {
    this.origin = new URL(options.testOrigin ?? 'https://api.openai.com');
    if (options.testOrigin && (this.origin.hostname !== '127.0.0.1' || this.origin.protocol !== 'http:'))
      throw new ServiceError('invalid_test_origin');
    this.timeout = options.timeoutMilliseconds ?? 10_000;
    if (!key || this.origin.username || this.origin.password) throw new ServiceError('live_not_configured', 503);
  }
  private readonly timeout: number;
  async create(sdp: string, language: string) {
    if (!supportsLanguage(language)) throw new ServiceError('invalid_language');
    try {
      // Never retry a billed create whose result is uncertain.
      const response = await fetch(new URL('/v1/live/sessions', this.origin), {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(this.timeout),
        headers: { Authorization: `Bearer ${this.key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ session: { model: 'gpt-live-1', store: false, input: [],
          instructions: `You are Mural, a warm language conversation partner. Speak only ${languages[language]}. Begin with a brief hello. Infer the learner's level naturally and adapt sentence length, vocabulary and pace. Accept replies in any language. Recast mistakes kindly in your reply and invite a short retry when useful. Ask one question at a time.`,
          delegation: { type: 'client' }, audio: { output: { voice: 'marin' } } }, transport: { type: 'webrtc', sdp } })
      });
      if (!response.ok) { await response.body?.cancel(); throw new Error(); }
      const raw = await boundedJSON(response, 131_072);
      if (typeof raw?.session?.id !== 'string' || typeof raw?.transport?.sdp !== 'string' || raw.transport.type !== 'webrtc') throw new Error();
      sessionPath(raw.session.id);
      return { sessionID: raw.session.id as string, sdp: raw.transport.sdp as string };
    } catch { throw new ServiceError('provider_create_uncertain', 502); }
  }
  async attach(sessionID: string, onUsage: (event: VoiceUsage) => void, onLoss: () => void): Promise<Sideband> {
    const url = new URL(`${sessionPath(sessionID)}/attach`, this.origin);
    url.protocol = this.origin.protocol === 'https:' ? 'wss:' : 'ws:';
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${this.key}` },
        handshakeTimeout: this.timeout, maxPayload: 524_288, perMessageDeflate: false, followRedirects: false });
      let intentional = false, opened = false, lossReported = false;
      let pongAt = Date.now();
      const heartbeat = setInterval(() => {
        if (!opened) return;
        if (Date.now() - pongAt > 15_000) { lost(); socket.terminate(); }
        else if (socket.readyState === WebSocket.OPEN) socket.ping();
      }, 5_000);
      heartbeat.unref();
      socket.on('pong', () => { pongAt = Date.now(); });
      const lost = () => { if (!intentional && !lossReported) { lossReported = true; onLoss(); } };
      socket.on('error', () => { if (!opened) reject(new ServiceError('provider_attach_failed', 502)); lost(); });
      socket.on('close', () => { clearInterval(heartbeat); if (!opened) reject(new ServiceError('provider_attach_failed', 502)); lost(); });
      socket.on('message', (bytes, binary) => {
        try {
          if (binary) throw new Error();
          const event = JSON.parse(bytes.toString());
          if (event.type === 'error') throw new Error();
          // Reflected audio, transcripts, prompts and session snapshots are discarded here.
          if (event.type !== 'session.usage.updated' && event.type !== 'session.closed') return;
          const seconds = event.usage?.seconds;
          if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0 || seconds > Number.MAX_SAFE_INTEGER / 1000) throw new Error();
          if (event.session?.id !== undefined && event.session.id !== sessionID) throw new Error();
          onUsage({ type: event.type, usage: { seconds } });
        } catch { lost(); socket.terminate(); }
      });
      socket.once('open', () => {
        opened = true;
        resolve({ closeSession() {
          if (socket.readyState !== WebSocket.OPEN) throw new ServiceError('provider_connection_lost', 502);
          socket.send(JSON.stringify({ type: 'session.close', event_id: randomUUID() }));
        }, disconnect() { intentional = true; socket.terminate(); } });
      });
    });
  }
  async hangup(sessionID: string): Promise<void> {
    try {
      const response = await fetch(new URL(`${sessionPath(sessionID)}/hangup`, this.origin), {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(this.timeout), headers: { Authorization: `Bearer ${this.key}` }
      });
      await response.body?.cancel();
      if (!response.ok) throw new Error();
    } catch { throw new ServiceError('provider_hangup_unconfirmed', 502); }
  }
}
export async function boundedJSON(response: Response, limit: number): Promise<any> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Empty response');
  const chunks: Uint8Array[] = []; let length = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read(); if (done) break;
      length += value.byteLength;
      if (length > limit) throw new Error('Response limit');
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks).toString());
  } finally { await reader.cancel().catch(() => {}); }
}
