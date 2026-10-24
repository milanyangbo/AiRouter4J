package io.github.airouter.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 每一个测试都必须属于某一道 gate，并且没有被关掉。
 *
 * <p>为什么要有这条：CI 的四个 gate 是按 JUnit 的 {@code @Tag} 分派的，
 * 而「一个新写的测试忘了打标记」恰恰是这类划分最容易出现的漏法 —— 它不红、不报错，
 * 只是**从来没有被任何一道 gate 跑到过**。同理，一个被 {@code @Disabled} 关掉的测试
 * 在报告里显示为通过，这正是来源项目的老毛病（ADR-0008 第 1 节）：
 * 门禁里最危险的不是红灯，是「看起来绿但什么都没跑」。
 *
 * <p>判的是源码而不是字节码：三个模块的测试类互相不在对方的 classpath 上，
 * 从任何一个模块里都看不到全部测试，而这道规则必须一次看到全部。
 */
@Tag("architecture")
final class EveryTestBelongsToAGateTest {

  /** 三个 gate 的名字。它们与 CI 里 {@code -Dgroups=} 的取值是同一组字符串，改一处就得改另一处。 */
  private static final Set<String> GATES = Set.of("unit", "integration", "architecture");

  private static final Pattern TAG = Pattern.compile("@Tag\\(\"([^\"]+)\"\\)");

  /** ArchUnit 引擎的入口注解。只认行首的注解位置，理由见 {@link #archUnitEngineIsNotUsed()}。 */
  private static final Pattern ARCH_UNIT_ENGINE =
      Pattern.compile("^\\s*@AnalyzeClasses", Pattern.MULTILINE);

  /**
   * 关掉一个测试的手段。列的是注解而不是理由：只要它们出现在测试源码里，
   * 那个测试就没有在跑 —— 无论当时挂上去的理由有多充分。
   *
   * <p>只认**行首的注解位置**，不认出现在任何地方的子串：否则这条规则会先把自己的源码判红 ——
   * 它自己就写着这些名字。判定方式决定判定的对象，这条规则因此比看上去更需要想清楚。
   */
  private static final Pattern SILENT_SKIP = Pattern.compile(
      "^\\s*@(Disabled|Ignore|EnabledIf\\w*|DisabledIf\\w*"
          + "|EnabledOnOs|DisabledOnOs|EnabledOnJre|DisabledOnJre|EnabledForJreRange)\\b",
      Pattern.MULTILINE);

  @Test
  @DisplayName("每个测试类都标了 gate，且标的确实是那三个之一")
  void everyTestClassDeclaresAGate() throws IOException {
    List<Path> tests = testSources();

    List<String> unassigned = tests.stream()
        .filter(path -> declaredGates(read(path)).isEmpty())
        .map(EveryTestBelongsToAGateTest::display)
        .toList();
    List<String> misspelled = tests.stream()
        .filter(path -> {
          Set<String> declared = declaredGates(read(path));
          return !declared.isEmpty() && !GATES.containsAll(declared);
        })
        .map(EveryTestBelongsToAGateTest::display)
        .toList();

    assertThat(unassigned)
        .as("这些测试类没有标 gate，于是没有任何一道 CI gate 会跑到它们（允许的值是 %s）", GATES)
        .isEmpty();
    assertThat(misspelled)
        .as("这些测试类标了不存在的 gate。gate 的名字与 CI 里 -Dgroups= 的取值必须一致")
        .isEmpty();
  }

  @Test
  @DisplayName("没有测试被条件注解关掉：被关掉的测试在报告里显示为通过")
  void noTestIsSwitchedOff() throws IOException {
    List<String> offenders = testSources().stream()
        .filter(path -> SILENT_SKIP.matcher(read(path)).find())
        .map(EveryTestBelongsToAGateTest::display)
        .toList();

    assertThat(offenders)
        .as("这些测试类里有条件注解。一个可以被关掉的测试等于没有 —— 要暂时不跑，就别提交")
        .isEmpty();
  }

  /**
   * ArchUnit 自带的引擎（{@code @AnalyzeClasses} + {@code @ArchTest}）看不见类上的 {@code @Tag}：
   * 用它的测试类在这里打了标记，用 {@code -Dgroups=} 筛选时却整个类都不会被选中 ——
   * 报告显示通过，实际上那些规则一条都没跑。本票正是被它坑了一次，因此把它变成断言。
   *
   * <p>同样只认行首的注解位置：这一条的名字必然出现在规则自己的说明里（就在这几行里），
   * 按子串判会先把这条规则自己的源码判红。
   */
  @Test
  @DisplayName("没有测试使用 ArchUnit 引擎：它看不见 gate 标记，会被分组筛选静默漏掉")
  void archUnitEngineIsNotUsed() throws IOException {
    List<String> offenders = testSources().stream()
        .filter(path -> ARCH_UNIT_ENGINE.matcher(read(path)).find())
        .map(EveryTestBelongsToAGateTest::display)
        .toList();

    assertThat(offenders)
        .as("这些测试类用 ArchUnit 引擎跑规则。那个引擎不认 @Tag，"
            + "于是它们不会进入任何一道 gate —— 改成 @Test 里显式 check 规则（见 ModuleStructureTest）")
        .isEmpty();
  }

  @Test
  @DisplayName("确实扫到了测试源码：扫描本身失效时不能显示为通过")
  void scanIsNotEmpty() throws IOException {
    assertThat(testSources())
        .as("一个测试类都没扫到，说明根路径或扫描条件已经失效 —— 上面两条断言会因此变成空跑")
        .hasSizeGreaterThan(20);
  }

  /** 全部测试源码。{@code legacy/} 是素材不是生产代码，{@code target/} 是构建产物。 */
  private static List<Path> testSources() throws IOException {
    Path root = repositoryRoot();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree
          .filter(path -> path.getFileName().toString().endsWith("Test.java"))
          .filter(path -> !path.toString().contains("/legacy/"))
          .filter(path -> !path.toString().contains("/target/"))
          .sorted()
          .toList();
    }
  }

  /** 从当前工作目录向上找，直到某个父目录里有 {@code .git} —— 那才是仓库根。 */
  private static Path repositoryRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("应当能从工作目录向上找到仓库根（含 .git 的那一层）").isNotNull();
    return dir;
  }

  private static Set<String> declaredGates(String source) {
    return TAG.matcher(source).results()
        .map(match -> match.group(1))
        .collect(Collectors.toSet());
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("读不到测试源码：" + path, failure);
    }
  }

  private static String display(Path path) {
    return path.toString().replace(repositoryRoot() + "/", "");
  }
}
