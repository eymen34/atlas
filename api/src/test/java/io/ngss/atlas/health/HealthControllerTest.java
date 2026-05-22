package io.ngss.atlas.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

  @Test
  void returnsTwoHundredUpJson() {
    HealthController controller = new HealthController();
    ResponseEntity<Map<String, String>> response = controller.health();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).containsExactly(Map.entry("status", "UP"));
  }

  @Test
  void hasNoDataSourceField() {
    boolean anyDataSource =
        Arrays.stream(HealthController.class.getDeclaredFields())
            .anyMatch(f -> DataSource.class.isAssignableFrom(f.getType()));
    assertThat(anyDataSource)
        .as("HealthController must not hold a DataSource reference")
        .isFalse();
  }

  @Test
  void doesNotTouchUninjectedDataSourceMock() {
    DataSource ds = mock(DataSource.class);
    HealthController controller = new HealthController();
    controller.health();
    verifyNoInteractions(ds);
  }
}
