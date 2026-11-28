import type { Database } from './db.js';

/**
 * AI reports are saved on the learner's phone and never transmitted, so the submission route and
 * its client are gone. Rows stored before that removal keep the published thirty-day expiry: this
 * retention task stays until the tables are empty and a human drops them.
 */
export async function pruneAIReports(db: Database): Promise<{ reports: number; limits: number }> {
  const row = (await db.query<{ deleted_reports: number; deleted_counters: number }>('SELECT * FROM prune_ai_feedback()')).rows[0]!;
  return { reports: row.deleted_reports, limits: row.deleted_counters };
}
