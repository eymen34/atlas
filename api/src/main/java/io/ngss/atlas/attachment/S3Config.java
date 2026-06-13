package io.ngss.atlas.attachment;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3/MinIO client beans (T-025), DUAL-ENDPOINT by design (D1):
 *
 * <ul>
 *   <li>{@link #s3Client} — endpointOverride = OBJECT_STORAGE_ENDPOINT (internal).
 *       Server-side HEAD on finalize + thumbnail GET/PUT. Uses the lightweight sync
 *       UrlConnectionHttpClient.
 *   <li>{@link #s3Presigner} — endpointOverride = OBJECT_STORAGE_PUBLIC_ENDPOINT
 *       (browser-resolvable). Signs every URL returned to the client; a presigned URL
 *       embeds the host it was signed against and cannot be rewritten, so it MUST be
 *       signed with the public endpoint.
 * </ul>
 *
 * <p>Both beans are {@code @Lazy} and MUST be injected via {@code @Lazy} injection
 * points / ObjectProvider so the Dockerfile stage-3 no-DB AppCDS context refresh
 * never constructs them (appcds_boot_safety). forcePathStyle / pathStyleAccess is on
 * for MinIO. Config is validated at first use here, not in any constructor.
 */
@Configuration
public class S3Config {

  @Bean
  @Lazy
  S3Client s3Client(ObjectStorageProperties props) {
    props.validate();
    return S3Client.builder()
        .endpointOverride(URI.create(props.endpoint()))
        .region(Region.of(props.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
        .forcePathStyle(true)
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  @Bean
  @Lazy
  S3Presigner s3Presigner(ObjectStorageProperties props) {
    props.validate();
    return S3Presigner.builder()
        .endpointOverride(URI.create(props.publicEndpoint()))
        .region(Region.of(props.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
