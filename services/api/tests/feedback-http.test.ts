import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';

// AI reports are saved on the learner's phone, so the submission route and its capability probe are
// gone: a client that still tries one gets the same 404 as any other unknown path, and no excerpt,
// rate-limit counter or trusted-proxy identity is read or stored anywhere.
const unused = { query: async () => { throw new Error('Database must not be reached.'); } } as unknown as Database;
const report = { reportID: randomUUID(), languageID: 'es', reason: 'incorrect',
  excerpt: 'Una frase que nunca llega al servidor.', consentVersion: 'ai-report-v1' };

test('the report routes are removed and admit no submission', async () => {
  const app = createApp({ db: unused, auth: {} });
  try {
    const responses = [
      await app.inject({ method: 'GET', url: '/v1/feedback/capabilities' }),
      await app.inject({ method: 'POST', url: '/v1/feedback/ai', headers: { 'x-mural-client-ip': '[IP_ADDRESS]' }, payload: report }),
    ];
    for (const response of responses) {
      assert.equal(response.statusCode, 404);
      assert.deepEqual(response.json(), { error: { code: 'not_found' } });
      assert.ok(!response.body.includes(report.excerpt));
    }
  } finally { await app.close(); }
});
