package io.ngss.atlas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ngss.atlas.domain.Project;
import io.ngss.atlas.domain.ProjectRepository;
import io.ngss.atlas.project.dto.CreateProjectRequest;
import io.ngss.atlas.project.dto.ProjectResponse;
import io.ngss.atlas.project.dto.UpdateProjectRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Unit tests for {@link ProjectService} — timestamp invariants and branch behaviour, no DB. */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

  @Mock ProjectRepository repository;
  @InjectMocks ProjectService service;

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  @Test
  void create_stampsCreatedAtEqualToUpdatedAt() {
    when(repository.existsByKeyAndDeletedAtIsNull("ALPHA")).thenReturn(false);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Instant before = Instant.now();
    ProjectResponse resp =
        service.create(new CreateProjectRequest("ALPHA", "Alpha", "desc"), CALLER);
    Instant after = Instant.now();

    assertThat(resp.id()).isNotNull();
    assertThat(resp.createdBy()).isEqualTo(CALLER);
    assertThat(resp.createdAt()).isEqualTo(resp.updatedAt());
    assertThat(resp.createdAt()).isBetween(before, after);
  }

  @Test
  void create_duplicateKeyPreCheck_throwsDuplicate() {
    when(repository.existsByKeyAndDeletedAtIsNull("DUP")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CreateProjectRequest("DUP", "x", null), CALLER))
        .isInstanceOf(DuplicateProjectKeyException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void create_raceOnSave_translatesDataIntegrityViolationToDuplicate() {
    when(repository.existsByKeyAndDeletedAtIsNull("RACE")).thenReturn(false);
    when(repository.save(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

    assertThatThrownBy(() -> service.create(new CreateProjectRequest("RACE", "x", null), CALLER))
        .isInstanceOf(DuplicateProjectKeyException.class);
  }

  @Test
  void update_advancesUpdatedAt_leavesCreatedAtUnchanged() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Instant before = Instant.now();
    ProjectResponse resp = service.update(p.getId(), new UpdateProjectRequest("NewName", null), CALLER);
    Instant after = Instant.now();

    assertThat(resp.name()).isEqualTo("NewName");
    assertThat(resp.createdAt()).isEqualTo(t0);
    assertThat(resp.updatedAt()).isAfter(t0).isBetween(before, after);
  }

  @Test
  void update_nullName_keepsExistingName_butStillAdvancesUpdatedAt() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Original", "Desc", CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ProjectResponse resp = service.update(p.getId(), new UpdateProjectRequest(null, null), CALLER);

    assertThat(resp.name()).isEqualTo("Original");
    assertThat(resp.description()).isEqualTo("Desc");
    assertThat(resp.updatedAt()).isAfter(t0);
  }

  @Test
  void update_blankName_throwsValidation() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    assertThatThrownBy(
            () -> service.update(p.getId(), new UpdateProjectRequest("   ", null), CALLER))
        .isInstanceOf(ProjectValidationException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void update_byNonCreator_throwsNotFound() {
    Instant t0 = Instant.now();
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, OTHER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    assertThatThrownBy(() -> service.update(p.getId(), new UpdateProjectRequest("x", null), CALLER))
        .isInstanceOf(ProjectNotFoundException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void update_missing_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(id, new UpdateProjectRequest("x", null), CALLER))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void softDelete_stampsDeletedAtAndUpdatedAt() {
    Instant t0 = Instant.now().minusSeconds(60);
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, CALLER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.softDelete(p.getId(), CALLER);

    assertThat(p.getDeletedAt()).isNotNull().isAfter(t0);
    assertThat(p.getUpdatedAt()).isEqualTo(p.getDeletedAt());
  }

  @Test
  void softDelete_byNonCreator_throwsNotFound() {
    Instant t0 = Instant.now();
    Project p = new Project(UUID.randomUUID(), "KEY", "Name", null, OTHER, t0, t0, null);
    when(repository.findByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));

    assertThatThrownBy(() -> service.softDelete(p.getId(), CALLER))
        .isInstanceOf(ProjectNotFoundException.class);
  }

  @Test
  void getByIdOrKey_uuidString_routesToIdFinder() {
    UUID id = UUID.randomUUID();
    Project p = new Project(id, "KEY", "Name", null, CALLER, Instant.now(), Instant.now(), null);
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(p));

    ProjectResponse resp = service.getByIdOrKeyForCaller(id.toString(), CALLER);

    assertThat(resp.id()).isEqualTo(id);
    verify(repository, never()).findByKeyAndDeletedAtIsNull(any());
  }

  @Test
  void getByIdOrKey_nonUuidString_routesToKeyFinder() {
    Project p =
        new Project(UUID.randomUUID(), "MYKEY", "Name", null, CALLER, Instant.now(), Instant.now(), null);
    when(repository.findByKeyAndDeletedAtIsNull("MYKEY")).thenReturn(Optional.of(p));

    ProjectResponse resp = service.getByIdOrKeyForCaller("MYKEY", CALLER);

    assertThat(resp.key()).isEqualTo("MYKEY");
    verify(repository, never()).findByIdAndDeletedAtIsNull(any());
  }

  @Test
  void getByIdOrKey_nonCreator_throwsNotFound() {
    UUID id = UUID.randomUUID();
    Project p = new Project(id, "KEY", "Name", null, OTHER, Instant.now(), Instant.now(), null);
    when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(p));

    assertThatThrownBy(() -> service.getByIdOrKeyForCaller(id.toString(), CALLER))
        .isInstanceOf(ProjectNotFoundException.class);
  }
}
