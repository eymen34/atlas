package io.ngss.atlas.project;

import io.ngss.atlas.project.dto.CreateProjectRequest;
import io.ngss.atlas.project.dto.ProjectResponse;
import io.ngss.atlas.project.dto.UpdateProjectRequest;
import io.ngss.atlas.security.CurrentUser;
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
 * Project CRUD endpoints (T-014). Every route is authenticated by the existing
 * {@code /api/**} security rule; authorization is creator-only and enforced in
 * {@link ProjectService}. Non-creator access to an existing project returns 404
 * (not 403) to avoid existence leakage.
 *
 * <p>Class-level {@code produces=application/json} keeps springdoc content types
 * concrete; body routes (POST/PATCH) add {@code consumes=application/json}.
 */
@RestController
@RequestMapping(value = "/api/projects", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "projects")
public class ProjectController {

  private final ProjectService projectService;

  public ProjectController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Create a new project owned by the authenticated caller")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Project created",
        content = @Content(schema = @Schema(implementation = ProjectResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "409", description = "Project key already in use")
  })
  public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest req) {
    // T-015: the creator becomes the first project_members admin row.
    ProjectResponse created = projectService.create(req, CurrentUser.id());
    return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
  }

  @GetMapping
  @Operation(summary = "List the authenticated caller's live projects")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Projects listed",
        content =
            @Content(array = @ArraySchema(schema = @Schema(implementation = ProjectResponse.class)))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  public ResponseEntity<List<ProjectResponse>> list() {
    return ResponseEntity.ok(projectService.listForCaller(CurrentUser.id()));
  }

  @GetMapping("/{idOrKey}")
  @Operation(
      summary = "Fetch a single project by UUID id or key",
      description =
          "Resolves the path segment as a UUID first; on failure it is treated as a project key. "
              + "Returns 404 if no live project the caller owns matches.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Project found",
        content = @Content(schema = @Schema(implementation = ProjectResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found")
  })
  public ResponseEntity<ProjectResponse> get(@PathVariable String idOrKey) {
    return ResponseEntity.ok(projectService.getByIdOrKeyForCaller(idOrKey, CurrentUser.id()));
  }

  @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Partially update a project the caller created",
      description =
          "Null/absent fields are left unchanged; an explicit empty-string description clears it. "
              + "A present but blank name is a 400. updatedAt always advances; createdAt is never "
              + "changed.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Project updated",
        content = @Content(schema = @Schema(implementation = ProjectResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found")
  })
  public ResponseEntity<ProjectResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest req) {
    return ResponseEntity.ok(projectService.update(id, req, CurrentUser.id()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete a project the caller created")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Project soft-deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found")
  })
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    projectService.softDelete(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
