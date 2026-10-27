package io.github.airouter.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 文档里引用的**票号必须有对应的票文件**。
 *
 * <p>它守的是 AGENTS.md「先有票，再动手」那一条的**凭据**：票号是「这件事有没有被授权」的判据，
 * 而一个指向不存在票号的引用，等于拿一个不存在的编号给自己授权。
 * 这件事真发生过（2026-10-27）：顺着 `07` 往下编出「票 08 / 票 09」两个号，各写了一次提交并推送，
 * 而 `.scratch/first-request/issues/` 里根本没有这两个文件。
 * 拦它的本该是一条规则，规则写在 AGENTS.md 里 —— 规则没拦住，因为它在实现阶段才被读到，
 * 而实现阶段的上下文压力最大。**能被读到的规则不是检查，检查是会被跑的东西。**
 *
 * <p>扫描范围是有意划定的：
 *
 * <ul>
 *   <li>**扫**：仓库根的 `*.md`、`docs/**`（`docs/adr/archive/` 除外）、`.scratch/**`。
 *       这是「票号会被引用」的全部地方
 *   <li>**不扫** `docs/adr/archive/` 与 `legacy/`：那里的票号属于**已经被取代的旧编号体系**
 *       （归档 ADR 里出现过 `票 27`，它在现行体系里从未存在）。把它们算进来只会让这条断言
 *       变成「去改历史文档」，那不是它要守的事
 *   <li>**不扫** `.workbuddy/`：那是工作笔记，里面**必须**能写「票 08 已被回退」这种话 ——
 *       让笔记不敢记历史，代价大于收益
 * </ul>
 *
 * <p>不扫提交信息：本仓库没有可读的提交门禁（`core.hooksPath` 指向机器级的 `~/.hookman/`），
 * 而测试进程不该去猜 git 的状态。提交那一侧靠 review 阶段核对，见
 * `docs/agents/issue-tracker.md`。
 */
@Tag("architecture")
final class TicketReferencesExistTest {

  /** 票文件的形状：`.scratch/<slug>/issues/<NN>-<slug>.md`。 */
  private static final Pattern TICKET_FILE = Pattern.compile("(\\d+)-.+\\.md");

  /** 正文里的引用形状。接受 `票 07` / `票07` / `票 7`，不接受范围写法（如 `票 01–09`）。 */
  private static final Pattern TICKET_REFERENCE = Pattern.compile("票\\s*(\\d+)");

  private static final List<String> EXCLUDED_DIRS = List.of("archive", "legacy", ".workbuddy");

  @Test
  @DisplayName("文档里引用的每个票号都有对应的票文件")
  void everyReferencedTicketNumberHasAFile() {
    Set<String> existing = ticketFilesByNumber().keySet();

    assertThat(existing)
        .as("一张票文件都没扫到：扫描条件已经失效，下面那条断言会变成空跑")
        .isNotEmpty();

    List<String> dangling = new ArrayList<>();
    for (Path file : scannedFiles()) {
      String name = repositoryRoot().relativize(file).toString();
      List<String> lines = readLines(file);
      for (int i = 0; i < lines.size(); i++) {
        Matcher matcher = TICKET_REFERENCE.matcher(lines.get(i));
        while (matcher.find()) {
          String number = normalise(matcher.group(1));
          if (!existing.contains(number)) {
            dangling.add(name + ":" + (i + 1) + " 引用了 票 " + number + "，但没有 " + number + "-*.md");
          }
        }
      }
    }

    assertThat(dangling)
        .as("""
            有票号指向不存在的票文件。票号是「有没有授权」的判据 —— \
            指向一个不存在的号，等于拿不存在的票给自己授权（AGENTS.md「先有票，再动手」）。\
            要么补那张票文件，要么把引用改成它真正的去处（ADR-0020 的阶段名也是合法去处）。
            已存在的票号：%s""",
            existing)
        .isEmpty();
  }

  /**
   * 同一个票号不能有两张票。
   *
   * <p>它守的是「票号能不能当凭据」这件事本身：`.scratch/<slug>/` 按功能分目录，
   * 而**编号是全仓库一份序列**（见 {@code docs/agents/issue-tracker.md}）。
   * 一旦允许每个 slug 都从 `01` 开始，「票 01」就同时指向两张票 ——
   * 上面那条「引用有对应文件」的断言照样绿，而它已经回答不了「指的是哪一张」。
   * 一个含糊的凭据不是凭据。
   */
  @Test
  @DisplayName("票号跨 slug 唯一：同一个号只有一张票")
  void ticketNumbersAreUniqueAcrossSlugs() {
    Map<String, List<String>> files = ticketFilesByNumber();

    List<String> duplicated = new ArrayList<>();
    files.forEach((number, names) -> {
      if (names.size() > 1) {
        duplicated.add(number + " 有 " + names.size() + " 张票：" + String.join("、", names));
      }
    });

    assertThat(duplicated)
        .as("""
            同一个票号出现在多个 slug 里。票号是「这件事有没有被授权」的判据，\
            一个号对应两张票时它指不出是哪一张 —— 编号规则见 \
            docs/agents/issue-tracker.md：票号是全仓库一份序列，新 slug 从当前最大号 + 1 开始。\
            已有的票文件：%s""",
            files)
        .isEmpty();
  }

  @Test
  @DisplayName("票号连续：01 到最大号之间没有缺号")
  void ticketNumbersHaveNoGaps() {
    Map<String, List<String>> files = ticketFilesByNumber();
    Set<String> numbers = files.keySet();
    int highest = numbers.stream().mapToInt(Integer::parseInt).max().orElseThrow();

    List<String> missing = new ArrayList<>();
    for (int i = 1; i <= highest; i++) {
      String number = String.format("%02d", i);
      if (!numbers.contains(number)) {
        missing.add(number);
      }
    }

    assertThat(missing)
        .as("""
            票号缺号：最大号是 %02d，中间没有文件的是 %s。缺号会让人以为有那张票 —— \
            而编号的意义正是「编号取自真实存在的文件」（AGENTS.md「先有票，再动手」）。\
            已有的票文件：%s""",
            highest, missing, files)
        .isEmpty();
  }

  /** 票号 → 文件名（可能不止一个）。扫目录而不是列名单：新增一张票自动进入判据，不需要有人记得改这里。 */
  private static Map<String, List<String>> ticketFilesByNumber() {
    Map<String, List<String>> found = new LinkedHashMap<>();
    Path issuesRoot = repositoryRoot().resolve(".scratch");
    if (!Files.isDirectory(issuesRoot)) {
      return found;
    }
    try (Stream<Path> slugs = Files.list(issuesRoot)) {
      List<Path> issueDirs = slugs
          .filter(Files::isDirectory)
          .map(slug -> slug.resolve("issues"))
          .filter(Files::isDirectory)
          .toList();
      for (Path dir : issueDirs) {
        try (Stream<Path> files = Files.list(dir)) {
          files.filter(Files::isRegularFile)
              .map(file -> file.getFileName().toString())
              .map(TICKET_FILE::matcher)
              .filter(Matcher::matches)
              .forEach(matcher -> found
                  .computeIfAbsent(normalise(matcher.group(1)), key -> new ArrayList<>())
                  .add(matcher.group()));
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("列不出票目录：" + issuesRoot, failure);
    }
    return found;
  }

  /** 待扫的 markdown：根上的几份 + `docs/**` + `.scratch/**`，排除归档 / legacy / 笔记。 */
  private static List<Path> scannedFiles() {
    Path root = repositoryRoot();
    List<Path> found = new ArrayList<>();
    for (String anchor : List.of("docs", ".scratch")) {
      Path dir = root.resolve(anchor);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> tree = Files.walk(dir)) {
        tree.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".md"))
            .filter(TicketReferencesExistTest::notExcluded)
            .forEach(found::add);
      } catch (IOException failure) {
        throw new UncheckedIOException("扫不到 " + anchor, failure);
      }
    }
    try (Stream<Path> rootFiles = Files.list(root)) {
      rootFiles
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".md"))
          .forEach(found::add);
    } catch (IOException failure) {
      throw new UncheckedIOException("列不出仓库根的文件：" + root, failure);
    }
    return found;
  }

  /** 归档与 legacy 里的票号属于被取代的旧编号体系，不算数（理由见本类 javadoc）。 */
  private static boolean notExcluded(Path file) {
    Set<String> segments = new TreeSet<>();
    for (Path part : file) {
      segments.add(part.toString());
    }
    return EXCLUDED_DIRS.stream().noneMatch(segments::contains);
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException failure) {
      throw new UncheckedIOException("读不了 " + file, failure);
    }
  }

  /** `7` 与 `07` 是同一个号：票文件名永远是两位，正文里手写常常只写一位。 */
  private static String normalise(String digits) {
    return String.format("%02d", Integer.parseInt(digits));
  }

  /** 从当前工作目录向上找，直到某个父目录里有 `.git`。与 `CoverageFloorTest` 同一套办法。 */
  private static Path repositoryRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("应当能从工作目录向上找到仓库根（含 .git 的那一层）").isNotNull();
    return dir;
  }

  private TicketReferencesExistTest() {
  }
}
