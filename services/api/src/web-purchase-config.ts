import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';

export interface WebPurchaseConfig {
  hmacKey: string;
  apiKey: string;
  from: string;
  replyTo: string;
}

export function validateWebPurchaseConfig(value: unknown): WebPurchaseConfig {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid website purchase configuration.');
  const input = value as Record<string, unknown>;
  if (Object.keys(input).some(key => !['hmacKey','apiKey','from','replyTo'].includes(key)) ||
      typeof input.hmacKey !== 'string' || !/^[a-f0-9]{64}$/.test(input.hmacKey) ||
      typeof input.apiKey !== 'string' || !/^re_[A-Za-z0-9_-]{20,200}$/.test(input.apiKey) ||
      typeof input.from !== 'string' || !/^Mural <[a-z0-9._-]+@contact\.hackmamba\.io>$/.test(input.from) ||
      input.replyTo !== 'hi@hackmamba.io') throw new Error('Invalid website purchase configuration.');
  return input as unknown as WebPurchaseConfig;
}

export async function webPurchaseConfig(env: NodeJS.ProcessEnv): Promise<WebPurchaseConfig | undefined> {
  if (env.WEB_PURCHASES_ENABLED !== undefined && !['true','false'].includes(env.WEB_PURCHASES_ENABLED))
    throw new Error('Invalid website purchase gate.');
  if (env.WEB_PURCHASES_ENABLED !== 'true') return undefined;
  const path = env.WEB_PURCHASES_CREDENTIALS_FILE;
  if (!path || !isAbsolute(path) || path.length > 1024) throw new Error('Missing protected website purchase configuration.');
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const before = await file.stat();
    if (!before.isFile() || before.nlink !== 1 || (before.mode & 0o077) !== 0 ||
        (before.uid !== 0 && before.uid !== process.getuid?.()) || before.size < 2 || before.size > 8192)
      throw new Error('Unsafe website purchase configuration.');
    const bytes = Buffer.alloc(before.size + 1), { bytesRead } = await file.read(bytes, 0, bytes.length, 0);
    const after = await file.stat();
    if (bytesRead !== before.size || before.size !== after.size || before.mtimeMs !== after.mtimeMs)
      throw new Error('Website purchase configuration changed during loading.');
    return validateWebPurchaseConfig(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, bytesRead))));
  } finally { await file.close(); }
}
