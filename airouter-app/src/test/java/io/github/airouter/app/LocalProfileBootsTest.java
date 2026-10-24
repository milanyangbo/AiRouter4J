package io.github.airouter.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.gateway.config.EndpointProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 本地档位：不需要任何环境变量、不需要网络就能起来，且健康入口可达。
 *
 * <p>这是后面所有端到端行为测试的前提 —— 一个能真跑到自己的 HTTP 端口上的进程，
 * 而不是一个被 mock 包起来的 context。因此这里用的是随机端口的真 Server，
 * 请求走真实的 socket 进去。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
final class LocalProfileBootsTest {

  private static WebTestClient clientOn(int port) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  @DisplayName("默认档位是 local：不带任何环境变量也不会落到需要凭证的那一档")
  void localIsTheDefaultProfile(@Autowired Environment environment) {
    assertThat(environment.getActiveProfiles()).contains("local");
  }

  @Test
  @DisplayName("存活/健康入口在真实 HTTP 端口上可达且报 UP")
  void healthEndpointIsReachable(@Value("${local.server.port}") int port) {
    clientOn(port).get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  @Test
  @DisplayName("本地档位的端点是本机地址：它能起来这件事不依赖任何外部服务")
  void endpointPointsAtLocalhost(@Autowired EndpointProperties endpoint) {
    assertThat(endpoint.baseUrl()).startsWith("http://localhost:");
    assertThat(endpoint.baseUrl()).endsWith("/v1");
  }
}
