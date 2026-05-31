package io.ngss.atlas.auth;

import io.ngss.atlas.auth.dto.AuthResponse;
import io.ngss.atlas.auth.dto.LoginRequest;
import io.ngss.atlas.auth.dto.NotImplementedResponse;
import io.ngss.atlas.auth.dto.RefreshRequest;
import io.ngss.atlas.auth.dto.RegisterRequest;
import io.ngss.atlas.auth.dto.UserProfileResponse;
import io.ngss.atlas.auth.dto.UserRegisteredResponse;
import io.ngss.atlas.domain.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-009 scaffold. Every endpoint returns HTTP 501 with a stable JSON body.
 * The real DTOs are declared on the request/response signatures so the
 * OpenAPI document is final from day one — T-010 generates the frontend
 * TS client against this contract; T-011/T-012 implement the bodies.
 */
@RestController
// Class-level produces locks the OpenAPI response content-type to
// application/json (without it, springdoc emits "*/*" which T-010's TS
// client codegen reads as an opaque media type). Per-method consumes on
// the three POSTs with @RequestBody mirrors the same contract on the
// request side.
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth")
public class AuthController {

  private static final NotImplementedResponse STUB =
      new NotImplementedResponse("NOT_IMPLEMENTED", "Endpoint not yet implemented (T-011/T-012)");

  private final RegistrationService registrationService;

  public AuthController(RegistrationService registrationService) {
    this.registrationService = registrationService;
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
  @Operation(summary = "Authenticate with email + password and receive token pair")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful login (T-011)",
        content = @Content(schema = @Schema(implementation = AuthResponse.class))),
    @ApiResponse(
        responseCode = "501",
        description = "Stub — not yet implemented",
        content = @Content(schema = @Schema(implementation = NotImplementedResponse.class)))
  })
  public ResponseEntity<NotImplementedResponse> login(@Valid @RequestBody LoginRequest req) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(STUB);
  }

  @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Exchange a refresh token for a fresh access + refresh pair")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful refresh (T-012)",
        content = @Content(schema = @Schema(implementation = AuthResponse.class))),
    @ApiResponse(
        responseCode = "501",
        description = "Stub — not yet implemented",
        content = @Content(schema = @Schema(implementation = NotImplementedResponse.class)))
  })
  public ResponseEntity<NotImplementedResponse> refresh(@Valid @RequestBody RefreshRequest req) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(STUB);
  }

  @PostMapping("/logout")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Revoke the caller's refresh token")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Successful logout (T-012)"),
    @ApiResponse(
        responseCode = "501",
        description = "Stub — not yet implemented",
        content = @Content(schema = @Schema(implementation = NotImplementedResponse.class)))
  })
  public ResponseEntity<NotImplementedResponse> logout() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(STUB);
  }

  @GetMapping("/me")
  @SecurityRequirement(name = "bearerAuth")
  // Explicit operationId: springdoc would otherwise derive it from the Java
  // method name ("me"), which generates an awkward AuthService.me() client
  // method. "getMe" gives the T-010 TS client a clear AuthService.getMe().
  @Operation(operationId = "getMe", summary = "Return the authenticated user's profile")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Profile retrieved (T-011)",
        content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
    @ApiResponse(
        responseCode = "501",
        description = "Stub — not yet implemented",
        content = @Content(schema = @Schema(implementation = NotImplementedResponse.class)))
  })
  public ResponseEntity<NotImplementedResponse> me() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(STUB);
  }
}
