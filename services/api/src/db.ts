import { Pool, type PoolClient } from 'pg';

export type Database = Pool;
export function connectDatabase(url: string): Database {
  return new Pool({ connectionString: url, max: 10, connectionTimeoutMillis: 5_000,
    statement_timeout: 10_000, application_name: 'mural-billing' });
}
export async function transaction<T>(db: Database, fn: (sql: PoolClient) => Promise<T>): Promise<T> {
  const sql = await db.connect();
  try {
    await sql.query('BEGIN');
    const result = await fn(sql);
    await sql.query('COMMIT');
    return result;
  } catch (error) {
    await sql.query('ROLLBACK');
    throw error;
  } finally { sql.release(); }
}
