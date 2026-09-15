import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { connectDatabase, transaction, type Database } from './db.js';

const postCommitConstraints = [
  ['hosted_sessions', 'hosted_rejection_evidence'],
  ['minute_stripe_paid_totals', 'minute_stripe_paid_totals_gross_minor_check'],
] as const;

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
  // NOT VALID enforces new writes immediately. Scan existing rows only after the
  // DDL transaction releases its ACCESS EXCLUSIVE locks. Retry this phase even
  // when all migration files were committed by an interrupted earlier run.
  await transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-migrations'))");
    for (const [table, name] of postCommitConstraints) {
      const constraint = (await sql.query(`SELECT c.convalidated FROM pg_constraint c
        JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
        WHERE n.nspname=current_schema() AND t.relname=$1 AND c.conname=$2 AND c.contype='c'`,
      [table, name])).rows[0];
      if (!constraint) throw new Error(`Migration constraint is missing: ${table}.${name}`);
      // Identifiers come only from the static list above, never configuration or input.
      if (!constraint.convalidated) await sql.query(`ALTER TABLE ${table} VALIDATE CONSTRAINT ${name}`);
    }
  });
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const db = connectDatabase(process.env.DATABASE_URL ?? '');
  try { await migrate(db); console.info('Mural schema ready.'); }
  catch { console.error('Mural migration failed. Check database configuration.'); process.exitCode = 1; }
  finally { await db.end(); }
}
