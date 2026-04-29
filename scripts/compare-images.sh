#!/bin/bash

# Compare image counts in MinIO vs PostgreSQL

set -e

echo "=== Image Count Comparison ==="
echo

# Count images in MinIO using the mc CLI from the minio container
echo "Images in MinIO bucket 'images':"
MINIO_COUNT=$(docker exec minio mc ls --recursive local/images | wc -l)
echo "  $MINIO_COUNT objects"
echo

# Count images in PostgreSQL (processed_events with eventType='IMAGE' and non-null image_object_key)
echo "Image records in PostgreSQL (processed_events):"
POSTGRES_COUNT=$(docker exec postgres psql -U postgres -d warehouse -t -c "SELECT COUNT(*) FROM processed_events WHERE event_type='IMAGE' AND image_object_key IS NOT NULL;")
echo "  $POSTGRES_COUNT records"
echo

# Count images aggregated by Flink in real-time (event_type_counts_5m)
echo "Image events aggregated by Flink (event_type_counts_5m):"
FLINK_COUNT=$(docker exec postgres psql -U postgres -d warehouse -t -c "SELECT COALESCE(SUM(event_count), 0) FROM event_type_counts_5m WHERE event_type='IMAGE';")
echo "  $FLINK_COUNT events (summed across all 5-minute windows)"
echo

# Compare
echo "Comparison:"
if [ "$MINIO_COUNT" -eq "$POSTGRES_COUNT" ]; then
  echo "  ✓ MinIO vs PostgreSQL: Counts match!"
else
  DIFF=$((MINIO_COUNT - POSTGRES_COUNT))
  echo "  ✗ MinIO vs PostgreSQL: Mismatch of $DIFF" | awk '{if ($NF < 0) gsub(/of/, "of -"); print}'
fi

echo "  Flink pre-aggregated count: $FLINK_COUNT events"
