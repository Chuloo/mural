import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connectDatabase, type Database } from './db.js';
import { deleteAccessRequest, pruneAccessRequests } from './access-requests.js';

export async function exportAccessRequests(db: Database, path: string): Promise<void> {
  if (!isAbsolute(path)) throw new Error('An absolute private output path is required.');
  const file = await open(path, 'wx', 0o600);
  try {
    const rows = (await db.query(`SELECT email,requested_at,consent_version,source FROM access_requests
      WHERE requested_at >= now()-interval '12 months' ORDER BY requested_at,email`)).rows;
    await file.writeFile(JSON.stringify({ version: 1, exportedAt: new Date().toISOString(), requests: rows }, null, 2) + '\n');
  } finally { await file.close(); }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const databaseURL = process.env.DATABASE_URL, command = process.argv[2];
  if (!databaseURL || !['export', 'delete', 'prune'].includes(command ?? '')) {
    console.error('Use DATABASE_URL and access-requests-admin.js export ABSOLUTE_NEW_FILE | delete (email on stdin) | prune.');
    process.exitCode = 1;
  } else {
    const db = connectDatabase(databaseURL);
    try {
      if (command === 'export') {
        if (!process.argv[3] || process.argv.length !== 4) throw new Error();
        await exportAccessRequests(db, process.argv[3]); console.info('Access requests exported to the private file.');
      } else if (command === 'delete') {
        if (process.argv.length !== 3) throw new Error();
        let input = '';
        for await (const chunk of process.stdin) { input += chunk.toString(); if (input.length > 1024) throw new Error(); }
        await deleteAccessRequest(db, input); console.info('Access request deletion completed.');
      } else {
        if (process.argv.length !== 3) throw new Error();
        const counts = await pruneAccessRequests(db); console.info(`Pruned ${counts.requests} expired requests and ${counts.limits} rate buckets.`);
      }
    } catch { console.error('Access request operation failed. No email or database details are logged.'); process.exitCode = 1; }
    finally { await db.end(); }
  }
}
