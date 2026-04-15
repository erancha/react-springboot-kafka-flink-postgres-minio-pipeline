package com.memcyco.pipeline.sinks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memcyco.pipeline.types.ProcessedEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

public class PostgresProcessedEventSink extends RichSinkFunction<ProcessedEvent> {
  private final ObjectMapper mapper;
  private transient Connection conn;
  private transient PreparedStatement stmt;

  public PostgresProcessedEventSink(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    String url = env("POSTGRES_URL", "jdbc:postgresql://postgres:5432/warehouse");
    String user = env("POSTGRES_USER", "postgres");
    String password = env("POSTGRES_PASSWORD", "postgres");

    this.conn = DriverManager.getConnection(url, user, password);
    this.conn.setAutoCommit(true);

    this.stmt = conn.prepareStatement(
        "INSERT INTO processed_events (id, event_type, event_time, source, payload, image_object_key, inserted_at) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET event_type = EXCLUDED.event_type, event_time = EXCLUDED.event_time, "
            + "source = EXCLUDED.source, payload = EXCLUDED.payload, image_object_key = EXCLUDED.image_object_key");
  }

  @Override
  public void invoke(ProcessedEvent value, Context context) throws Exception {
    String payloadJson = value.getPayload() == null ? null
        : mapper.writeValueAsString(cleanPayload(value.getPayload()));

    stmt.setObject(1, value.getId());
    stmt.setString(2, value.getEventType());
    stmt.setTimestamp(3, Timestamp.from(value.getEventTime()));
    stmt.setString(4, value.getSource());
    stmt.setString(5, payloadJson);
    stmt.setString(6, null);
    stmt.setTimestamp(7, Timestamp.from(Instant.now()));
    stmt.executeUpdate();
  }

  @Override
  public void close() throws Exception {
    if (stmt != null)
      stmt.close();
    if (conn != null)
      conn.close();
  }

  private static String env(String name, String def) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? def : v;
  }

  private Object cleanPayload(Object payload) {
    return payload;
  }
}
