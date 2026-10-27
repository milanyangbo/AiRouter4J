package io.github.airouter.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.airouter.app.AiRouterApplication;
import io.github.airouter.gateway.config.InstanceDefinition;
import io.github.airouter.gateway.config.ServicesProperties;
import io.github.airouter.gateway.providers.UpstreamShape;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * `local` 档位与配置来源。
 *
 * <p>两条要求各自独立可验证：**不设任何端点变量也能起来**，以及**能完成一次受支持的调用**。
 * 前者是「配置漏项表现为起不来」那一类问题的另一面 —— 档位存在的意义是给出一套**能跑**的默认值，
 * 而不是给出一份文档。
 *
 * <p>完成一次调用那一侧把地址换成了 {@link RecordedUpstream}。真 Ollama 不进常规测试
 * （它的存在与否是一个环境假设，会让终态类断言失去确定性），
 * 其余四项（形态、凭证、模型、超时）**全部来自 local 档位的默认值**，一个都没被覆盖。
 *
 * <p>这一类断言不写在 {@code @SpringBootTest} 上：档位本身就是被断言的对象，
 * 而 {@code @DynamicPropertySource} 会抢在档位之前生效，于是「默认值是什么」反而看不出来。
 * 这里直接用 {@link SpringApplicationBuilder} 起两个上下文，一个原样、一个只换地址。
 */
@Tag("integration")
final class LocalProfileTest {

  /** 扫描凭证时要跳过的目录：它们不是本项目的配置，而是素材与构建产物。 */
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of("legacy", "target", ".git", ".workbuddy", "node_modules");

  private static final RecordedUpstream UPSTREAM = startUpstream();

  private static RecordedUpstream startUpstream() {
    try {
      return new RecordedUpstream(0, 200, "{}");
    } catch (IOException failure) {
      throw new IllegalStateException("回放上游起不来", failure);
    }
  }

  @Test
  @DisplayName("local 档位：不设任何端点变量也能起来，端点形态与地址都由档位给出")
  void localProfileStartsWithoutAnyEndpointVariable() {
    try (ConfigurableApplicationContext context = startLocal()) {
      UpstreamRegistry registry = context.getBean(UpstreamRegistry.class);
      ServicesProperties services = context.getBean(ServicesProperties.class);
      // 值在配置那一层看，同时确认注册表里真的有这一项以及它已经拿到了出站能力 ——
      // 「档位给得出来」与「能被装配成一个能出网的对象」是两件事，只有后者才值得断言。
      InstanceDefinition instance = services.defaultServiceDefinition().enabledInstances().get(0);

      assertThat(instance.shape()).isEqualTo(UpstreamShape.OPENAI);
      // 本机地址写 127.0.0.1 不写 localhost：后者要先走一次名字解析，
      // 而解析结果不保证是 IPv4 —— 表现是「偶发连不上」，而且只在部分机器上出现。
      assertThat(instance.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
      assertThat(instance.model()).isNotBlank();
      assertThat(instance.apiKey()).isNotBlank();

      RegisteredInstance registered = registry.requireService(services.defaultService())
          .instance(instance.id())
          .orElseThrow();
      assertThat(registered.shape()).isEqualTo(instance.shape());
      assertThat(registered.provider().streaming()).isNotNull();
      assertThat(registered.provider().completion()).isNotNull();
    }
  }

  @Test
  @DisplayName("local 档位能完成一次受支持的调用（只有地址被换成录制器）")
  void localProfileCompletesASupportedCall() {
    UPSTREAM.replay(200, """
        {"id":"chatcmpl-local","object":"chat.completion","model":"qwen2.5:0.5b",
         "choices":[{"index":0,"message":{"role":"assistant","content":"好"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}
        """);
    // 走环境变量（这里用系统属性代替它 —— 同一个 JVM 里改不了真环境变量），
    // 而不是走 `--airouter.services.chat.instances[0].base-url=`：列表元素不跨配置来源合并，
    // 在命令行覆盖其中一个字段会让这个实例的其余字段一起消失。
    // 顺带这也正是「环境变量优先于档位默认值」的路径。
    System.setProperty("AIROUTER_ENDPOINT_BASE_URL", UPSTREAM.baseUrl());
    try (ConfigurableApplicationContext context = startLocal()) {
      // 绑定在上下文上而不是另起一个 socket：这一段要证明的是「档位给的默认值跑得通一次调用」，
      // 而不是「端口能不能连上」—— 后者由其他端到端测试守着，那里走的是真实 socket。
      WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

      client.post()
          .uri("/v1/chat/completions")
          .header("Content-Type", "application/json")
          .bodyValue("{\"model\":\"qwen2.5:0.5b\","
              + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}")
          .exchange()
          .expectStatus().isOk()
          .expectBody()
          .jsonPath("$.choices[0].message.content").isEqualTo("好");
    } finally {
      // 这个测试用例的生命周期之外不再生效：它是 JVM 全局的，
      // 漏清会让本类里「local 档位自带 127.0.0.1:11434」那条断言在下一个用例上偶发变绿。
      System.clearProperty("AIROUTER_ENDPOINT_BASE_URL");
    }
  }

  /**
   * 地址与凭证只从环境变量读取：配置文件里出现的只能是**占位符**。
   *
   * <p>它不是格式检查。一旦有人把一次联调用的真实密钥留在配置里，
   * 那份值会跟着分支走、被别人复制、进到别人的机器上 —— 而撤回它的唯一办法是当它已经泄露。
   */
  @Test
  @DisplayName("配置文件里的地址与凭证只能是占位符：值里必须出现 ${")
  void configurationFilesCarryPlaceholdersOnly() throws IOException {
    List<Path> files = configurationFiles();

    assertThat(files).as("没扫到任何配置文件，检查一下是不是路径变了").isNotEmpty();
    for (Path file : files) {
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
          continue;
        }
        if (trimmed.contains("api-key:") || trimmed.contains("api-key=")
            || trimmed.contains("base-url:") || trimmed.contains("base-url=")) {
          assertThat(trimmed)
              .as(file + " 里这一行的值必须是 ${...} 占位符：" + trimmed)
              .contains("${");
        }
      }
    }
  }

  /** 含真实凭证的文件一律不得入库。`.gitignore` 已经把它们挡在外面，这里钉住「确实没有」。 */
  @Test
  @DisplayName("仓库里没有 .env（只有 .env.example）：含真实凭证的文件不入库")
  void noEnvironmentFileIsPresent() throws IOException {
    try (Stream<Path> children = Files.list(repoRoot())) {
      assertThat(children.filter(path -> path.getFileName().toString().startsWith(".env"))
          .map(path -> path.getFileName().toString())
          .toList())
          .as("仓库里出现 .env 之外的环境文件：真实凭证不入库")
          .containsExactly(".env.example");
    }
  }

  /** 起一个 local 档位的上下文。命令行参数原样传进去：档位默认值之外要覆盖的东西只从这里进来。 */
  private static ConfigurableApplicationContext startLocal(String... args) {
    return new SpringApplicationBuilder(AiRouterApplication.class)
        .profiles("local")
        .build()
        .run(args);
  }

  /**
   * 仓库根。
   *
   * <p>不能写 {@code Path.of(".")}：surefire 的工作目录是**模块**目录，
   * 于是「仓库里有没有 .env」会变成「airouter-app 里有没有 .env」—— 一个永远为真、也就没有意义的问题。
   */
  private static Path repoRoot() {
    Path candidate = Path.of(".").toAbsolutePath().normalize();
    while (!Files.exists(candidate.resolve(".git"))) {
      Path parent = candidate.getParent();
      if (parent == null) {
        return candidate;
      }
      candidate = parent;
    }
    return candidate;
  }

  private static List<Path> configurationFiles() throws IOException {
    Path root = repoRoot();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree
          .filter(Files::isRegularFile)
          .filter(LocalProfileTest::isConfigurationFile)
          .filter(path -> !insideSkippedDirectory(root, path))
          .sorted()
          .toList();
    }
  }

  private static boolean isConfigurationFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".yml") || name.endsWith(".yaml") || name.startsWith(".env");
  }

  private static boolean insideSkippedDirectory(Path root, Path path) {
    Path relative = root.relativize(path.toAbsolutePath().normalize());
    for (Path element : relative) {
      if (SKIPPED_DIRECTORIES.contains(element.toString())) {
        return true;
      }
    }
    return false;
  }
}
