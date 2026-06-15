package io.ngss.atlas.config;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA deep-link / refresh fallback (T-060). The single-page app uses client-side
 * routing, so a refresh or deep-link to a client route (e.g.
 * {@code /projects/TESTAI/board}) reaches the server as a GET with no matching
 * controller and no matching static file — which would otherwise 404. This
 * resolver serves the SPA shell ({@code classpath:/static/index.html}) for those
 * requests so any front-end route resolves on refresh/deep-link.
 *
 * <p>It is wired ONLY onto the {@code /**} static-resource handler
 * ({@link SpaWebMvcConfig}), so real handlers (controllers, actuator, springdoc)
 * already take precedence and never reach here. As defense in depth it still
 * declines to fall back for the API/ops/docs namespaces and for missing static
 * assets, so those keep their natural 404/401 instead of being masked by the SPA
 * shell.
 *
 * <p>The {@code index.html} reference is constructed lazily-read: building a
 * {@link ClassPathResource} performs no I/O, so this bean is AppCDS-safe (the
 * stage-3 no-DB boot never touches the file; the built SPA is copied into
 * {@code static/} only at package time).
 */
class SpaPathResourceResolver extends PathResourceResolver {

  /**
   * Location-relative path prefixes that must NEVER fall back to the SPA shell —
   * they keep their own response (or a real 404/401). Paths are relative to the
   * {@code /**} handler location, so they carry no leading slash. {@code v3}
   * covers {@code /v3/api-docs}; {@code swagger-ui} covers the Swagger UI routes.
   */
  private static final List<String> EXCLUDED_PREFIXES =
      List.of("api", "actuator", "internal", "v3", "swagger-ui", "health", "ready");

  private final Resource indexHtml = new ClassPathResource("static/index.html");

  @Override
  protected Resource getResource(String resourcePath, Resource location) throws IOException {
    // Let the parent do the location-safe lookup first (existence, readability,
    // path-traversal / under-location checks). A real static file — index.html,
    // /assets/**, favicon.ico — is served as-is.
    Resource resolved = super.getResource(resourcePath, location);
    if (resolved != null) {
      return resolved;
    }
    // No real file matched: serve the SPA shell for a client-side route, else
    // return null so the request keeps its natural 404.
    return isSpaFallback(resourcePath) ? indexHtml : null;
  }

  /**
   * Whether an unmatched GET should fall back to the SPA shell. True for a
   * client-side route (no excluded namespace, no file-extension last segment);
   * false for an API/ops/docs path or a missing static asset.
   */
  static boolean isSpaFallback(String resourcePath) {
    for (String prefix : EXCLUDED_PREFIXES) {
      if (resourcePath.equals(prefix) || resourcePath.startsWith(prefix + "/")) {
        return false;
      }
    }
    // A last segment containing a '.' is a static-asset request (e.g. a missing
    // JS/CSS/image or swagger-ui.html) — let it 404 rather than masking it with
    // the SPA shell. SPA routes have no file extension.
    int slash = resourcePath.lastIndexOf('/');
    String lastSegment = slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
    if (lastSegment.contains(".")) {
      return false;
    }
    return true;
  }
}
