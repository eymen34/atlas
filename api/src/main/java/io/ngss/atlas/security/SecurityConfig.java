package io.ngss.atlas.security;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * T-009 stateless security scaffold. CSRF disabled, session policy STATELESS,
 * CORS configured from {@code app.cors.allowed-origins} (comma-separated).
 *
 * <p>Permit list (unauthenticated): /health, /ready, /actuator/prometheus,
 * springdoc/Swagger UI, and the three pre-login auth endpoints
 * (login, register, refresh). Everything else under /api/** requires a
 * valid Bearer JWT. /internal/** is denyAll today and will be gated by a
 * shared-secret header in T-029.
 *
 * <p>The {@code bearerAuth} OpenAPI security scheme is declared at the
 * class level so {@code @SecurityRequirement(name="bearerAuth")} on auth
 * stub endpoints renders correctly in /v3/api-docs.
 */
@Configuration
@EnableWebSecurity
// T-015: enables @PreAuthorize/@PostAuthorize support project-wide. No method
// is annotated yet (project authz is enforced imperatively via ProjectAccessGuard);
// present so future capability annotations work without a config change. Annotation
// only — adds no bean that touches a DB at refresh, so AppCDS stage-3 is unaffected.
@EnableMethodSecurity
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class SecurityConfig {

  private final List<String> corsAllowedOrigins;

  public SecurityConfig(
      @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:4173}")
          String corsAllowedOrigins) {
    this.corsAllowedOrigins =
        Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      JsonAuthenticationEntryPoint authenticationEntryPoint,
      JsonAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Unauthenticated observability + docs.
                    .requestMatchers(HttpMethod.GET, "/health", "/ready").permitAll()
                    // Actuator surface T-005 designed: /actuator (discovery
                    // link index), /actuator/health (+ groups), and
                    // /actuator/prometheus are reachable without auth so
                    // load balancers and Prometheus scrape can hit them.
                    // Sensitive actuator paths (env, beans, configprops,
                    // info, heapdump, …) stay protected by the catch-all
                    // /actuator/** authenticated rule below.
                    .requestMatchers(
                            HttpMethod.GET,
                            "/actuator",
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/prometheus")
                        .permitAll()
                    .requestMatchers(
                            "/v3/api-docs",
                            "/v3/api-docs/**",
                            "/swagger-ui",
                            "/swagger-ui/**",
                            "/swagger-ui.html")
                        .permitAll()
                    // Static + SPA: anything outside /api, /actuator, /internal.
                    .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico")
                        .permitAll()
                    // Pre-login auth endpoints.
                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/auth/login",
                            "/api/auth/register",
                            "/api/auth/refresh")
                        .permitAll()
                    // T-023: public feature-flag config — readable before login so
                    // the SPA can gate flag-controlled UI. ONLY this exact GET path
                    // is opened (no /api/config/** glob); it must precede the
                    // /api/** authenticated catch-all below.
                    .requestMatchers(HttpMethod.GET, "/api/config/public").permitAll()
                    // Internal endpoints — denyAll today; T-029 wires a
                    // shared-secret header filter ahead of this rule.
                    .requestMatchers("/internal/**").denyAll()
                    // Every other /api/** path requires a valid Bearer token.
                    .requestMatchers("/api/**").authenticated()
                    // Actuator surface other than the explicit permits above
                    // (info, env, beans, heapdump, ...) requires auth — note
                    // that /actuator/health is wired by Actuator itself; only
                    // /actuator/prometheus is in the permit list.
                    .requestMatchers("/actuator/**").authenticated()
                    .anyRequest().permitAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    // allowCredentials=true requires an explicit origin list — never "*".
    cfg.setAllowedOrigins(new ArrayList<>(corsAllowedOrigins));
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
    cfg.setAllowedHeaders(List.of("*"));
    cfg.setExposedHeaders(List.of("X-Request-Id"));
    cfg.setAllowCredentials(true);
    cfg.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }

  /**
   * No-op user store. T-009 is the scaffold — authentication is performed
   * directly by {@link JwtAuthenticationFilter} reading the Bearer token; the
   * UserDetailsService bean is present only to satisfy Boot's security
   * auto-configuration (without it, Boot prints a generated dev password on
   * every boot, which leaks into structured logs).
   */
  @Bean
  UserDetailsService noOpUserDetailsService() {
    return new InMemoryUserDetailsManager(
        User.withUsername("placeholder").password("{noop}placeholder").authorities("USER").build());
  }

  /**
   * BCrypt encoder for password hashing (T-011 registration; T-012 login). Cost
   * is env-tunable via BCRYPT_COST (default 12 per env_var_convention). Returns
   * the $2a variant, so encoded hashes start with {@code $2a$<cost>$}.
   *
   * <p>AppCDS-safe: no secret or external dependency, default cost is valid, so
   * the bean constructs cleanly during the stage-3 no-DB boot.
   */
  @Bean
  PasswordEncoder passwordEncoder(@Value("${BCRYPT_COST:12}") int bcryptCost) {
    if (bcryptCost < 4 || bcryptCost > 31) {
      throw new IllegalStateException("BCRYPT_COST must be in [4,31], got " + bcryptCost);
    }
    return new BCryptPasswordEncoder(bcryptCost);
  }
}
