package io.github.airouter.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置文件里不许出现凭证。
 *
 * <p>这条之所以要由机器断言而不是写进 README：来源项目把「待会儿删掉」的密钥留在 yml 里
 * 是一种习惯，而习惯在 deadline 面前不成立。这里只看一件事 ——
 * 端点相关的每一项取值是不是**指向环境变量的占位符**，不是的话就拦住。
 */
final class EndpointSecretsAreNotCommittedTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z_]+(:[^}]*)?}$");
  private static final Pattern SETTING = Pattern.compile("^\\s*(base-url|api-key|model)\\s*:\\s*(\\S+)\\s*$");

  @Test
  @DisplayName("端点配置的每一项取值都是环境变量占位符")
  void endpointValuesArePlaceholders() throws IOException {
    List<String> lines = readConfiguration();

    assertThat(lines).as("应当读得到 application.yml").isNotEmpty();

    List<String> offending = lines.stream()
        .map(SETTING::matcher)
        .filter(java.util.regex.Matcher::matches)
        .map(matcher -> matcher.group(2))
        .filter(value -> !PLACEHOLDER.matcher(value).matches())
        .toList();

    assertThat(offending)
        .as("这些取值不是环境变量占位符：凭证只能从环境里来")
        .isEmpty();
  }

  @Test
  @DisplayName("配置文件里不出现任何形如密钥的字面值")
  void noKeyLikeLiteral() throws IOException {
    assertThat(readConfiguration())
        .noneMatch(line -> line.contains("sk-") || line.contains("Bearer "));
  }

  private List<String> readConfiguration() throws IOException {
    try (InputStream stream =
        EndpointSecretsAreNotCommittedTest.class.getResourceAsStream("/application.yml")) {
      assertThat(stream).as("application.yml 应当在 classpath 上").isNotNull();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        return reader.lines().toList();
      }
    }
  }
}
