package io.ngss.atlas.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Brute-force login throttling (T-033): per-account and per-IP failed-attempt counters with a
 * sliding window + lockout. {@link #checkThrottle} runs BEFORE credential verification (so a
 * correct password during an active lockout still 429s — D4), {@link #recordFailure} bumps both
 * buckets on any auth failure, and {@link #clearAccountBucket} resets the account bucket on
 * success (the IP bucket is intentionally retained).
 *
 * <p>Numeric {@code @Value} defaults are AppCDS-safe by construction (always a valid int — the
 * stage-3 no-DB boot uses them with no failure). The X-Forwarded-For trust list is parsed LAZILY
 * and tolerantly (malformed entries skipped), so a garbage env value never crashes boot.
 */
@Service
public class LoginAttemptService {

  private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

  private final LoginAttemptRepository repo;
  private final int maxAttempts;
  private final int windowMinutes;
  private final int ipMaxAttempts;
  private final String trustedProxyCidrsRaw;

  /** Lazily-parsed XFF trust list; null until first {@link #extractClientIp} call. */
  private volatile List<CidrBlock> trustedProxies;

  public LoginAttemptService(
      LoginAttemptRepository repo,
      @Value("${LOGIN_MAX_ATTEMPTS:5}") int maxAttempts,
      @Value("${LOGIN_LOCKOUT_WINDOW_MINUTES:15}") int windowMinutes,
      @Value("${LOGIN_IP_MAX_ATTEMPTS:20}") int ipMaxAttempts,
      @Value("${LOGIN_TRUSTED_PROXY_CIDRS:}") String trustedProxyCidrsRaw) {
    this.repo = repo;
    this.maxAttempts = maxAttempts;
    this.windowMinutes = windowMinutes;
    this.ipMaxAttempts = ipMaxAttempts;
    this.trustedProxyCidrsRaw = trustedProxyCidrsRaw;
  }

  /**
   * Throws {@link TooManyLoginAttemptsException} if either the account or the IP bucket is in an
   * active lockout. Read-only — never writes (the failed-attempt write is {@link #recordFailure}).
   */
  public void checkThrottle(String email, String clientIp) {
    throwIfLocked(accountKey(email), LoginAttemptKey.ACCOUNT);
    throwIfLocked(clientIp, LoginAttemptKey.IP);
  }

  private void throwIfLocked(String key, LoginAttemptKey type) {
    repo.findByKeyAndType(key, type)
        .filter(rec -> rec.lockedUntil() != null && rec.lockedUntil().isAfter(Instant.now()))
        .ifPresent(
            rec -> {
              throw new TooManyLoginAttemptsException(
                  "Too many failed login attempts. Try again later.", rec.lockedUntil());
            });
  }

  /** Records one failure against BOTH buckets (account: maxAttempts, IP: ipMaxAttempts). */
  public void recordFailure(String email, String clientIp) {
    repo.upsertFailedAttempt(accountKey(email), LoginAttemptKey.ACCOUNT, maxAttempts, windowMinutes);
    repo.upsertFailedAttempt(clientIp, LoginAttemptKey.IP, ipMaxAttempts, windowMinutes);
  }

  /** Reset-on-success: clears ONLY the account bucket; the IP bucket is intentionally kept. */
  public void clearAccountBucket(String email) {
    repo.deleteByKeyAndType(accountKey(email), LoginAttemptKey.ACCOUNT);
  }

  /**
   * The client IP for throttling. X-Forwarded-For is honoured ONLY when a trust list is configured
   * AND {@code remoteAddr} is within one of those CIDRs (SEC-5); then the LEFTMOST XFF value is
   * used. A blank trust list (default) NEVER trusts XFF (SEC-4) — returns {@code remoteAddr}.
   */
  public String extractClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    List<CidrBlock> trusted = trustedProxies();
    if (trusted.isEmpty()) {
      return remoteAddr; // SEC-4: no trust list → never trust the forwarded header
    }
    InetAddress remote;
    try {
      remote = InetAddress.getByName(remoteAddr);
    } catch (UnknownHostException e) {
      return remoteAddr;
    }
    boolean fromTrustedProxy = trusted.stream().anyMatch(block -> block.contains(remote));
    if (!fromTrustedProxy) {
      return remoteAddr; // SEC-5: direct peer is not a trusted proxy → ignore XFF
    }
    String xff = request.getHeader("X-Forwarded-For");
    if (xff == null || xff.isBlank()) {
      return remoteAddr;
    }
    return xff.split(",", 2)[0].trim(); // leftmost value only
  }

  private static String accountKey(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  // ───────────────────────── XFF trust list (lazy, tolerant) ─────────────────────────

  private List<CidrBlock> trustedProxies() {
    List<CidrBlock> result = trustedProxies;
    if (result == null) {
      result = parseCidrs(trustedProxyCidrsRaw);
      trustedProxies = result;
    }
    return result;
  }

  private static List<CidrBlock> parseCidrs(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<CidrBlock> blocks = new ArrayList<>();
    for (String entry : raw.split(",")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        blocks.add(CidrBlock.parse(trimmed));
      } catch (UnknownHostException | IllegalArgumentException e) {
        // IllegalArgumentException also covers NumberFormatException (bad prefix bits).
        log.warn("ignoring malformed LOGIN_TRUSTED_PROXY_CIDRS entry: {}", trimmed);
      }
    }
    return List.copyOf(blocks);
  }

  /** A parsed CIDR block; matches an {@link InetAddress} by comparing the network-prefix bits. */
  private record CidrBlock(byte[] network, int prefixBits) {

    static CidrBlock parse(String cidr) throws UnknownHostException {
      int slash = cidr.indexOf('/');
      String ipPart = slash >= 0 ? cidr.substring(0, slash) : cidr;
      byte[] network = InetAddress.getByName(ipPart.trim()).getAddress();
      int bits = slash >= 0 ? Integer.parseInt(cidr.substring(slash + 1).trim()) : network.length * 8;
      if (bits < 0 || bits > network.length * 8) {
        throw new IllegalArgumentException("prefix out of range: " + cidr);
      }
      return new CidrBlock(network, bits);
    }

    boolean contains(InetAddress candidate) {
      byte[] addr = candidate.getAddress();
      if (addr.length != network.length) {
        return false; // IPv4 vs IPv6 family mismatch
      }
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (addr[i] != network[i]) {
          return false;
        }
      }
      int remainingBits = prefixBits % 8;
      if (remainingBits > 0) {
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
      }
      return true;
    }
  }
}
