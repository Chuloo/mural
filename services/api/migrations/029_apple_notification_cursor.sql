CREATE TABLE apple_notification_cursors (
  environment text NOT NULL CHECK(environment IN ('test','live')),
  merchant text NOT NULL,
  completed_through_ms bigint,
  window_start_ms bigint,
  window_end_ms bigint,
  page_token text CHECK(length(page_token)<=4096),
  next_poll_after timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(environment,merchant),
  CHECK((page_token IS NULL AND window_start_ms IS NULL AND window_end_ms IS NULL) OR
        (page_token IS NOT NULL AND window_start_ms>0 AND window_end_ms>window_start_ms))
);
