package io.ngss.atlas.label;

import io.ngss.atlas.label.dto.CreateLabelRequest;
import io.ngss.atlas.label.dto.LabelResponse;
import io.ngss.atlas.label.dto.UpdateLabelRequest;
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
 * Label endpoints (T-018). Project-scoped list/create live at
 * {@code /api/projects/{id}/labels}; label-scoped update/delete at
 * {@code /api/labels/{id}}. Authorization is enforced in {@link LabelService} via
 * ProjectAccessGuard — non-member → 404 (existence-leak prevention),
 * member-non-admin → 403 on DELETE.
 *
 * <p>No class-level base path (the two resource roots differ); each method declares
 * its absolute path. Class-level {@code produces=application/json} keeps springdoc
 * content types concrete; body routes add {@code consumes=application/json}.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "labels")
public class LabelController {

  private final LabelService labelService;

  public LabelController(LabelService labelService) {
    this.labelService = labelService;
  }

  @GetMapping("/api/projects/{id}/labels")
  @Operation(operationId = "listProjectLabels", summary = "List a project's labels (any member)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Labels listed",
        content =
            @Content(array = @ArraySchema(schema = @Schema(implementation = LabelResponse.class)))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public ResponseEntity<List<LabelResponse>> listProjectLabels(@PathVariable UUID id) {
    return ResponseEntity.ok(labelService.list(id));
  }

  @PostMapping(value = "/api/projects/{id}/labels", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "createProjectLabel", summary = "Create a label in a project (any member)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Label created",
        content = @Content(schema = @Schema(implementation = LabelResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member"),
    @ApiResponse(responseCode = "409", description = "Label name already in use in this project")
  })
  public ResponseEntity<LabelResponse> createProjectLabel(
      @PathVariable UUID id, @Valid @RequestBody CreateLabelRequest req) {
    LabelResponse created = labelService.create(id, req);
    return ResponseEntity.created(URI.create("/api/labels/" + created.id())).body(created);
  }

  @PatchMapping(value = "/api/labels/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "updateLabel",
      summary = "Update a label's name and/or color (any member)",
      description = "Null/absent fields are left unchanged; supplying neither name nor color is a 400.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Label updated",
        content = @Content(schema = @Schema(implementation = LabelResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Label not found or caller is not a member"),
    @ApiResponse(responseCode = "409", description = "Label name already in use in this project")
  })
  public ResponseEntity<LabelResponse> updateLabel(
      @PathVariable UUID id, @Valid @RequestBody UpdateLabelRequest req) {
    return ResponseEntity.ok(labelService.update(id, req));
  }

  @DeleteMapping("/api/labels/{id}")
  @Operation(
      operationId = "deleteLabel",
      summary = "Delete a label and detach it from all tickets (ADMIN only)")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Label deleted"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "403", description = "Caller is a member but not an ADMIN"),
    @ApiResponse(responseCode = "404", description = "Label not found or caller is not a member")
  })
  public ResponseEntity<Void> deleteLabel(@PathVariable UUID id) {
    labelService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
