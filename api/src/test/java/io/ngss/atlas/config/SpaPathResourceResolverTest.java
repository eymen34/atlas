package io.ngss.atlas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Unit tests for the SPA deep-link / refresh fallback resolver (T-060). Plain
 * JUnit — {@code @WebMvcTest} is unavailable on this Boot 4 test classpath
 * (webmvctest_unavailable), and the routing decision is pure logic, so it is
 * exercised directly without a Spring context.
 */
class SpaPathResourceResolverTest {

  // --- isSpaFallback: client-side routes fall back to the SPA shell -----------

  @Test
  void clientRoutesFallBackToTheSpaShell() {
    assertThat(SpaPathResourceResolver.isSpaFallback("projects/TESTAI/board")).isTrue();
    assertThat(SpaPathResourceResolver.isSpaFallback("projects/TESTAI")).isTrue();
    assertThat(SpaPathResourceResolver.isSpaFallback("projects/TESTAI/tickets/ENG-42")).isTrue();
    assertThat(SpaPathResourceResolver.isSpaFallback("settings")).isTrue();
  }

  // --- isSpaFallback: API / ops / docs namespaces must NOT fall back ----------

  @Test
  void apiOpsAndDocsNamespacesNeverFallBack() {
    assertThat(SpaPathResourceResolver.isSpaFallback("api/projects")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("api")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("actuator/prometheus")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("actuator")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("internal/tasks/drain-outbox")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("v3/api-docs")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("swagger-ui/index.html")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("health")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("ready")).isFalse();
  }

  @Test
  void aPrefixIsMatchedOnTheSegmentBoundaryNotAsASubstring() {
    // "apiary" / "readyish" are real client routes that merely start with an
    // excluded prefix's letters — they must still fall back.
    assertThat(SpaPathResourceResolver.isSpaFallback("apiary")).isTrue();
    assertThat(SpaPathResourceResolver.isSpaFallback("readyish")).isTrue();
  }

  // --- isSpaFallback: missing static assets keep their 404 --------------------

  @Test
  void missingStaticAssetsDoNotFallBack() {
    assertThat(SpaPathResourceResolver.isSpaFallback("assets/index-abc123.js")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("favicon.ico")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("logo.svg")).isFalse();
    assertThat(SpaPathResourceResolver.isSpaFallback("projects/data.json")).isFalse();
  }

  // --- getResource: real file served, route → shell, asset → null ------------

  @Test
  void getResourceServesARealStaticFileWhenItExists(@TempDir Path tempDir) throws IOException {
    Files.writeString(tempDir.resolve("app.js"), "console.log(1)");
    Resource location = new FileSystemResource(tempDir.toString() + "/");

    Resource resolved = new SpaPathResourceResolver().getResource("app.js", location);

    assertThat(resolved).isNotNull();
    assertThat(resolved.getFilename()).isEqualTo("app.js");
  }

  @Test
  void getResourceFallsBackToIndexHtmlForAnUnmatchedClientRoute(@TempDir Path tempDir)
      throws IOException {
    Resource location = new FileSystemResource(tempDir.toString() + "/");

    Resource resolved =
        new SpaPathResourceResolver().getResource("projects/TESTAI/board", location);

    assertThat(resolved).isNotNull();
    assertThat(resolved.getFilename()).isEqualTo("index.html");
  }

  @Test
  void getResourceReturnsNullForAMissingAssetSoItKeeps404(@TempDir Path tempDir)
      throws IOException {
    Resource location = new FileSystemResource(tempDir.toString() + "/");

    Resource resolved =
        new SpaPathResourceResolver().getResource("assets/missing.js", location);

    assertThat(resolved).isNull();
  }

  @Test
  void getResourceReturnsNullForAnUnmatchedApiPathSoItKeeps404(@TempDir Path tempDir)
      throws IOException {
    Resource location = new FileSystemResource(tempDir.toString() + "/");

    Resource resolved = new SpaPathResourceResolver().getResource("api/nope", location);

    assertThat(resolved).isNull();
  }
}
