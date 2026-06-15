package io.ngss.atlas.auth;

import io.ngss.atlas.auth.dto.AuthResponse;
import io.ngss.atlas.auth.dto.LoginRequest;
import io.ngss.atlas.auth.dto.RegisterRequest;
import io.ngss.atlas.auth.dto.UserProfileResponse;
import io.ngss.atlas.auth.dto.UserRegisteredResponse;
import io.ngss.atlas.domain.PasswordCredential;
import io.ngss.atlas.domain.PasswordCredentialRepository;
import io.ngss.atlas.domain.RegistrationService;
import io.ngss.atlas.domain.User;
import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.security.JwtIssuer;
import io.ngss.atlas.security.LoginAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. register (T-011) and login/refresh/logout/me (T-012) are all
 * live. Class-level {@code produces=application/json} keeps springdoc content
 * types concrete; body POSTs declare {@code consumes=application/json}.
 */
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth")
public class AuthController {

  // Pre-computed BCrypt(cost 12) hash of a fixed sentinel — NOT a real
  // credential. login runs a BCrypt comparison against this when the email is
  // unknown, so response latency does not reveal whether an account exists
  // (timing-based enumeration defence). Computed once at class load; never logged.
  private static final String DUMMY_BCRYPT_HASH =
      new BCryptPasswordEncoder(12).encode("timing-equalization-sentinel-not-a-secret");

  private final RegistrationService registrationService;
  private final JwtIssuer jwtIssuer;
  private final RefreshTokenService refreshTokenService;
  private final UserRepository userRepository;
  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordEncoder passwordEncoder;
  private final LoginAttemptService loginAttemptService;
  private final AuthCookieFactory authCookieFactory;

  public AuthController(
      RegistrationService registrationService,
      JwtIssuer jwtIssuer,
      RefreshTokenService refreshTokenService,
      UserRepository userRepository,
      PasswordCredentialRepository passwordCredentialRepository,
      PasswordEncoder passwordEncoder,
      LoginAttemptService loginAttemptService,
      AuthCookieFactory authCookieFactory) {
    this.registrationService = registrationService;
    this.jwtIssuer = jwtIssuer;
    this.refreshTokenService = refreshTokenService;
    this.userRepository = userRepository;
    this.passwordCredentialRepository = passwordCredentialRepository;
    this.passwordEncoder = passwordEncoder;
    this.loginAttemptService = loginAttemptService;
    this.authCookieFactory = authCookieFactory;
  }

  @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Register a new user account")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "User created",
        content = @Content(schema = @Schema(implementation = UserRegisteredResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "409", description = "Email already registered")
  })
  public ResponseEntity<UserRegisteredResponse> register(@Valid @RequestBody RegisterRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(req));
  }

  @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Authenticate with email + password and receive a token pair")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Authenticated",
        content = @Content(schema = @Schema(implementation = AuthResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Invalid credentials"),
    @ApiResponse(
        responseCode = "429",
        description = "Too Many Requests — account or IP locked due to repeated failed attempts")
  })
  public ResponseEntity<AuthResponse> login(
      @Valid @RequestBody LoginRequest req, HttpServletRequest request) {
    String email = req.email().trim().toLowerCase(Locale.ROOT);
    String clientIp = loginAttemptService.extractClientIp(request);
    // T-033: throttle BEFORE any user lookup / credential check — a correct password during an
    // active lockout still 429s (D4), and unknown emails throttle identically (anti-enumeration).
    loginAttemptService.checkThrottle(email, clientIp);

    User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
    if (user == null) {
      // Timing equalization: spend a BCrypt comparison even for unknown emails.
      passwordEncoder.matches(req.password(), DUMMY_BCRYPT_HASH);
      loginAttemptService.recordFailure(email, clientIp);
      throw new InvalidCredentialsException();
    }
    PasswordCredential credential =
        passwordCredentialRepository
            .findById(user.getId())
            .orElseThrow(InvalidCredentialsException::new);
    if (!passwordEncoder.matches(req.password(), credential.getBcryptHash())) {
      loginAttemptService.recordFailure(email, clientIp);
      throw new InvalidCredentialsException();
    }
    // Success clears the account bucket; the IP bucket is intentionally retained (EC-1).
    loginAttemptService.clearAccountBucket(email);
    String accessToken = jwtIssuer.issue(user.getId());
    String refreshToken = refreshTokenService.issue(user.getId());
    // T-048: deliver the refresh token as the HttpOnly atlas_refresh cookie (out of JS reach),
    // never in the body. credentials:'include' on the client receives it.
    ResponseCookie cookie = authCookieFactory.buildRefreshCookie(refreshToken);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(new AuthResponse(accessToken, jwtIssuer.accessTtlSeconds()));
  }

  @PostMapping("/refresh")
  @Operation(
      summary = "Exchange the refresh cookie for a fresh access token + a rotated refresh cookie")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Rotated",
        content = @Content(schema = @Schema(implementation = AuthResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired refresh cookie"),
    @ApiResponse(
        responseCode = "415",
        description = "Unexpected request body (this endpoint is body-less)")
  })
  public ResponseEntity<AuthResponse> refresh(
      @CookieValue(value = AuthCookieFactory.COOKIE_NAME, required = false) String rawRefresh,
      HttpServletRequest request)
      throws HttpMediaTypeNotSupportedException {
    rejectBody(request);
    // T-048: the raw refresh token comes ONLY from the HttpOnly cookie — no body, no fallback.
    if (rawRefresh == null || rawRefresh.isBlank()) {
      throw new InvalidCredentialsException();
    }
    RefreshTokenService.RotateResult rotated = refreshTokenService.rotate(rawRefresh);
    String accessToken = jwtIssuer.issue(rotated.userId());
    ResponseCookie cookie = authCookieFactory.buildRefreshCookie(rotated.newRaw());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(new AuthResponse(accessToken, jwtIssuer.accessTtlSeconds()));
  }

  @PostMapping("/logout")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Revoke the caller's refresh token (read from the cookie) and clear the cookie")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Logged out (cookie cleared; idempotent)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Refresh token belongs to another user"),
    @ApiResponse(
        responseCode = "415",
        description = "Unexpected request body (this endpoint is body-less)")
  })
  public ResponseEntity<Void> logout(
      @CookieValue(value = AuthCookieFactory.COOKIE_NAME, required = false) String rawRefresh,
      HttpServletRequest request)
      throws HttpMediaTypeNotSupportedException {
    rejectBody(request);
    // Absent cookie → still 204 (idempotent). A present cookie is revoked; a token belonging to
    // another user still 403s via revokeByRawToken.
    if (rawRefresh != null && !rawRefresh.isBlank()) {
      refreshTokenService.revokeByRawToken(rawRefresh, currentUserId());
    }
    ResponseCookie clear = authCookieFactory.buildClearCookie();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clear.toString()).build();
  }

  @PostMapping("/logout-all")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(
      operationId = "logoutAll",
      summary = "Revoke ALL of the caller's live refresh tokens (log out everywhere)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "All live refresh tokens revoked (idempotent)"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  public ResponseEntity<Void> logoutAll() {
    refreshTokenService.logoutAll(currentUserId());
    // T-048: the other devices' tokens are revoked server-side; clear THIS browser's cookie too.
    ResponseCookie clear = authCookieFactory.buildClearCookie();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clear.toString()).build();
  }

  /**
   * The cookie-based refresh/logout endpoints take NO request body — the refresh token is read from
   * the {@code atlas_refresh} cookie. A body-less POST (no Content-Type) is the normal path; a
   * client that erroneously sends a body gets 415 (T-048, QG-3). Injecting {@link
   * HttpServletRequest} (rather than a {@code @RequestHeader}) keeps this off the OpenAPI parameter
   * list.
   */
  private static void rejectBody(HttpServletRequest request)
      throws HttpMediaTypeNotSupportedException {
    if (request.getContentType() != null) {
      throw new HttpMediaTypeNotSupportedException(
          "This endpoint takes no request body; the refresh token is read from the "
              + AuthCookieFactory.COOKIE_NAME
              + " cookie.");
    }
  }

  @GetMapping("/me")
  @SecurityRequirement(name = "bearerAuth")
  // Explicit operationId so springdoc names the generated client method getMe()
  // rather than me() (T-010).
  @Operation(operationId = "getMe", summary = "Return the authenticated user's profile")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Profile retrieved",
        content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  public ResponseEntity<UserProfileResponse> me() {
    User user =
        userRepository.findById(currentUserId()).orElseThrow(InvalidCredentialsException::new);
    return ResponseEntity.ok(
        new UserProfileResponse(
            user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt()));
  }

  /**
   * Reads the authenticated user id from the SecurityContext via the shared
   * {@link CurrentUser} helper. A missing or non-UUID principal yields 401
   * (InvalidCredentialsException) — the helper's IllegalState/IllegalArgument
   * exceptions are translated here so the auth endpoints keep their exact
   * pre-T-014 contract.
   */
  private static UUID currentUserId() {
    try {
      return CurrentUser.id();
    } catch (IllegalStateException | IllegalArgumentException e) {
      throw new InvalidCredentialsException();
    }
  }
}
