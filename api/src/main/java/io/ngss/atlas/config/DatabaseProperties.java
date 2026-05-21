package io.ngss.atlas.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "app.database")
public class DatabaseProperties {

  @NotBlank(message = "url must not be blank")
  @Pattern(
      regexp = "^jdbc:postgres(ql)?://.+",
      message = "url must be a JDBC URL starting with jdbc:postgresql:// or jdbc:postgres://")
  private String url = "jdbc:postgresql://localhost:5432/atlas";

  @NotBlank(message = "username must not be blank")
  private String username = "atlas";

  @NotNull(message = "password must not be null (use an empty string to opt out)")
  private String password = "";

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = (url == null) ? null : url.trim();
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
