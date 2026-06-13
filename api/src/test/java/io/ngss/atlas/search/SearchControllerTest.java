package io.ngss.atlas.search;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.common.PagedResponse;
import io.ngss.atlas.search.dto.TicketSearchResult;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link SearchController} (Docker-free, plain Mockito — webmvctest_unavailable).
 *
 * <p>SEC-3: the project-scoped endpoint MUST call {@code requireMember} BEFORE touching the
 * search service, so a non-member never reaches the repo. The {@link InOrder} assertion locks
 * that ordering. The global endpoint resolves the caller via {@code CurrentUser.id()} (reads the
 * SecurityContext) — never an inline read — so the SQL scope is keyed to the authenticated user.
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

  @Mock TicketSearchService searchService;
  @Mock ProjectAccessGuard guard;
  @InjectMocks SearchController controller;

  private static final PagedResponse<TicketSearchResult> EMPTY =
      new PagedResponse<>(List.of(), 0, 25, 0);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void projectSearch_guardsMembershipBeforeQuerying() {
    UUID projectId = UUID.randomUUID();
    when(searchService.searchProject(eq(projectId), eq("login"), eq(0), eq(25))).thenReturn(EMPTY);

    controller.searchProjectTickets(projectId, "login", 0, 25);

    InOrder order = inOrder(guard, searchService);
    order.verify(guard).requireMember(projectId); // FIRST
    order.verify(searchService).searchProject(projectId, "login", 0, 25);
  }

  @Test
  void globalSearch_usesCurrentUserIdFromSecurityContext() {
    UUID caller = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(caller.toString(), null, List.of()));
    when(searchService.searchGlobal(eq(caller), eq("login"), eq(0), eq(25))).thenReturn(EMPTY);

    controller.searchAllTickets("login", 0, 25);

    verify(searchService).searchGlobal(caller, "login", 0, 25); // scoped to the authenticated id
  }
}
