package io.ngss.atlas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectMember;
import io.ngss.atlas.domain.ProjectMemberRepository;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.domain.ProjectRole;
import io.ngss.atlas.project.dto.CreateProjectRequest;
import io.ngss.atlas.project.dto.ProjectResponse;
import io.ngss.atlas.project.dto.UpdateProjectRequest;
import io.ngss.atlas.security.ProjectAccessGuard;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Unit tests for {@link ProjectService} — timestamps, member seeding, and guard delegation. */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

  @Mock ProjectRepository repository;
  @Mock ProjectMemberRepository memberRepository;
  @Mock ProjectAccessGuard guard;
  @InjectMocks ProjectService service;

  private static final UUID CALLER = UUID.randomUUID();

  // ───────────────────────── create ─────────────────────────

  @Test
  void create_stampsCreatedAtEqualToUpdatedAt_andSeedsAdminMember() {
    when(repository.existsByKeyAndDeletedAtIsNull("ALPHA")).thenReturn(false);

    Instant before = Instant.now();
    ProjectResponse resp =
        service.create(new CreateProjectRequest("ALPHA", "Alpha", "desc"), CALLER);
    Instant after = Instant.now();

    assertThat(resp.id()).isNotNull();
    assertThat(resp.createdBy()).isEqualTo(CALLER);
    assertThat(resp.createdAt()).isEqualTo(resp.updatedAt());
    assertThat(resp.createdAt()).isBetween(before, after);

    ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
    verify(memberRepository).save(captor.capture());
    ProjectMember seeded = captor.getValue();
    assertThat(seeded.getProjectId()).isEqualTo(resp.id());
    assertThat(seeded.getUserId()).isEqualTo(CALLER);
    assertThat(seeded.getRole()).isEqualTo(ProjectRole.ADMIN);
    assertThat(seeded.getInvitedBy()).isNull();
  }

  @Test
  void create_duplicateKeyPreCheck_throwsDuplicate_andSeedsNothing() {
    when(repository.existsByKeyAndDeletedAtIsNull("DUP")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CreateProjectRequest("DUP", "x", null), CALLER))
        .isInstanceOf(DuplicateProjectKeyException.class);
    verify(repository, never()).save(any());
    verify(memberRepository, never()).save(any());
  }

  @Test
  void create_raceOnSave_translatesDataIntegrityViolationToDuplicate() {
    when(repository.existsByKeyAndDeletedAtIsNull("RACE")).thenReturn(false);
    when(repository.save(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

    assertThatThrownBy(() -> service.create(new CreateProjectRequest("RACE", "x", null), CALLER))
        .isInstanceOf(DuplicateProjectKeyException.class);
    verify(memberRepository, never()).save(any());
  }

  // ───────────────────────── update (guard.requireAdmin) ─────────────────────────

  @Test
  void update_advancesUpdatedAt_leavesCreatedAtUnchanged() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    Instant before = Instant.now();
    ProjectResponse resp = service.update(p.getId(), new UpdateProjectRequest("NewName", null));
    Instant after = Instant.now();

    assertThat(resp.name()).isEqualTo("NewName");
    assertThat(resp.createdAt()).isEqualTo(t0);
    assertThat(resp.updatedAt()).isAfter(t0).isBetween(before, after);
    verify(guard).requireAdmin(p.getId());
  }

  @Test
  void update_nullName_keepsExistingName_butStillAdvancesUpdatedAt() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Original", "Desc", CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    ProjectResponse resp = service.update(p.getId(), new UpdateProjectRequest(null, null));

    assertThat(resp.name()).isEqualTo("Original");
    assertThat(resp.description()).isEqualTo("Desc");
    assertThat(resp.updatedAt()).isAfter(t0);
  }

  @Test
  void update_blankName_throwsValidation_afterAuthPasses() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    assertThatThrownBy(() -> service.update(p.getId(), new UpdateProjectRequest("   ", null)))
        .isInstanceOf(ProjectValidationException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void update_nonAdmin_propagatesForbidden() {
    Instant t0 = Instant.now();
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    doThrow(new ForbiddenProjectAccessException(p.getId())).when(guard).requireAdmin(p.getId());

    assertThatThrownBy(() -> service.update(p.getId(), new UpdateProjectRequest("x", null)))
        .isInstanceOf(ForbiddenProjectAccessException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void update_nonMember_propagatesNotFound() {
    Instant t0 = Instant.now();
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    doThrow(new ProjectNotFoundException(p.getId())).when(guard).requireAdmin(p.getId());

    assertThatThrownBy(() -> service.update(p.getId(), new UpdateProjectRequest("x", null)))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void update_missing_throwsNotFound_beforeGuard() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(id, new UpdateProjectRequest("x", null)))
        .isInstanceOf(ProjectNotFoundException.class);
    verify(guard, never()).requireAdmin(any());
  }

  // ───────────────────────── softDelete ─────────────────────────

  @Test
  void softDelete_stampsDeletedAtAndUpdatedAt() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    service.softDelete(p.getId());

    assertThat(p.getDeletedAt()).isNotNull().isAfter(t0);
    assertThat(p.getUpdatedAt()).isEqualTo(p.getDeletedAt());
    verify(guard).requireAdmin(p.getId());
  }

  @Test
  void softDelete_nonAdmin_propagatesForbidden() {
    Instant t0 = Instant.now();
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    doThrow(new ForbiddenProjectAccessException(p.getId())).when(guard).requireAdmin(p.getId());

    assertThatThrownBy(() -> service.softDelete(p.getId()))
        .isInstanceOf(ForbiddenProjectAccessException.class);
    verify(repository, never()).save(any());
  }

  // ───────────────────────── getByIdOrKey (guard.requireMember) ─────────────────────────

  @Test
  void getByIdOrKey_uuidString_routesToIdFinder() {
    UUID id = UUID.randomUUID();
    Project p = new Project(id, "KEY", "Name", null, CALLER, Instant.now(), Instant.now(), null);
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(p));

    ProjectResponse resp = service.getByIdOrKeyForCaller(id.toString());

    assertThat(resp.id()).isEqualTo(id);
    verify(guard).requireMember(id);
    verify(repository, never()).findByKeyAndDeletedAtIsNull(any());
  }

  @Test
  void getByIdOrKey_nonUuidString_routesToKeyFinder() {
    UUID id = UUID.randomUUID();
    Project p = new Project(id, "MYKEY", "Name", null, CALLER, Instant.now(), Instant.now(), null);
    when(repository.findByKeyAndDeletedAtIsNull("MYKEY")).thenReturn(Optional.of(p));

    ProjectResponse resp = service.getByIdOrKeyForCaller("MYKEY");

    assertThat(resp.key()).isEqualTo("MYKEY");
    verify(guard).requireMember(id);
    verify(repository, never()).findByIdAndDeletedAtIsNull(any());
  }

  @Test
  void getByIdOrKey_nonMember_propagatesNotFound() {
    UUID id = UUID.randomUUID();
    Project p = new Project(id, "KEY", "Name", null, CALLER, Instant.now(), Instant.now(), null);
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(p));
    doThrow(new ProjectNotFoundException(id)).when(guard).requireMember(eq(id));

    assertThatThrownBy(() -> service.getByIdOrKeyForCaller(id.toString()))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void getByIdOrKey_missing_throwsNotFound_beforeGuard() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getByIdOrKeyForCaller(id.toString()))
        .isInstanceOf(ProjectNotFoundException.class);
    verify(guard, never()).requireMember(any());
  }
}
