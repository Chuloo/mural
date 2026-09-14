import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { connectDatabase, transaction, type Database } from './db.js';

export async function migrate(db: Database): Promise<void> {
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));
  // When executed from source, this module is one level closer to migrations.
  const sourceDirectory = fileURLToPath(new URL('../migrations/', import.meta.url));
  const path = await readdir(sourceDirectory).then(() => sourceDirectory).catch(() => directory);
  await transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-migrations'))");
    await sql.query('CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())');
    for (const file of (await readdir(path)).filter(name => name.endsWith('.sql')).sort()) {
      if ((await sql.query('SELECT name FROM schema_migrations WHERE name=$1', [file])).rowCount) continue;
      await sql.query(await readFile(`${path}/${file}`, 'utf8'));
      await sql.query('INSERT INTO schema_migrations(name) VALUES ($1)', [file]);
    }
  });
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const db = connectDatabase(process.env.DATABASE_URL ?? '');
  try { await migrate(db); console.info('Mural schema ready.'); }
  catch { console.error('Mural migration failed. Check database configuration.'); process.exitCode = 1; }
  finally { await db.end(); }
}
