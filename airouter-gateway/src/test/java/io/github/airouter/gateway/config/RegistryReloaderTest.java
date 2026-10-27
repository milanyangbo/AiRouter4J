package io.github.airouter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.airouter.gateway.providers.UpstreamProvider;
import io.github.airouter.gateway.providers.UpstreamProviderFactory;
import io.github.airouter.gateway.providers.UpstreamShape;
import io.github.airouter.gateway.registry.RegisteredInstance;
import io.github.airouter.gateway.registry.UpstreamRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 热重载与配置版本（ADR-0010 第 5、6 节）。
 *
 * <p>这一类断言**不起容器**：换注册表这件事不需要 Spring 在场，
 * 而「不需要在场」本身就是 ADR-0010 第 5 节要求的那件事的证据 ——
 * 热重载若依赖重建上下文，这里就起不来。
 *
 * <p>两条主线：
 *
 * <ul>
 *   <li>**只换该换的**：出网身份变了的实例换一个新的出网对象，其余沿用同一个 ——
 *       将来挂在出网对象上的熔断计数与延迟样本正是靠这一点在配置变更里活下来
 *   <li>**变更有版本、可回滚**：每一次变更产生新版本号（回滚也是），
 *       而一次失败的变更什么也不留下
 * </ul>
 */
@Tag("unit")
final class RegistryReloaderTest {

  /** 第一个实例的端点。端口不存在也无妨：装配不连网。 */
  private static final String FIRST_ADDRESS = "http://127.0.0.1:9/v1";

  private static final String SECOND_ADDRESS = "http://127.0.0.1:10/v1";

  /** 换过的地址。它只在「改了地址就该换对象」那几条里出现。 */
  private static final String MOVED_ADDRESS = "http://127.0.0.1:11/v1";

  private static final StreamProperties STREAM =
      new StreamProperties(Duration.ofMillis(600), 1 << 16, 1 << 20, 1 << 18, 1 << 20);

  @Test
  @DisplayName("热重载只重建变了的那个实例：没变的实例沿用同一个出网对象")
  void reloadRebuildsOnlyTheInstanceThatChanged() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS), instance("i2", SECOND_ADDRESS));
    UpstreamProvider firstBefore = runtime.provider("i1");
    UpstreamProvider secondBefore = runtime.provider("i2");

    runtime.reloader.reload(runtime.services(instance("i1", MOVED_ADDRESS),
        instance("i2", SECOND_ADDRESS)));

    assertThat(runtime.provider("i1")).as("改了地址的实例").isNotSameAs(firstBefore);
    assertThat(runtime.provider("i2")).as("一个字都没改的实例").isSameAs(secondBefore);
  }

  /**
   * 改治理字段（权重、启用标志、标签）**不换**出网对象。
   *
   * <p>这是「配置变更不等于状态清零」那一条在这一个阶段的落点：
   * 出网对象是运行时状态（熔断计数、延迟样本）将来的宿主，
   * 把实例关掉再打开就被换掉它，等于一次运维操作顺手清空了那个实例的历史。
   */
  @Test
  @DisplayName("改权重 / 启用标志 / 标签不换出网对象：值变了，出网的那一个还是同一个")
  void governanceFieldsDoNotRebuildTheOutboundObject() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS), instance("i2", SECOND_ADDRESS));
    UpstreamProvider before = runtime.provider("i1");

    InstanceDefinition governed = new InstanceDefinition("i1", UpstreamShape.OPENAI, null,
        FIRST_ADDRESS, "key-i1", "model-i1", Duration.ofSeconds(5), 7, false, List.of("blue"));
    runtime.reloader.reload(runtime.services(governed, instance("i2", SECOND_ADDRESS)));

    assertThat(runtime.provider("i1")).isSameAs(before);
    assertThat(runtime.instance("i1").weight()).isEqualTo(7);
    assertThat(runtime.instance("i1").enabled()).isFalse();
    assertThat(runtime.instance("i1").tags()).containsExactly("blue");
  }

  @Test
  @DisplayName("每一次变更产生一个新版本号，账本里留着它们")
  void everyChangeProducesANewVersion() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS), instance("i2", SECOND_ADDRESS));

    assertThat(runtime.ledger.current().version()).isEqualTo(1L);
    assertThat(runtime.ledger.versions()).containsExactly(1L);

    ConfigurationSnapshot snapshot =
        runtime.reloader.reload(runtime.services(instance("i1", MOVED_ADDRESS),
            instance("i2", SECOND_ADDRESS)));

    assertThat(snapshot.version()).isEqualTo(2L);
    assertThat(runtime.ledger.current().version()).isEqualTo(2L);
    assertThat(runtime.ledger.versions()).containsExactly(1L, 2L);
    assertThat(runtime.ledger.version(2L)).isPresent();
  }

  /**
   * 回滚**前进**版本号，内容取自上一版。
   *
   * <p>版本号回退看起来更直观，代价是两个副本之间再也没法用「版本号大的新」来判断谁落后，
   * 而多副本广播的是版本号，不是全量配置（ADR-0010 第 6 节）。
   */
  @Test
  @DisplayName("回滚产生新的一版，内容是上一版的那一份 —— 版本号只增不减")
  void rollbackAppliesThePreviousContentAsANewVersion() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS), instance("i2", SECOND_ADDRESS));
    runtime.reloader.reload(runtime.services(instance("i1", MOVED_ADDRESS),
        instance("i2", SECOND_ADDRESS)));
    long beforeRollback = runtime.ledger.current().version();

    ConfigurationSnapshot rolledBack = runtime.reloader.rollback();

    assertThat(rolledBack.version()).isEqualTo(beforeRollback + 1);
    assertThat(addressIn(rolledBack, "i1")).isEqualTo(FIRST_ADDRESS);
    assertThat(runtime.ledger.versions()).containsExactly(1L, 2L, 3L);
  }

  @Test
  @DisplayName("第一版没有可回滚的上一版：拒绝，并指名当前版本号")
  void rollbackWithoutAnEarlierVersionIsRejected() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS));

    assertThatThrownBy(runtime.reloader::rollback)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("v1");
    assertThat(runtime.ledger.versions()).containsExactly(1L);
  }

  /**
   * 一次失败的变更**什么也不留下**。
   *
   * <p>配置的有效性与启动期是**同一套规则**（都在 {@link ServicesProperties} 的构造器里），
   * 所以一份变不出来的配置在到达重载入口之前就已经被挡住了。这一条钉的是它被挡住之后的结果：
   * 生效的那一版一个字都没动 —— 版本号没前进，出网对象还是原来那个。
   *
   * <p>它同时是「先装配、再记账、最后换注册表」这个次序的守卫：反过来的次序会在装配抛异常时
   * 留下「账本说 v2 生效过，而注册表里还是 v1 的东西」这样一处对不上的账 ——
   * 那种不一致要到副本之间对账时才被发现，而那时已经分不清谁对。
   */
  @Test
  @DisplayName("一次失败的变更不改变生效中的那一版：版本号不前进，出网对象还是原来那个")
  void aFailedChangeLeavesTheEffectiveVersionUntouched() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS));
    UpstreamProvider before = runtime.provider("i1");

    assertThatThrownBy(() -> runtime.reloader.reload(
        new ServicesProperties("ghost", Map.of("chat",
            new ServiceDefinition(List.of(instance("i1", MOVED_ADDRESS)))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost");

    assertThat(runtime.ledger.current().version()).isEqualTo(1L);
    assertThat(runtime.provider("i1")).isSameAs(before);
  }

  @Test
  @DisplayName("被一次变更删掉的实例不再在注册表里；再加回来是一个新的出网对象")
  void anInstanceRemovedByAChangeComesBackAsANewOne() {
    Runtime runtime = boot(instance("i1", FIRST_ADDRESS), instance("i2", SECOND_ADDRESS));
    UpstreamProvider secondBefore = runtime.provider("i2");

    runtime.reloader.reload(runtime.services(instance("i1", FIRST_ADDRESS)));
    assertThat(runtime.instance("i2")).as("被删掉的 i2").isNull();

    runtime.reloader.reload(runtime.services(instance("i1", FIRST_ADDRESS),
        instance("i2", SECOND_ADDRESS)));
    assertThat(runtime.provider("i2")).as("回来的 i2").isNotSameAs(secondBefore);
  }

  /**
   * 历史上限。
   *
   * <p>无限增长的变更史在长跑的进程上是一个缓慢的泄漏，而回滚不需要三个月前的那一版。
   * 上限带来的代价（更早的版本回不去）必须是有意选的，所以它被断言在这里。
   */
  @Test
  @DisplayName("账本只留最近若干版：更早的被丢掉，也就回不去了")
  void historyKeepsOnlyTheMostRecentVersions() {
    Runtime runtime = boot(2, instance("i1", FIRST_ADDRESS));
    runtime.reloader.reload(runtime.services(instance("i1", SECOND_ADDRESS)));
    runtime.reloader.reload(runtime.services(instance("i1", MOVED_ADDRESS)));

    assertThat(runtime.ledger.versions()).containsExactly(2L, 3L);
    assertThat(runtime.ledger.version(1L)).isEmpty();
  }

  private static String addressIn(ConfigurationSnapshot snapshot, String instanceId) {
    return snapshot.services().services().get("chat").instances().stream()
        .filter(instance -> instance.id().equals(instanceId))
        .findFirst()
        .orElseThrow()
        .baseUrl();
  }

  private static InstanceDefinition instance(String id, String baseUrl) {
    return new InstanceDefinition(id, UpstreamShape.OPENAI, null, baseUrl, "key-" + id,
        "model-" + id, Duration.ofSeconds(5), 1, true, List.of());
  }

  /** 一套已经跑起来的东西：注册表、账本与热重载入口，外加取用它们的几个短写法。 */
  private record Runtime(UpstreamRegistry registry, ConfigurationLedger ledger,
                         RegistryReloader reloader) {

    ServicesProperties services(InstanceDefinition... instances) {
      return new ServicesProperties("chat",
          Map.of("chat", new ServiceDefinition(List.of(instances))));
    }

    RegisteredInstance instance(String instanceId) {
      return registry.requireService("chat").instance(instanceId).orElse(null);
    }

    UpstreamProvider provider(String instanceId) {
      return instance(instanceId).provider();
    }
  }

  private static Runtime boot(InstanceDefinition... instances) {
    return boot(ConfigurationLedger.RETAINED_VERSIONS, instances);
  }

  private static Runtime boot(int retainedVersions, InstanceDefinition... instances) {
    InstanceAssembler assembler =
        new InstanceAssembler(STREAM, new UpstreamProviderFactory(), outcome -> { });
    ServicesProperties initial = new ServicesProperties("chat",
        Map.of("chat", new ServiceDefinition(List.of(instances))));
    UpstreamRegistry registry = new UpstreamRegistry(assembler.assemble(initial));
    ConfigurationLedger ledger = new ConfigurationLedger(initial, retainedVersions);
    return new Runtime(registry, ledger, new RegistryReloader(assembler, ledger, registry::replace));
  }
}
