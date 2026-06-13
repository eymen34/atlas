package io.ngss.atlas.attachment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Thumbnail worker: image→JPEG thumbnail, non-image→none, bomb guard, fault isolation (T-025). */
class AttachmentThumbnailIT extends AttachmentITBase {

  // Spy the internal S3Client so one test can make the thumbnail PUT throw without
  // touching finalize (which uses headObject) or the presigned upload (direct HTTP).
  @MockitoSpyBean S3Client s3Client;

  private record Fixture(String token, String ticket) {}

  private Fixture aliceWithTicket() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    String ticket = createTicket(token, eng, "Images");
    return new Fixture(token, ticket);
  }

  private static byte[] png(int w, int h, int type) {
    try {
      BufferedImage img = new BufferedImage(w, h, type);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(img, "png", baos);
      return baos.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Uploads + finalizes an attachment, returning its id. */
  private String upload(Fixture f, String filename, String contentType, byte[] body) {
    Response init = initUpload(f.token(), f.ticket(), filename, contentType, body.length);
    init.then().statusCode(201);
    String id = init.jsonPath().getString("attachmentId");
    assertThat(httpPut(init.jsonPath().getString("uploadUrl"), body, contentType)).isEqualTo(200);
    finalizeUpload(f.token(), id).then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("READY"));
    return id;
  }

  @Test
  void imageFinalize_generatesJpegThumbnail() {
    Fixture f = aliceWithTicket();
    String id = upload(f, "pic.png", "image/png", png(120, 90, BufferedImage.TYPE_INT_RGB));

    // AFTER_COMMIT runs synchronously on the request thread, so the thumbnail is ready now.
    String thumbnailKey = thumbnailKey(id);
    assertThat(thumbnailKey).isNotNull().isEqualTo("thumbnails/" + id + ".jpg");
    assertThat(objectExists(thumbnailKey)).isTrue();

    listAttachments(f.token(), f.ticket()).then().statusCode(200).body("[0].hasThumbnail", org.hamcrest.Matchers.equalTo(true));
    downloadUrl(f.token(), id, "?thumbnail=true").then().statusCode(200);
  }

  @Test
  void nonImageFinalize_producesNoThumbnail() {
    Fixture f = aliceWithTicket();
    String id = upload(f, "notes.txt", "text/plain", "just text".getBytes(UTF_8));

    assertThat(thumbnailKey(id)).isNull();
    downloadUrl(f.token(), id, "?thumbnail=true").then().statusCode(404);
  }

  @Test
  void decompressionBomb_skipsThumbnail_keepsReady_andLogsError() {
    Fixture f = aliceWithTicket();
    // 4200x4200 → 4200*4200*4 ≈ 70 MB decoded (> 64 MiB guard). GRAY keeps the TEST heap
    // small; the PNG header still declares the large dimensions the guard reads.
    byte[] bomb = png(4200, 4200, BufferedImage.TYPE_BYTE_GRAY);

    Logger listenerLogger = (Logger) LoggerFactory.getLogger(AttachmentThumbnailListener.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    listenerLogger.addAppender(appender);
    String id;
    try {
      id = upload(f, "bomb.png", "image/png", bomb);
    } finally {
      listenerLogger.detachAppender(appender);
    }

    assertThat(attachmentStatus(id)).isEqualTo("READY"); // finalize unaffected
    assertThat(thumbnailKey(id)).isNull(); // no thumbnail
    boolean errorLogged = appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR);
    assertThat(errorLogged).as("bomb guard should log at ERROR").isTrue();
  }

  @Test
  void throwingThumbnailPut_doesNotFailFinalize() {
    Fixture f = aliceWithTicket();
    // Make the thumbnail PUT blow up; finalize (headObject) and the upload are unaffected.
    doThrow(new RuntimeException("boom: thumbnail upload failed"))
        .when(s3Client)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));

    Logger listenerLogger = (Logger) LoggerFactory.getLogger(AttachmentThumbnailListener.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    listenerLogger.addAppender(appender);
    String id;
    try {
      id = upload(f, "pic.png", "image/png", png(60, 60, BufferedImage.TYPE_INT_RGB));
    } finally {
      listenerLogger.detachAppender(appender);
    }

    assertThat(attachmentStatus(id)).isEqualTo("READY"); // finalize succeeded despite the throw
    assertThat(thumbnailKey(id)).isNull(); // PUT failed → no thumbnail key
    boolean errorLogged = appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR);
    assertThat(errorLogged).as("failed thumbnail should log at ERROR").isTrue();
  }
}
