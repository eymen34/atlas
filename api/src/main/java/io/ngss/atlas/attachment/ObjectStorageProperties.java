package io.ngss.atlas.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Object-storage configuration (T-025). Read from the OBJECT_STORAGE_* env vars
 * (NOT {@code APP_}-prefixed — same convention as JWT_SECRET / FEATURE_*; see
 * {@code .env.example}). All {@code @Value}s carry empty-string defaults and the
 * constructor performs NO validation, so this bean constructs cleanly during the
 * Dockerfile stage-3 no-DB AppCDS boot (appcds_boot_safety). {@link #validate()} is
 * invoked LAZILY by the {@link S3Config} {@code @Lazy} beans at first use.
 *
 * <p>TWO endpoints by design (dual-endpoint presigning): {@link #endpoint()} is the
 * server-side address (HEAD / thumbnail GET+PUT), reachable from inside the Docker
 * network; {@link #publicEndpoint()} is what presigned URLs are signed against —
 * it MUST be resolvable from the browser. A presigned URL embeds the host it was
 * signed for and cannot be rewritten afterward, hence two clients.
 */
@Component
public class ObjectStorageProperties {

  private final String endpoint;
  private final String publicEndpoint;
  private final String region;
  private final String bucket;
  private final String accessKey;
  private final String secretKey;
  private final long maxSizeBytes;

  public ObjectStorageProperties(
      @Value("${OBJECT_STORAGE_ENDPOINT:}") String endpoint,
      @Value("${OBJECT_STORAGE_PUBLIC_ENDPOINT:}") String publicEndpoint,
      @Value("${OBJECT_STORAGE_REGION:us-east-1}") String region,
      @Value("${OBJECT_STORAGE_BUCKET:}") String bucket,
      @Value("${OBJECT_STORAGE_ACCESS_KEY:}") String accessKey,
      @Value("${OBJECT_STORAGE_SECRET_KEY:}") String secretKey,
      @Value("${ATTACHMENT_MAX_SIZE_BYTES:26214400}") long maxSizeBytes) {
    this.endpoint = endpoint;
    this.publicEndpoint = publicEndpoint;
    this.region = region;
    this.bucket = bucket;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.maxSizeBytes = maxSizeBytes;
  }

  /**
   * Fails loudly if a required value is blank. Called at FIRST USE (from the
   * {@code @Lazy} S3 beans), never at construction — so a misconfigured deployment
   * surfaces a clear message on the first attachment request rather than a cryptic
   * AWS SDK error, while the stage-3 AppCDS boot (no env) stays unaffected.
   */
  public void validate() {
    requireSet("OBJECT_STORAGE_ENDPOINT", endpoint);
    requireSet("OBJECT_STORAGE_PUBLIC_ENDPOINT", publicEndpoint);
    requireSet("OBJECT_STORAGE_REGION", region);
    requireSet("OBJECT_STORAGE_BUCKET", bucket);
    requireSet("OBJECT_STORAGE_ACCESS_KEY", accessKey);
    requireSet("OBJECT_STORAGE_SECRET_KEY", secretKey);
  }

  private static void requireSet(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name + " is not configured — set it in the environment (see .env.example).");
    }
  }

  public String endpoint() {
    return endpoint;
  }

  public String publicEndpoint() {
    return publicEndpoint;
  }

  public String region() {
    return region;
  }

  public String bucket() {
    return bucket;
  }

  public String accessKey() {
    return accessKey;
  }

  public String secretKey() {
    return secretKey;
  }

  public long maxSizeBytes() {
    return maxSizeBytes;
  }
}
