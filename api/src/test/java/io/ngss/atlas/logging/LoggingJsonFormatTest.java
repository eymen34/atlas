package io.ngss.atlas.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class LoggingJsonFormatTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Logger root;
  private OutputStreamAppender<ILoggingEvent> appender;
  private ByteArrayOutputStream buffer;
  private org.slf4j.Logger log;

  @BeforeEach
  void attachJsonAppender() {
    root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    buffer = new ByteArrayOutputStream();
    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(root.getLoggerContext());
    encoder.start();
    appender = new OutputStreamAppender<>();
    appender.setContext(root.getLoggerContext());
    appender.setOutputStream(buffer);
    appender.setEncoder(encoder);
    appender.start();
    root.addAppender(appender);
    log = LoggerFactory.getLogger(LoggingJsonFormatTest.class);
    MDC.clear();
  }

  @AfterEach
  void detachJsonAppender() {
    root.detachAppender(appender);
    appender.stop();
    MDC.clear();
    assertThat(appender.isStarted()).isFalse();
  }

  @Test
  void infoAndErrorEmitSingleLineJsonWithRequiredFields() throws Exception {
    MDC.put("request_id", "test-req-123");
    MDC.put("user_id", "user-abc");
    log.info("info message");
    MDC.clear();
    log.error("error message", new RuntimeException("boom"));

    String raw = buffer.toString(StandardCharsets.UTF_8);
    String[] lines = raw.split("\n");
    assertThat(lines).hasSize(2);

    JsonNode infoNode = MAPPER.readTree(lines[0]);
    assertField(infoNode, "@timestamp");
    assertField(infoNode, "level");
    assertField(infoNode, "logger_name");
    assertField(infoNode, "thread_name");
    assertField(infoNode, "message");
    assertThat(infoNode.get("level").asText()).isEqualTo("INFO");
    assertThat(infoNode.get("message").asText()).isEqualTo("info message");
    assertThat(infoNode.get("request_id").asText()).isEqualTo("test-req-123");
    assertThat(infoNode.get("user_id").asText()).isEqualTo("user-abc");

    JsonNode errorNode = MAPPER.readTree(lines[1]);
    assertField(errorNode, "@timestamp");
    assertThat(errorNode.get("level").asText()).isEqualTo("ERROR");
    assertThat(errorNode.get("message").asText()).isEqualTo("error message");
    assertThat(errorNode.has("stack_trace")).as("ERROR with throwable must include stack_trace").isTrue();
    assertThat(errorNode.get("stack_trace").asText()).contains("RuntimeException");
  }

  @Test
  void everyEmittedLineIsSingleLineJsonWithoutEmbeddedNewlines() throws Exception {
    log.info("first");
    log.error("second", new RuntimeException("multi\nline\nthrowable"));

    String raw = buffer.toString(StandardCharsets.UTF_8);
    String[] lines = raw.split("\n");
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      JsonNode node = MAPPER.readTree(line);
      assertThat(node.isObject()).isTrue();
      // The line bytes themselves must not contain a raw '\n' inside the JSON
      // content. The split above handles the trailing newline separator; any
      // line that survives the split should be one complete JSON object.
      assertThat(line).doesNotContain("\n");
    }
  }

  @Test
  void logbackSpringXmlHasOnlyConsoleAppenderAndLogstashEncoder() throws Exception {
    File config = new File("src/main/resources/logback-spring.xml");
    assertThat(config).exists();

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document doc = dbf.newDocumentBuilder().parse(config);

    NodeList appenders = doc.getElementsByTagName("appender");
    assertThat(appenders.getLength()).isGreaterThanOrEqualTo(1);
    for (int i = 0; i < appenders.getLength(); i++) {
      Element appenderEl = (Element) appenders.item(i);
      String cls = appenderEl.getAttribute("class");
      assertThat(cls)
          .as("appender class must not be a file or rolling variant")
          .doesNotContain("FileAppender")
          .doesNotContain("RollingFileAppender");
      assertThat(cls).contains("ConsoleAppender");
    }

    NodeList encoders = doc.getElementsByTagName("encoder");
    assertThat(encoders.getLength()).isGreaterThanOrEqualTo(1);
    for (int i = 0; i < encoders.getLength(); i++) {
      Element encoderEl = (Element) encoders.item(i);
      String cls = encoderEl.getAttribute("class");
      assertThat(cls)
          .as("encoder must be the Logstash JSON encoder, not a PatternLayoutEncoder")
          .doesNotContain("PatternLayoutEncoder");
      assertThat(cls).contains("LogstashEncoder");
    }
  }

  private static void assertField(JsonNode node, String name) {
    assertThat(node.has(name)).as("JSON must contain field %s", name).isTrue();
    assertThat(node.get(name).isNull()).isFalse();
    if (node.get(name).isTextual()) {
      assertThat(node.get(name).asText()).isNotBlank();
    }
  }
}
