package io.ngss.atlas.user;

import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.project.UserNotFoundException;
import io.ngss.atlas.user.dto.UserSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only user lookup (T-044). A single endpoint that resolves a user id to a
 * display-only {@link UserSummaryResponse}, used by the frontend actor lookup to
 * render the name of an author who has LEFT a project: their {@code project_members}
 * row was removed (ProjectMemberService.removeMember), but the {@code users} row
 * persists (there is no user hard-delete). So this resolves the displayName in
 * virtually every case; a genuinely-missing id is the rare 404.
 *
 * <p>Authz: authenticated but deliberately NOT project-scoped — the {@code /api/**}
 * catch-all in SecurityConfig already requires a valid Bearer token. Resolving a
 * display name for an id the caller already sees in activity/comments leaks nothing
 * new, and the actor has by definition left the project, so a current-membership
 * guard would defeat the feature. The body is display-only ({id, displayName}); it
 * never carries email, role, or any credential/PII field.
 */
@RestController
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "users")
public class UserController {

  private final UserRepository userRepository;

  public UserController(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @GetMapping("/{id}")
  @Operation(
      operationId = "getUserSummary",
      summary = "Resolve a user id to a display-only summary (id + displayName)",
      description =
          "Renders the name of an author who has left a project. Display-only: no "
              + "email/role/PII. Authenticated; resolves regardless of shared project membership.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "User summary",
        content = @Content(schema = @Schema(implementation = UserSummaryResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "No user with that id")
  })
  public UserSummaryResponse getUserSummary(@PathVariable UUID id) {
    // Resolve the user row regardless of project membership (F3). No soft-delete on
    // users, so an empty Optional means the id is genuinely unknown → uniform 404.
    return userRepository
        .findById(id)
        .map(u -> new UserSummaryResponse(u.getId(), u.getDisplayName()))
        .orElseThrow(() -> new UserNotFoundException("User not found"));
  }
}
