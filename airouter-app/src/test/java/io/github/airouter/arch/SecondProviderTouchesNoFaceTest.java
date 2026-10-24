package io.github.airouter.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 第二个 Provider 的改动范围：**Face 侧与 {@code core} 一个文件都没碰**。
 *
 * <p>这是 ADR-0005「新增 Provider 不需要改动 Face」从声明变成事实的那一步。
 * 声明与事实的差别在于前者无法被推翻：它写在文档里，永远不会红 ——
 * 来源项目里堆着的正是这一类句子。这里用差异统计把它变成一条会红的断言：
 * 谁往 Face 或 {@code core} 里加了一行，这条就红，而且红的时候会说清是哪一个文件。
 *
 * <p>基线是 {@code baseline-pre-09}（第二个 Provider 开始之前的那个提交）。
 * 比较的是**工作区与基线**，不是两个提交之间 —— 后者在本票的改动提交之前会给出空结果，
 * 于是这条断言会在最需要它的那段时间里一直绿着。
 *
 * <p>两条防假绿的断言是刻意加的：{@code git} 不可用、标签被删、路径算错，
 * 三者都表现为「没有文件被改动」，也就是全绿。一个在前提失效时仍然全绿的断言，
 * 与没有它是同一件事。
 */
@Tag("architecture")
final class SecondProviderTouchesNoFaceTest {

  /** 第二个 Provider 开始之前的基线。 */
  private static final String BASELINE = "baseline-pre-09";

  private static final String CORE_MODULE = "airouter-core/";

  private static final String FACES_PACKAGE = "/faces/";

  /** 第一个 Provider 的非流式契约基线目录。 */
  private static final String FIRST_PROVIDER_WIRE_BASELINES = "airouter-app/src/test/resources/contract/";

  /** 第一个 Provider 的流式契约基线目录。 */
  private static final String FIRST_PROVIDER_STREAM_BASELINES = "airouter-app/src/test/resources/contract-stream/";

  @Test
  @DisplayName("自基线以来，没有任何 Face 侧或 core 的文件被改动")
  void noFaceOrCoreFileIsTouched() {
    List<String> offenders = changedFiles().stream()
        .filter(SecondProviderTouchesNoFaceTest::isFaceOrCore)
        .toList();

    assertThat(offenders)
        .as("第二个 Provider 的改动越界了：这些文件属于 Face 或 core，加一个 Provider 不该碰到它们")
        .isEmpty();
  }

  @Test
  @DisplayName("第一个 Provider 的全部 golden 基线保持原样，一个字节都没改")
  void firstProviderBaselinesAreUntouched() {
    List<String> offenders = changedFiles().stream()
        .filter(SecondProviderTouchesNoFaceTest::isFirstProviderBaseline)
        .toList();

    assertThat(offenders)
        .as("第一个 Provider 的契约基线被改了：新增形态的正确做法是加一组新基线，不是改旧的")
        .isEmpty();
  }

  /** 防「git 用不了 / 标签没了，于是没有文件被改动，于是全绿」。 */
  @Test
  @DisplayName("基线标签存在，且这一票确实改动了文件 —— 上面的断言不是因为算错了才通过的")
  void theComparisonActuallyHasSomethingToCompare() {
    assertThat(git("rev-parse", "--verify", BASELINE))
        .as("基线标签 " + BASELINE + " 不存在：差异统计无从谈起")
        .isNotEmpty();

    assertThat(changedFiles())
        .as("与基线相比没有任何改动：差异统计是不是算错了？")
        .isNotEmpty();
  }

  private static boolean isFaceOrCore(String path) {
    return path.startsWith(CORE_MODULE) || path.contains(FACES_PACKAGE);
  }

  /**
   * 只有参与比对的那两个文件算基线。
   *
   * <p>不包括目录里的 {@code README.md}：它写的是存放约定，改它是文档维护而不是改基线。
   * 把它也算进来，第一次在那一节里补一句说明就会红 —— 而那条红没有任何信息，
   * 只会让人习惯性地去放宽断言，那正是这类断言失效的方式。
   */
  private static boolean isFirstProviderBaseline(String path) {
    return path.endsWith(".json")
        && (path.startsWith(FIRST_PROVIDER_WIRE_BASELINES)
            || path.startsWith(FIRST_PROVIDER_STREAM_BASELINES));
  }

  private static List<String> changedFiles() {
    return git("status", "--porcelain").stream()
        .map(line -> line.length() > 3 ? line.substring(3) : line)
        // 重命名在 porcelain 里是两行（旧路径与新路径），都算被改动。
        .map(line -> line.contains(" -> ") ? line.substring(line.indexOf(" -> ") + 4) : line)
        .toList();
  }

  /**
   * 跑一条 git 命令并取它的输出。
   *
   * <p>用 {@code -C} 显式给出仓库根而不是依赖当前目录：surefire 的工作目录是模块目录，
   * 而 {@code git status} 在那里看到的是同一个仓库却给出**相对模块**的路径 ——
   * 那样上面那些前缀判断会全部失配，而失配的表现是「没有越界文件」，也就是全绿。
   *
   * @param args git 的参数，不含 {@code git} 本身
   * @return 输出的非空行
   */
  private static List<String> git(String... args) {
    String[] command = new String[args.length + 3];
    command[0] = "git";
    command[1] = "-C";
    command[2] = repositoryRoot().toString();
    System.arraycopy(args, 0, command, 3, args.length);
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(
            "git " + String.join(" ", args) + " 失败：" + output);
      }
      return output.lines().filter(line -> !line.isBlank()).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("跑不了 git，差异统计无从谈起", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git 被中断", failure);
    }
  }

  /** 从当前目录向上找到带 {@code .git} 的那一层。 */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve(".git"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("找不到仓库根：当前目录不在 git 工作树里");
  }
}
