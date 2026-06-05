package io.ngss.atlas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.project.ForbiddenProjectAccessException;
import io.ngss.atlas.project.ProjectNotFoundException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link ProjectAccessGuard} — caching + 404/403 split. */
@ExtendWith(MockitoExtension.class)
class ProjectAccessGuardTest {

  @Mock ProjectMemberRepository memberRepository;
  ProjectAccessGuard guard;

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID PROJECT = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                CALLER.toString(), null, Collections.emptyList()));
    guard = new ProjectAccessGuard(memberRepository);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private ProjectMember member(ProjectRole role) {
    return new ProjectMember(UUID.randomUUID(), PROJECT, CALLER, role, null, Instant.now());
  }

  @Test
  void lookup_isMemoizedPerProject() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER))
        .thenReturn(Optional.of(member(ProjectRole.MEMBER)));

    guard.isMember(PROJECT);
    guard.isMember(PROJECT);
    guard.isAdmin(PROJECT);

    verify(memberRepository, times(1)).findByProjectIdAndUserId(PROJECT, CALLER);
  }

  @Test
  void requireMember_nonMember_throwsNotFound() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> guard.requireMember(PROJECT))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void requireAdmin_memberButNotAdmin_throwsForbidden() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER))
        .thenReturn(Optional.of(member(ProjectRole.MEMBER)));

    assertThatThrownBy(() -> guard.requireAdmin(PROJECT))
        .isInstanceOf(ForbiddenProjectAccessException.class);
  }

  @Test
  void requireAdmin_stranger_throwsNotFound_notForbidden() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> guard.requireAdmin(PROJECT))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void requireAdmin_admin_passes() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER))
        .thenReturn(Optional.of(member(ProjectRole.ADMIN)));

    guard.requireAdmin(PROJECT);

    assertThat(guard.isAdmin(PROJECT)).isTrue();
  }

  @Test
  void invalidate_forcesReLookup() {
    when(memberRepository.findByProjectIdAndUserId(PROJECT, CALLER))
        .thenReturn(Optional.of(member(ProjectRole.MEMBER)));

    guard.isMember(PROJECT);
    guard.invalidate(PROJECT);
    guard.isMember(PROJECT);

    verify(memberRepository, times(2)).findByProjectIdAndUserId(PROJECT, CALLER);
  }
}
