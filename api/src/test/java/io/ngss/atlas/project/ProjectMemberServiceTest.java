package io.ngss.atlas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.domain.User;
import io.ngss.atlas.domain.UserRepository;
import io.ngss.atlas.project.dto.AddMemberRequest;
import io.ngss.atlas.project.dto.MemberResponse;
import io.ngss.atlas.project.dto.UpdateMemberRoleRequest;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link ProjectMemberService} — last-admin lock, dup race, guard invalidation. */
@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

  @Mock ProjectMemberRepository memberRepository;
  @Mock UserRepository userRepository;
  @Mock ProjectRepository projectRepository;
  @Mock ProjectAccessGuard guard;
  @InjectMocks ProjectMemberService service;

  private static final UUID PROJECT = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                CALLER.toString(), null, Collections.emptyList()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void projectIsLive() {
    when(projectRepository.findByIdAndDeletedAtIsNull(PROJECT))
        .thenReturn(
            Optional.of(
                new Project(
                    PROJECT, "KEY", "Name", null, CALLER, Instant.now(), Instant.now(), null)));
  }

  private User user(UUID id, String email) {
    return new User(id, email, "Display", Instant.now(), Instant.now());
  }

  private ProjectMember member(UUID userId, ProjectRole role) {
    return new ProjectMember(UUID.randomUUID(), PROJECT, userId, role, null, Instant.now());
  }

  // ───────────────────────── addMember ─────────────────────────

  @Test
  void addMember_success_invalidatesGuard() {
    projectIsLive();
    when(userRepository.findByEmailIgnoreCase("new@example.com"))
        .thenReturn(Optional.of(user(TARGET, "new@example.com")));
    when(memberRepository.existsByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(false);

    MemberResponse resp =
        service.addMember(PROJECT, new AddMemberRequest("new@example.com", ProjectRole.MEMBER));

    assertThat(resp.userId()).isEqualTo(TARGET);
    assertThat(resp.role()).isEqualTo(ProjectRole.MEMBER);
    verify(memberRepository).save(any(ProjectMember.class));
    verify(guard).invalidate(PROJECT);
  }

  @Test
  void addMember_unknownEmail_throwsUserNotFound() {
    projectIsLive();
    when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.addMember(PROJECT, new AddMemberRequest("ghost@example.com", ProjectRole.MEMBER)))
        .isInstanceOf(UserNotFoundException.class);
    verify(memberRepository, never()).save(any());
  }

  @Test
  void addMember_alreadyMember_throwsDuplicate() {
    projectIsLive();
    when(userRepository.findByEmailIgnoreCase("dup@example.com"))
        .thenReturn(Optional.of(user(TARGET, "dup@example.com")));
    when(memberRepository.existsByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(true);

    assertThatThrownBy(
            () -> service.addMember(PROJECT, new AddMemberRequest("dup@example.com", ProjectRole.MEMBER)))
        .isInstanceOf(DuplicateMemberException.class);
    verify(memberRepository, never()).save(any());
  }

  @Test
  void addMember_uniqueRace_translatesToDuplicate() {
    projectIsLive();
    when(userRepository.findByEmailIgnoreCase("race@example.com"))
        .thenReturn(Optional.of(user(TARGET, "race@example.com")));
    when(memberRepository.existsByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(false);
    when(memberRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("unique violation"));

    assertThatThrownBy(
            () -> service.addMember(PROJECT, new AddMemberRequest("race@example.com", ProjectRole.MEMBER)))
        .isInstanceOf(DuplicateMemberException.class);
  }

  @Test
  void addMember_softDeletedProject_throwsNotFound() {
    when(projectRepository.findByIdAndDeletedAtIsNull(PROJECT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.addMember(PROJECT, new AddMemberRequest("x@example.com", ProjectRole.MEMBER)))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  // ───────────────────────── changeRole ─────────────────────────

  @Test
  void changeRole_demoteLastAdmin_locksThenThrows() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET))
        .thenReturn(Optional.of(member(TARGET, ProjectRole.ADMIN)));
    when(memberRepository.lockAdminsForProject(PROJECT))
        .thenReturn(List.of(member(TARGET, ProjectRole.ADMIN)));

    assertThatThrownBy(
            () -> service.changeRole(PROJECT, TARGET, new UpdateMemberRoleRequest(ProjectRole.MEMBER)))
        .isInstanceOf(LastAdminException.class);
    verify(memberRepository).lockAdminsForProject(PROJECT);
    verify(memberRepository, never()).save(any());
    verify(guard, never()).invalidate(any());
  }

  @Test
  void changeRole_demoteWithSecondAdmin_succeeds_andInvalidates() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET))
        .thenReturn(Optional.of(member(TARGET, ProjectRole.ADMIN)));
    when(memberRepository.lockAdminsForProject(PROJECT))
        .thenReturn(List.of(member(TARGET, ProjectRole.ADMIN), member(CALLER, ProjectRole.ADMIN)));
    when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, "t@example.com")));

    MemberResponse resp =
        service.changeRole(PROJECT, TARGET, new UpdateMemberRoleRequest(ProjectRole.MEMBER));

    assertThat(resp.role()).isEqualTo(ProjectRole.MEMBER);
    verify(memberRepository).save(any(ProjectMember.class));
    verify(guard).invalidate(PROJECT);
  }

  @Test
  void changeRole_promoteMemberToAdmin_doesNotConsultLastAdminLock() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET))
        .thenReturn(Optional.of(member(TARGET, ProjectRole.MEMBER)));
    when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, "t@example.com")));

    MemberResponse resp =
        service.changeRole(PROJECT, TARGET, new UpdateMemberRoleRequest(ProjectRole.ADMIN));

    assertThat(resp.role()).isEqualTo(ProjectRole.ADMIN);
    verify(memberRepository, never()).lockAdminsForProject(any());
    verify(guard).invalidate(PROJECT);
  }

  @Test
  void changeRole_targetNotMember_throwsMemberNotFound() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.changeRole(PROJECT, TARGET, new UpdateMemberRoleRequest(ProjectRole.ADMIN)))
        .isInstanceOf(MemberNotFoundException.class);
  }

  // ───────────────────────── removeMember ─────────────────────────

  @Test
  void removeMember_lastAdmin_locksThenThrows() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET))
        .thenReturn(Optional.of(member(TARGET, ProjectRole.ADMIN)));
    when(memberRepository.lockAdminsForProject(PROJECT))
        .thenReturn(List.of(member(TARGET, ProjectRole.ADMIN)));

    assertThatThrownBy(() -> service.removeMember(PROJECT, TARGET))
        .isInstanceOf(LastAdminException.class);
    verify(memberRepository).lockAdminsForProject(PROJECT);
    verify(memberRepository, never()).delete(any());
  }

  @Test
  void removeMember_nonAdmin_deletes_andInvalidates() {
    projectIsLive();
    ProjectMember target = member(TARGET, ProjectRole.MEMBER);
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(Optional.of(target));

    service.removeMember(PROJECT, TARGET);

    verify(memberRepository, never()).lockAdminsForProject(any());
    verify(memberRepository).delete(target);
    verify(guard).invalidate(PROJECT);
  }

  @Test
  void removeMember_targetNotMember_throwsMemberNotFound() {
    projectIsLive();
    when(memberRepository.findByProjectIdAndUserId(PROJECT, TARGET)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.removeMember(PROJECT, TARGET))
        .isInstanceOf(MemberNotFoundException.class);
  }

  // ───────────────────────── listMembers ─────────────────────────

  @Test
  void listMembers_requiresMember_thenProjects() {
    projectIsLive();
    MemberResponse row =
        new MemberResponse(CALLER, "c@example.com", "C", ProjectRole.ADMIN, null, Instant.now());
    when(memberRepository.findMemberResponsesByProjectId(PROJECT)).thenReturn(List.of(row));

    List<MemberResponse> result = service.listMembers(PROJECT);

    assertThat(result).containsExactly(row);
    verify(guard).requireMember(PROJECT);
  }
}
