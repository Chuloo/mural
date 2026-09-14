-- Both funding ledgers use the same provider watchdog and recovery protocol.
-- A session belongs to exactly one ledger; existing dollar reservations are unchanged.
ALTER TABLE hosted_sessions ALTER COLUMN reservation_id DROP NOT NULL;
ALTER TABLE hosted_sessions ADD COLUMN minute_reservation_id uuid UNIQUE REFERENCES minute_reservations(id);
ALTER TABLE hosted_sessions ADD COLUMN reserved_ms bigint CHECK (reserved_ms>0 AND reserved_ms<=600000);
ALTER TABLE hosted_sessions ADD COLUMN charged_ms bigint CHECK (charged_ms>=0 AND charged_ms<=reserved_ms);
ALTER TABLE hosted_sessions ADD CONSTRAINT one_hosted_funding_ledger CHECK (
  (reservation_id IS NOT NULL AND minute_reservation_id IS NULL AND reserved_ms IS NULL AND charged_ms IS NULL) OR
  (reservation_id IS NULL AND minute_reservation_id IS NOT NULL AND reserved_ms IS NOT NULL AND charged_nano IS NULL)
);
