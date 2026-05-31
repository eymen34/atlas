package io.ngss.atlas.domain;

import io.ngss.atlas.auth.dto.RegisterRequest;
import io.ngss.atlas.auth.dto.UserRegisteredResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers a new user: normalizes the email, fast-path duplicate check, then
 * persists a {@link User} and its {@link PasswordCredential} (BCrypt hash) in a
 * single transaction.
 *
 * <p>Duplicate handling is two-layered: the {@code existsByEmailIgnoreCase}
 * pre-check covers the common case, and the V1 {@code users_email_lower_key}
 * unique index covers the concurrent race — a losing race surfaces as a
 * {@link org.springframework.dao.DataIntegrityViolationException}, which is
 * intentionally NOT caught here so GlobalExceptionHandler maps it to 409.
 */
@Service
public class RegistrationService {

  private final UserRepository userRepository;
  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordEncoder passwordEncoder;

  public RegistrationService(
      UserRepository userRepository,
      PasswordCredentialRepository passwordCredentialRepository,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordCredentialRepository = passwordCredentialRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public UserRegisteredResponse register(RegisterRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);

    if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
      throw new EmailAlreadyRegisteredException(normalizedEmail);
    }

    Instant now = Instant.now();
    UUID userId = UUID.randomUUID();

    User user =
        userRepository.save(
            new User(userId, normalizedEmail, request.displayName().trim(), now, now));

    String hash = passwordEncoder.encode(request.password());
    if (hash == null || hash.length() != 60) {
      // Defensive: a misconfigured encoder must fail loudly, never persist a
      // truncated/garbage credential.
      throw new IllegalStateException("Unexpected bcrypt hash length");
    }
    passwordCredentialRepository.save(new PasswordCredential(userId, hash, now));

    return new UserRegisteredResponse(userId, normalizedEmail, user.getDisplayName(), now);
  }
}
