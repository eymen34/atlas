package io.ngss.atlas.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA deep-link / refresh fallback (T-060). Registers a {@code /**} static-resource
 * handler backed by {@link SpaPathResourceResolver}, which serves the built SPA's
 * {@code index.html} for unmatched non-API, non-asset GETs so any client-side route
 * resolves on refresh/deep-link.
 *
 * <p>Deliberately NOT a controller: it adds no request mapping, so springdoc emits
 * nothing for it (no OpenAPI drift) and SecurityConfig stays byte-unchanged — SPA
 * routes already fall through to the existing {@code anyRequest().permitAll()} rule.
 * Real handlers (controllers under {@code /api/**}, actuator, springdoc) outrank the
 * resource handler, so the fallback only sees paths nothing else claimed.
 */
@Configuration
public class SpaWebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaPathResourceResolver());
  }
}
