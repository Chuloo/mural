REVOKE ALL ON minute_play_void_cursors FROM mural_runtime;
GRANT SELECT,INSERT ON minute_play_void_cursors TO mural_runtime;
GRANT UPDATE(completed_through_ms,window_start_ms,window_end_ms,page_token,next_poll_after,updated_at)
  ON minute_play_void_cursors TO mural_runtime;
