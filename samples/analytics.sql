-- Analytics SQL examples
--
-- Run with:
--   ./scripts/sql-file.sh samples/analytics.sql

-- Count events by type
SELECT event_type, COUNT(*)
FROM processed_events
GROUP BY event_type
ORDER BY COUNT(*) DESC;

-- Retrieve latest processed records
SELECT id, event_type, event_time, source, image_object_key, inserted_at
FROM processed_events
ORDER BY inserted_at DESC
LIMIT 20;

-- Aggregate events by hour
SELECT date_trunc('hour', event_time) AS hour, event_type, COUNT(*)
FROM processed_events
GROUP BY 1, 2
ORDER BY 1 DESC, 2;
