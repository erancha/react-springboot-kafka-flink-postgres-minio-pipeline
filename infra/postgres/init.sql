CREATE TABLE IF NOT EXISTS processed_events (
  id UUID PRIMARY KEY,
  event_type TEXT NOT NULL,
  event_time TIMESTAMPTZ NOT NULL,
  source TEXT NOT NULL,
  payload JSONB NULL,
  image_object_key TEXT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_processed_events_type_time
  ON processed_events (event_type, event_time DESC);

CREATE INDEX IF NOT EXISTS idx_processed_events_inserted_at
  ON processed_events (inserted_at DESC);
