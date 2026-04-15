package com.memcyco.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memcyco.pipeline.sinks.PostgresProcessedEventSink;
import com.memcyco.pipeline.sinks.S3LikeImageSink;
import com.memcyco.pipeline.types.ProcessedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class StreamingJob {
  public static void main(String[] args) throws Exception {
    String kafkaBootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
    String kafkaTopic = env("KAFKA_TOPIC", "events");

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.enableCheckpointing(10_000);

    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    KafkaSource<String> source = KafkaSource.<String>builder()
        .setBootstrapServers(kafkaBootstrap)
        .setTopics(kafkaTopic)
        .setGroupId("flink-processor")
        .setStartingOffsets(OffsetsInitializer.earliest())
        .setValueOnlyDeserializer(new SimpleStringSchema())
        .build();

    DataStream<String> json = env.fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-events");

    DataStream<ProcessedEvent> processed = json
        .map(value -> {
          Map<String, Object> event = mapper.readValue(value, new TypeReference<Map<String, Object>>() {
          });

          String id = String.valueOf(event.getOrDefault("id", UUID.randomUUID().toString()));
          String eventType = String.valueOf(event.getOrDefault("eventType", "UNKNOWN")).toUpperCase();
          String eventTimeStr = String.valueOf(event.getOrDefault("eventTime", Instant.now().toString()));
          Instant eventTime = Instant.parse(eventTimeStr);
          String sourceName = String.valueOf(event.getOrDefault("source", "unknown"));

          Map<String, Object> payload = null;
          if (event.get("payload") instanceof Map<?, ?> m) {
            payload = (Map<String, Object>) m;
          }

          String imageUrl = Optional.ofNullable(event.get("imageUrl")).map(Object::toString).orElse(null);
          String imageBase64 = Optional.ofNullable(event.get("imageBase64")).map(Object::toString).orElse(null);
          String imageContentType = Optional.ofNullable(event.get("imageContentType")).map(Object::toString)
              .orElse("image/jpeg");

          LocalDate date = eventTime.atZone(ZoneOffset.UTC).toLocalDate();

          return new ProcessedEvent(
              UUID.fromString(id),
              eventType,
              eventTime,
              sourceName,
              payload,
              imageUrl,
              imageBase64,
              imageContentType,
              date);
        })
        .name("parse-json");

    processed
        .filter(e -> "IMAGE".equals(e.getEventType()))
        .addSink(new S3LikeImageSink(mapper))
        .name("image-to-minio");

    processed
        .filter(e -> "DATA".equals(e.getEventType()))
        .addSink(new PostgresProcessedEventSink(mapper))
        .name("data-to-postgres");

    env.execute("Kafka->Flink->(MinIO,Postgres)");
  }

  private static String env(String name, String def) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? def : v;
  }
}
