package io.github.airouter.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 行覆盖率不低于 60% —— {@code docs/agents/testing.md} 第二节那三个数里唯一的下限。
 *
 * <p>它读的是**四个模块合起来**的探针数据，不是每模块各判一次。为什么：本项目的入站接缝
 * 刻意选在 HTTP 入口，于是 gateway 的代码主要由这个模块里的端到端测试跑过。同一个 gateway，
 * 只认自己那份数据是 14.2%，把四份并起来看是 84.0%。按模块各设一道闸，红的不表示
 * 「gateway 缺测试」，只表示「测试住在另一个模块」—— 修它的唯一办法是把端到端测试在
 * gateway 里重抄一遍，那正是 testing.md 第一节反对的按类铺开。
 *
 * <p>为什么是一条测试而不是 jacoco 的 {@code check} 或 {@code report-aggregate}：
 * {@code check} 只认当前模块 {@code target/classes} 下的类，判不出跨模块的数；
 * {@code report-aggregate} 只收**直接**依赖，core 是经由 gateway 传递进来的，
 * 于是它产出的报告里静默少掉 core 与 app 自己（实测 18 个类而不是 41 个）——
 * 一份少算的报告比没有报告更糟。这里因此直接用 jacoco 的分析器：
 * 把四份 {@code jacoco.exec} 并进一个 {@link ExecutionDataStore}，
 * 再逐个模块的 {@code target/classes} 分析。**模块清单是扫出来的，不是列出来的**，
 * 所以「某个模块悄悄掉出统计」这件事在结构上不会发生，不需要再靠一条断言去猜。
 *
 * <p>为什么单独跑在 verify 阶段（见 {@code airouter-app/pom.xml}）：它要判的数字里
 * 包含**本模块自己的**测试，而探针数据要等测试 JVM 退出才落盘。在 test 阶段跑它，
 * 读到的是上一次构建留下的数据 —— 一个永远绿、且绿得没有依据的结果。
 */
@Tag("architecture")
final class CoverageFloorTest {

  /** 下限。0.60 来自 testing.md 第二节，不是这里挑的数字。 */
  private static final double FLOOR = 0.60;

  private static final String CLASSES = "target/classes";

  private static final String EXEC = "target/jacoco.exec";

  @Test
  @DisplayName("全项目行覆盖率不低于 60%")
  void lineCoverageIsAtLeastTheFloor() {
    List<ModuleCoverage> modules = measured(executionData());

    int missed = modules.stream().mapToInt(ModuleCoverage::missed).sum();
    int covered = modules.stream().mapToInt(ModuleCoverage::covered).sum();
    double ratio = (double) covered / (covered + missed);

    System.out.println("行覆盖率 " + format(ratio)
        + "（" + covered + "/" + (covered + missed) + "）—— 分模块：" + describe(modules));

    assertThat(ratio)
        .as("全项目行覆盖率 %s（%d / %d 行），下限 %.0f%%。分模块：%s%n"
                + "覆盖率破了要补的是那条链路，不是给 getter 补断言（testing.md 第二节）",
            format(ratio), covered, covered + missed, FLOOR * 100, describe(modules))
        .isGreaterThanOrEqualTo(FLOOR);
  }

  /**
   * 统计本身没失效。
   *
   * <p>覆盖率门禁最危险的失效形态不是红灯，是**一个偏高且稳定的数字**：
   * 只要有一个模块掉出统计，分子分母同时变小，剩下的模块照样给出好看的比值。
   * 这里把两个前提变成断言：模块清单扫得到，且每个有代码的模块都真的跑过测试。
   */
  @Test
  @DisplayName("统计没有失效：扫得到模块，且每个有代码的模块都跑过测试")
  void theMeasurementItselfIsSound() {
    List<Path> modules = moduleDirectories();

    assertThat(modules)
        .as("一个模块都没扫到，说明扫描条件已经失效 —— 覆盖率那条断言会因此变成空跑")
        .isNotEmpty();

    List<String> neverRan = modules.stream()
        .filter(CoverageFloorTest::hasCompiledCode)
        .filter(module -> !Files.isRegularFile(module.resolve(EXEC)))
        .map(CoverageFloorTest::name)
        .toList();

    assertThat(neverRan)
        .as("这些模块有代码却没有探针数据：它们的测试一次都没跑过。"
            + "那不是「覆盖率 0%」，是「这个模块根本没进过这次构建」")
        .isEmpty();

    List<ModuleCoverage> measured = measured(executionData());
    assertThat(measured).as("一个有代码的模块都没统计到，覆盖率那条断言是空跑").isNotEmpty();
    assertThat(measured.stream().mapToInt(module -> module.missed() + module.covered()).sum())
        .as("统计到的可执行行数是 0，分析的类目录或探针数据已经失效")
        .isPositive();
  }

  /** 各模块逐个数出来，便于红灯时看出是哪一块掉下去 —— 判的仍然是合起来那个数。 */
  private static List<ModuleCoverage> measured(ExecutionDataStore data) {
    return moduleDirectories().stream()
        .filter(CoverageFloorTest::hasCompiledCode)
        .map(module -> measure(module, data))
        .toList();
  }

  private static ModuleCoverage measure(Path module, ExecutionDataStore data) {
    CoverageBuilder builder = new CoverageBuilder();
    try {
      new Analyzer(data, builder).analyzeAll(module.resolve(CLASSES).toFile());
    } catch (IOException failure) {
      throw new UncheckedIOException("分析不了编译产物：" + module, failure);
    }

    int missed = 0;
    int covered = 0;
    for (IClassCoverage type : builder.getClasses()) {
      ICounter lines = type.getLineCounter();
      missed += lines.getMissedCount();
      covered += lines.getCoveredCount();
    }
    return new ModuleCoverage(name(module), missed, covered);
  }

  /**
   * 四个模块的探针数据并成一份。
   *
   * <p>每个模块各自只留得下「自己这轮测试跑过什么」，而 gateway 的代码主要是
   * 在 app 那一轮里被跑到的 —— 不并起来，那份覆盖就永远找不回来。
   */
  private static ExecutionDataStore executionData() {
    ExecutionDataStore data = new ExecutionDataStore();
    for (Path file : executionDataFiles()) {
      try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
        ExecutionDataReader reader = new ExecutionDataReader(in);
        reader.setSessionInfoVisitor(new SessionInfoStore());
        reader.setExecutionDataVisitor(data);
        while (reader.read()) {
          continue;
        }
      } catch (IOException failure) {
        throw new UncheckedIOException("读不到探针数据：" + file, failure);
      }
    }
    return data;
  }

  private static List<Path> executionDataFiles() {
    return moduleDirectories().stream()
        .map(module -> module.resolve(EXEC))
        .filter(Files::isRegularFile)
        .toList();
  }

  /**
   * 模块目录：仓库根下带 {@code pom.xml} 的 {@code airouter-*}。
   * 按目录形状认而不是列一份名单 —— 加一个模块就自动进分母，不需要有人记得改这里。
   */
  private static List<Path> moduleDirectories() {
    Path root = repositoryRoot();
    try (Stream<Path> children = Files.list(root)) {
      return children
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith("airouter-"))
          .filter(dir -> Files.isRegularFile(dir.resolve("pom.xml")))
          .sorted()
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("列不出模块目录：" + root, failure);
    }
  }

  /**
   * 这个模块有没有真实代码。{@code package-info.java} 也会编译出一个 class，
   * 但它一行可执行代码都没有 —— 把它算进分母，等于让「写了文档的包」拉低覆盖率。
   */
  private static boolean hasCompiledCode(Path module) {
    File classes = module.resolve(CLASSES).toFile();
    if (!classes.isDirectory()) {
      return false;
    }
    try (Stream<Path> tree = Files.walk(classes.toPath())) {
      return tree
          .filter(path -> path.getFileName().toString().endsWith(".class"))
          .anyMatch(path -> !path.getFileName().toString().equals("package-info.class"));
    } catch (IOException failure) {
      throw new UncheckedIOException("扫不到编译产物：" + classes, failure);
    }
  }

  /**
   * 模块名。用 {@link File#getName()} 而不是 {@link Path#getFileName()}：
   * 后者在路径没有名字元素时返回 null，而这里要的是一个永远有值的显示名。
   */
  private static String name(Path module) {
    return module.toFile().getName();
  }

  private static String describe(List<ModuleCoverage> modules) {
    return modules.stream()
        .map(module -> module.module() + " " + format(module.ratio()))
        .collect(Collectors.joining("，"));
  }

  private static String format(double ratio) {
    return String.format("%.1f%%", ratio * 100);
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

  private record ModuleCoverage(String module, int missed, int covered) {

    private double ratio() {
      return (double) covered / (covered + missed);
    }
  }

  private CoverageFloorTest() {
  }
}
