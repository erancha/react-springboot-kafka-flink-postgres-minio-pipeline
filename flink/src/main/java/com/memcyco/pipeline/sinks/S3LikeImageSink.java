package com.memcyco.pipeline.sinks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memcyco.pipeline.types.ProcessedEvent;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

public class S3LikeImageSink extends RichSinkFunction<ProcessedEvent> {
  private final ObjectMapper mapper;
  private transient MinioClient minio;
  private transient HttpClient http;
  private transient Connection conn;
  private transient PreparedStatement stmt;

  public S3LikeImageSink(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void open(Configuration parameters) {
    String endpoint = env("MINIO_ENDPOINT", "http://minio:9000");
    String accessKey = env("MINIO_ACCESS_KEY", "minio");
    String secretKey = env("MINIO_SECRET_KEY", "minio123");

    this.minio = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build();

    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    try {
      String url = env("POSTGRES_URL", "jdbc:postgresql://postgres:5432/warehouse");
      String user = env("POSTGRES_USER", "postgres");
      String password = env("POSTGRES_PASSWORD", "postgres");
      this.conn = DriverManager.getConnection(url, user, password);
      this.conn.setAutoCommit(true);

      this.stmt = conn.prepareStatement(
          "INSERT INTO processed_events (id, event_type, event_time, source, payload, image_object_key) "
              + "VALUES (?, ?, ?, ?, ?::jsonb, ?) "
              + "ON CONFLICT (id) DO UPDATE SET event_type = EXCLUDED.event_type, event_time = EXCLUDED.event_time, "
              + "source = EXCLUDED.source, payload = EXCLUDED.payload, image_object_key = EXCLUDED.image_object_key");
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize Postgres connection for image sink", e);
    }
  }

  @Override
  public void invoke(ProcessedEvent value, Context context) throws Exception {
    String bucket = env("MINIO_BUCKET", "images");

    byte[] bytes = null;
    String contentType = (value.getImageContentType() == null || value.getImageContentType().isBlank())
        ? "image/jpeg"
        : value.getImageContentType();

    if (value.getImageBase64() != null && !value.getImageBase64().isBlank()) {
      bytes = Base64.getDecoder().decode(value.getImageBase64());
    } else if (value.getImageUrl() != null && !value.getImageUrl().isBlank()) {
      HttpRequest req = HttpRequest.newBuilder(URI.create(value.getImageUrl())).GET().build();
      HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (resp.statusCode() / 100 != 2) {
        throw new IllegalStateException("Failed to fetch imageUrl status=" + resp.statusCode());
      }
      bytes = resp.body();
      contentType = resp.headers().firstValue("content-type").orElse(contentType);
    } else {
      return;
    }

    String date = DateTimeFormatter.ISO_LOCAL_DATE.format(value.getDate());
    String extension = guessExtension(contentType);
    String objectKey = "images/" + date + "/" + value.getId() + extension;

    try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
      minio.putObject(
          PutObjectArgs.builder()
              .bucket(bucket)
              .object(objectKey)
              .stream(in, bytes.length, -1)
              .contentType(contentType)
              .build());
    }

    stmt.setObject(1, value.getId());
    stmt.setString(2, value.getEventType());
    stmt.setTimestamp(3, Timestamp.from(value.getEventTime()));
    stmt.setString(4, value.getSource());
    stmt.setString(5, null);
    stmt.setString(6, objectKey);
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

  private static String guessExtension(String contentType) {
    String ct = contentType == null ? "" : contentType.toLowerCase();
    if (ct.contains("png"))
      return ".png";
    if (ct.contains("webp"))
      return ".webp";
    if (ct.contains("gif"))
      return ".gif";
    return ".jpg";
  }
}
