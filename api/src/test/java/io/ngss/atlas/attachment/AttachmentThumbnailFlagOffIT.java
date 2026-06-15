package io.ngss.atlas.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-4: with {@code FEATURE_INLINE_THUMBNAILS_ENABLED=false}, finalizing an image enqueues ZERO
 * ATTACHMENT_THUMBNAIL rows and generates no thumbnail — a clean no-op that leaves the attachment
 * row READY with a NULL thumbnail key. Separate context (own property set) so the OFF flag is wired
 * at bean construction, mirroring {@code WatcherFlagOffIT}.
 */
@TestPropertySource(properties = "app.feature.inline-thumbnails.enabled=false")
class AttachmentThumbnailFlagOffIT extends AttachmentITBase {

  @Test
  void flagOff_finalizeEnqueuesZeroThumbnailRows() {
    UUID alice = register("alice@example.com", "Alice");
    String token = sign(alice);
    String eng = createProject(token, "ENG", "Engineering");
    String ticket = createTicket(token, eng, "Images");

    byte[] body = png(40, 40);
    Response init = initUpload(token, ticket, "pic.png", "image/png", body.length);
    init.then().statusCode(201);
    String id = init.jsonPath().getString("attachmentId");
    assertThat(httpPut(init.jsonPath().getString("uploadUrl"), body, "image/png")).isEqualTo(200);
    finalizeUpload(token, id).then().statusCode(200).body("status", equalTo("READY"));

    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE kind = 'ATTACHMENT_THUMBNAIL'", Long.class);
    assertThat(rows).isZero();
    assertThat(thumbnailKey(id)).isNull();
  }

  private static byte[] png(int w, int h) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", baos);
      return baos.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
