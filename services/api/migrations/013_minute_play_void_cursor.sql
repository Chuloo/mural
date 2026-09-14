-- Contains only a Google pagination cursor and checkpoint, never purchase tokens or user data.
CREATE TABLE minute_play_void_cursors (
  environment text NOT NULL CHECK (environment IN ('test','live')),
  merchant text NOT NULL CHECK (merchant='chat.mural.android'),
  completed_through_ms bigint CHECK (completed_through_ms BETWEEN 0 AND 9007199254740991),
  window_start_ms bigint CHECK (window_start_ms BETWEEN 0 AND 9007199254740991),
  window_end_ms bigint CHECK (window_end_ms BETWEEN 0 AND 9007199254740991),
  page_token text CHECK (octet_length(page_token) BETWEEN 1 AND 4096 AND page_token !~ '[^!-~]'),
  next_poll_after timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(environment,merchant),
  CHECK ((page_token IS NULL AND window_start_ms IS NULL AND window_end_ms IS NULL)
    OR (page_token IS NOT NULL AND window_start_ms IS NOT NULL AND window_end_ms IS NOT NULL AND window_start_ms<window_end_ms))
);
CREATE FUNCTION protect_minute_void_cursor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.environment IS DISTINCT FROM OLD.environment OR NEW.merchant IS DISTINCT FROM OLD.merchant
    OR (OLD.completed_through_ms IS NOT NULL AND (NEW.completed_through_ms IS NULL OR NEW.completed_through_ms<OLD.completed_through_ms)) THEN
    RAISE EXCEPTION 'immutable_minute_void_checkpoint';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER minute_play_void_cursor_scope BEFORE UPDATE ON minute_play_void_cursors
FOR EACH ROW EXECUTE FUNCTION protect_minute_void_cursor();
