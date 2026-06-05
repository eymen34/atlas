package io.ngss.atlas.project;

import io.ngss.atlas.project.dto.AddMemberRequest;
import io.ngss.atlas.project.dto.MemberResponse;
import io.ngss.atlas.project.dto.UpdateMemberRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project membership endpoints (T-015). All routes are authenticated by the
 * existing {@code /api/**} rule; authorization (member vs admin) is enforced in
 * {@link ProjectMemberService} via {@code ProjectAccessGuard}. Non-members get
 * 404 (existence-leak prevention); members lacking ADMIN get 403 on mutations.
 */
@RestController
@RequestMapping(value = "/api/projects/{id}/members", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "project-members")
public class ProjectMemberController {

  private final ProjectMemberService memberService;

  public ProjectMemberController(ProjectMemberService memberService) {
    this.memberService = memberService;
  }

  @GetMapping
  @Operation(summary = "List the members of a project (any member may view)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Members listed",
        content =
            @Content(array = @ArraySchema(schema = @Schema(implementation = MemberResponse.class)))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public ResponseEntity<List<MemberResponse>> list(@PathVariable UUID id) {
    return ResponseEntity.ok(memberService.listMembers(id));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Add a registered user to the project (ADMIN only)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Member added",
        content = @Content(schema = @Schema(implementation = MemberResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is a member but not an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Project/caller-membership/email not found"),
    @ApiResponse(responseCode = "409", description = "User is already a member")
  })
  public ResponseEntity<MemberResponse> add(
      @PathVariable UUID id, @Valid @RequestBody AddMemberRequest req) {
    MemberResponse added = memberService.addMember(id, req);
    return ResponseEntity.created(URI.create("/api/projects/" + id + "/members/" + added.userId()))
        .body(added);
  }

  @PatchMapping(value = "/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Change a member's role (ADMIN only)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Role updated",
        content = @Content(schema = @Schema(implementation = MemberResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error or last-admin demotion"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is a member but not an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Project not found or target is not a member")
  })
  public ResponseEntity<MemberResponse> changeRole(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @Valid @RequestBody UpdateMemberRoleRequest req) {
    return ResponseEntity.ok(memberService.changeRole(id, userId, req));
  }

  @DeleteMapping("/{userId}")
  @Operation(summary = "Remove a member from the project (ADMIN only)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Member removed"),
    @ApiResponse(responseCode = "400", description = "Last-admin removal blocked"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is a member but not an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Project not found or target is not a member")
  })
  public ResponseEntity<Void> remove(@PathVariable UUID id, @PathVariable UUID userId) {
    memberService.removeMember(id, userId);
    return ResponseEntity.noContent().build();
  }
}
