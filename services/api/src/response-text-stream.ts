/** Decode bounded Responses SSE without treating partial text as a completed, billable result. */
export async function responseTextStream(response: Response, onText: (text: string) => void): Promise<unknown> {
  if (!response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream') || !response.body) throw new Error('Invalid provider stream');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let pending = '', data: string[] = [], eventBytes = 0, total = 0, text = '';
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > 2_097_152) throw new Error('Provider stream exceeds limit');
      pending += decoder.decode(chunk.value, { stream: true });
      let newline: number;
      while ((newline = pending.indexOf('\n')) >= 0) {
        const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1);
        eventBytes += Buffer.byteLength(line) + 1;
        if (eventBytes > 65_536) throw new Error('Provider event exceeds limit');
        if (line) {
          if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
          continue;
        }
        const payload = data.join('\n'); data = []; eventBytes = 0;
        if (!payload) continue;
        if (payload === '[DONE]') throw new Error('Provider stream is incomplete');
        const event = JSON.parse(payload);
        if (!event || typeof event !== 'object') throw new Error('Invalid provider event');
        switch (event.type) {
          case 'response.output_text.delta':
            if (typeof event.delta !== 'string') throw new Error('Invalid provider delta');
            text += event.delta;
            if (Buffer.byteLength(text) > 65_536) throw new Error('Provider text exceeds limit');
            onText(text); break;
          case 'response.completed':
            if (!event.response || event.response.status !== 'completed') throw new Error('Provider stream is incomplete');
            return event.response;
          case 'response.refusal.delta': case 'response.refusal.done': break; // Read final usage before settling a refusal.
          case 'error': case 'response.failed': case 'response.incomplete':
            throw new Error('Provider stream did not complete');
        }
      }
      if (Buffer.byteLength(pending) + eventBytes > 65_536) throw new Error('Provider event exceeds limit');
    }
    throw new Error('Provider stream ended without completion');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
