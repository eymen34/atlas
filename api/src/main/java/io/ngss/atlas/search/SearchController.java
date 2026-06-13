package io.ngss.atlas.search;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.search.dto.TicketSearchResult;
import io.ngss.atlas.security.CurrentUser;
import io.ngss.atlas.security.ProjectAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Full-text search endpoints (T-028). Both are GET (no {@code consumes}) and return a
 * {@code PagedResponse<TicketSearchResult>} ranked by relevance. Project-scoped guards
 * membership FIRST (non-member → 404); global scopes to the caller's projects entirely
 * in SQL (the service passes {@link CurrentUser#id()}). A missing {@code q} → 400
 * (MissingServletRequestParameterException → GlobalExceptionHandler); a whitespace-only
 * {@code q} → 200 with an empty list (empty tsquery).
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "search")
public class SearchController {

  private final TicketSearchService searchService;
  private final ProjectAccessGuard guard;

  public SearchController(TicketSearchService searchService, ProjectAccessGuard guard) {
    this.searchService = searchService;
    this.guard = guard;
  }

  @GetMapping("/api/projects/{projectId}/tickets/search")
  @Operation(
      operationId = "searchProjectTickets",
      summary = "Full-text search a project's tickets (any member)",
      description =
          "Ranked by ts_rank_cd then updated_at (both DESC), english stemming, soft-deleted "
              + "excluded. size clamped 1..100. Non-member → 404 (existence-leak prevention).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Search results (paged envelope)"),
    @ApiResponse(responseCode = "400", description = "Missing q parameter"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
  })
  public PagedResponse<TicketSearchResult> searchProjectTickets(
      @PathVariable UUID projectId,
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    guard.requireMember(projectId); // FIRST — non-member never reaches the repo
    return searchService.searchProject(projectId, q, page, size);
  }

  @GetMapping("/api/search/tickets")
  @Operation(
      operationId = "searchAllTickets",
      summary = "Full-text search across all of the caller's projects",
      description =
          "Scoped to the caller's project memberships ENTIRELY in SQL (a project_members "
              + "subquery) — never a Java post-filter. Same ranking + clamping as the "
              + "project-scoped endpoint.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Search results (paged envelope)"),
    @ApiResponse(responseCode = "400", description = "Missing q parameter"),
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
  })
  public PagedResponse<TicketSearchResult> searchAllTickets(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return searchService.searchGlobal(CurrentUser.id(), q, page, size);
  }
}
