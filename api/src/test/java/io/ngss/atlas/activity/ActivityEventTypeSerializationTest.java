package io.ngss.atlas.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ngss.atlas.activity.payload.AssigneeChangedPayload;
import io.ngss.atlas.activity.payload.CreatedPayload;
import io.ngss.atlas.activity.payload.LabelsChangedPayload;
import io.ngss.atlas.activity.payload.PriorityChangedPayload;
import io.ngss.atlas.activity.payload.StatusChangedPayload;
import io.ngss.atlas.domain.TicketPriority;
import io.ngss.atlas.domain.TicketStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * REG-9: every payload type round-trips through Jackson 3 ({@code tools.jackson.*})
 * unchanged, and {@link LabelsChangedPayload}'s lists are immutable defensive copies.
 * Docker-free.
 */
class ActivityEventTypeSerializationTest {

  private final ObjectMapper om = JsonMapper.builder().build();

  @Test
  void createdPayload_roundTrips() {
    CreatedPayload p = new CreatedPayload("Build the thing", TicketStatus.TODO, TicketPriority.P2);
    assertThat(om.readValue(om.writeValueAsString(p), CreatedPayload.class)).isEqualTo(p);
  }

  @Test
  void statusChangedPayload_roundTrips() {
    StatusChangedPayload p = new StatusChangedPayload(TicketStatus.TODO, TicketStatus.IN_PROGRESS);
    assertThat(om.readValue(om.writeValueAsString(p), StatusChangedPayload.class)).isEqualTo(p);
  }

  @Test
  void assigneeChangedPayload_roundTrips_withNullFrom() {
    AssigneeChangedPayload p = new AssigneeChangedPayload(null, UUID.randomUUID());
    AssigneeChangedPayload back =
        om.readValue(om.writeValueAsString(p), AssigneeChangedPayload.class);
    assertThat(back).isEqualTo(p);
    assertThat(back.from()).isNull();
  }

  @Test
  void priorityChangedPayload_roundTrips() {
    PriorityChangedPayload p = new PriorityChangedPayload(TicketPriority.P3, TicketPriority.P1);
    assertThat(om.readValue(om.writeValueAsString(p), PriorityChangedPayload.class)).isEqualTo(p);
  }

  @Test
  void labelsChangedPayload_roundTrips() {
    LabelsChangedPayload p =
        new LabelsChangedPayload(List.of(UUID.randomUUID(), UUID.randomUUID()), List.of(UUID.randomUUID()));
    assertThat(om.readValue(om.writeValueAsString(p), LabelsChangedPayload.class)).isEqualTo(p);
  }

  @Test
  void labelsChangedPayload_listsAreImmutableDefensiveCopies() {
    List<UUID> added = new ArrayList<>(List.of(UUID.randomUUID()));
    LabelsChangedPayload p = new LabelsChangedPayload(added, List.of());
    added.add(UUID.randomUUID()); // mutate the source after construction

    assertThat(p.added()).hasSize(1); // copy is decoupled from the source list
    assertThatThrownBy(() -> p.added().add(UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
