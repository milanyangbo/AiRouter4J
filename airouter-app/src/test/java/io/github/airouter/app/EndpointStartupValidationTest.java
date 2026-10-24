package io.github.airouter.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.gateway.config.EndpointProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 配置形态错误发生在**启动期**，不在第一次调用时。
 *
 * <p>这些用例刻意去真的跑一遍 {@code SpringApplication}，而不是对着某个 validator 断言：
 * 需要被证明的是「违约会让进程起不来」这条链路上每一环都通 —— 包含 yml 里的占位符、
 * 属性绑定、校验、以及失败如何冒泡。任何一环脱开，这类缺陷就会变成运行期的 404 或
 * 401，而那种缺陷只有半夜才会被人知道。
 */
@Tag("integration")
final class EndpointStartupValidationTest {

  private static final String BASE_URL = "airouter.endpoint.base-url";
  private static final String API_KEY = "airouter.endpoint.api-key";
  private static final String MODEL = "airouter.endpoint.model";

  /**
   * 用系统属性而不是 SpringApplicationBuilder 的 properties()：后者是 defaultProperties，
   * 优先级低于 application.yml，覆盖不了 yml 里的占位符默认值。
   */
  private Throwable startupFailure(Map<String, String> overrides, String profile) {
    Map<String, String> previous = new HashMap<>();
    overrides.forEach((key, value) -> {
      previous.put(key, System.getProperty(key));
      System.setProperty(key, value);
    });

    Throwable failure = null;
    try {
      new SpringApplicationBuilder(AiRouterApplication.class)
          .web(WebApplicationType.NONE)
          .profiles(profile)
          .run();
    } catch (Throwable thrown) {
      failure = thrown;
    } finally {
      overrides.keySet().forEach(key -> {
        String value = previous.get(key);
        if (value == null) {
          System.clearProperty(key);
        } else {
          System.setProperty(key, value);
        }
      });
    }

    assertThat(failure)
        .as("预期这次启动会失败，但它起来了 —— 配置违约不再拦住启动")
        .isNotNull();
    return failure;
  }

  /** 把整条 cause 链上的文字摊平：异常类型各不相同，信息常常落在中间某一层。 */
  private String rendered(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      text.append(current).append('\n');
    }
    return text.toString();
  }

  @Test
  @DisplayName("云端档位未配置凭证时启动失败，报错逐个指名缺的是哪个环境变量")
  void cloudProfileWithoutCredentialsNamesEveryMissingVariable() {
    Throwable failure = startupFailure(
        Map.of(BASE_URL, "", API_KEY, "", MODEL, ""), "cloud");

    String message = rendered(failure);
    assertThat(message).contains(EndpointProperties.BASE_URL_ENV);
    assertThat(message).contains(EndpointProperties.API_KEY_ENV);
    assertThat(message).contains(EndpointProperties.MODEL_ENV);
  }

  @Test
  @DisplayName("base-url 缺少 /v1 后缀时启动失败，并给出「补上 /v1」级别的提示")
  void baseUrlWithoutV1StopsStartup() {
    Throwable failure = startupFailure(
        Map.of(BASE_URL, "https://api.example.com", API_KEY, "key", MODEL, "model"), "cloud");

    String message = rendered(failure);
    assertThat(message).contains("/v1");
    assertThat(message).contains(EndpointProperties.BASE_URL_ENV);
  }

  @Test
  @DisplayName("切换档位不需要改代码：给足环境，云端档位也能起来")
  void switchingProfileNeedsNoCodeChange() {
    Map<String, String> previous = new HashMap<>();
    Map<String, String> overrides = Map.of(
        BASE_URL, "https://api.example.invalid/v1",
        API_KEY, "not-a-real-key",
        MODEL, "some-model");
    overrides.forEach((key, value) -> {
      previous.put(key, System.getProperty(key));
      System.setProperty(key, value);
    });

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(AiRouterApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("cloud")
            .run()) {

      EndpointProperties endpoint = context.getBean(EndpointProperties.class);
      assertThat(endpoint.baseUrl()).isEqualTo("https://api.example.invalid/v1");
      assertThat(endpoint.model()).isEqualTo("some-model");
    } finally {
      overrides.keySet().forEach(key -> {
        String value = previous.get(key);
        if (value == null) {
          System.clearProperty(key);
        } else {
          System.setProperty(key, value);
        }
      });
    }
  }
}
