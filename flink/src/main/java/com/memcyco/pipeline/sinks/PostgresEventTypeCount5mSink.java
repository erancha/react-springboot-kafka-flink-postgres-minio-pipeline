package com.memcyco.pipeline.sinks;

import com.memcyco.pipeline.types.EventTypeCount5m;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

public class PostgresEventTypeCount5mSink extends RichSinkFunction<EventTypeCount5m> {
  private transient Connection conn;
  private transient PreparedStatement stmt;

  @Override
  public void open(Configuration parameters) throws Exception {
    String url = env("POSTGRES_URL", "jdbc:postgresql://postgres:5432/warehouse");
    String user = env("POSTGRES_USER", "postgres");
    String password = env("POSTGRES_PASSWORD", "postgres");

    this.conn = DriverManager.getConnection(url, user, password);
    this.conn.setAutoCommit(true);

    this.stmt = conn.prepareStatement(
        "INSERT INTO event_type_counts_5m (window_start, window_end, event_type, event_count, updated_at) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (window_start, event_type) DO UPDATE SET window_end = EXCLUDED.window_end, "
            + "event_count = EXCLUDED.event_count, updated_at = EXCLUDED.updated_at");
  }

  @Override
  public void invoke(EventTypeCount5m value, Context context) throws Exception {
    stmt.setTimestamp(1, Timestamp.from(value.getWindowStart()));
    stmt.setTimestamp(2, Timestamp.from(value.getWindowEnd()));
    stmt.setString(3, value.getEventType());
    stmt.setLong(4, value.getEventCount());
    stmt.setTimestamp(5, Timestamp.from(Instant.now()));
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
}
