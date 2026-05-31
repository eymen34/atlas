package io.ngss.atlas.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  // N5: existsByEmailIgnoreCase derives UPPER(email)=UPPER(?), which does NOT
  // use the V1 lower(email) functional index (users_email_lower_key). Acceptable
  // while the table is small; replace with an explicit lower()-based @Query when
  // the table grows (auth performance backlog).
  boolean existsByEmailIgnoreCase(String email);

  Optional<User> findByEmailIgnoreCase(String email);
}
