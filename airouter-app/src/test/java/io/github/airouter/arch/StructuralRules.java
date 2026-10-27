package io.github.airouter.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 结构约束的唯一定义处。九条，编号不重排，刻意不再增加 ——
 * 规则越多，越容易走到「加个例外算了」那条路上，
 * 而来源项目那份 186 行抑制文件就是那条路的终点（ADR-0003 第 3 节）。
 *
 * <p>表格里的 R1 由 Maven enforcer 判红（`airouter-core` 的依赖树里没有框架），
 * 其余八条由 ArchUnit 判红。
 *
 * <table>
 *   <caption>九条规则与它们各自保护的东西</caption>
 *   <tr><td>R1</td><td>core 的依赖树里没有框架</td><td>Maven enforcer（core/pom.xml）</td></tr>
 *   <tr><td>R2</td><td>没有模块依赖 app</td><td>{@link #nothingDependsOnApp}</td></tr>
 *   <tr><td>R3</td><td>faces 与 providers 互不感知</td>
 *       <td>{@link #facesDoNotDependOnProviders} / {@link #providersDoNotDependOnFaces}</td></tr>
 *   <tr><td>R4</td><td>routing 与 governance 不碰协议与 IO，也看不到 HTTP 状态码</td>
 *       <td>{@link #routingAndGovernanceDoNoIo}</td></tr>
 *   <tr><td>R5</td><td>transport 不知道业务包</td><td>{@link #transportKnowsNoBusiness}</td></tr>
 *   <tr><td>R6</td><td>禁止 @Lazy</td><td>{@link #noLazyWiring}</td></tr>
 *   <tr><td>R7</td><td>禁止字段注入与 setter 注入</td><td>{@link #noFieldOrSetterInjection}</td></tr>
 *   <tr><td>R8</td><td>禁止按类型 getBean</td><td>{@link #noBeanLookupByType}</td></tr>
 *   <tr><td>R9</td><td>成败的结论只有一个出口</td><td>{@link #settlementHasOneExit}</td></tr>
 * </table>
 *
 * <p>R2 到 R5 与 R9 以**包根为参数**：同一组规则既作用在真实模块上，也作用在测试样例上，
 * 这样「规则真的会红」这件事本身是被断言过的，而不是靠人记得去试一次（ADR-0003 第 3 节）。
 * 阶段 0 的生产包大多是占位包，靠的就是这一层：规则与守门先到，生产内容按需开放。
 */
public final class StructuralRules {

  /** 生产代码的包根。 */
  public static final String ROOT = "io.github.airouter";

  /** 结算结论的类型名。按名字认而不按类型认，是为了让规则在 core 落地之前就能守样例（见 R9）。 */
  private static final String OUTCOME = "Outcome";

  /** 「HTTP 状态码」的载体。治理侧一旦引用它们，就离「自己判断成败」只有一行代码。 */
  private static final DescribedPredicate<JavaClass> HTTP_STATUS_TYPES =
      JavaClass.Predicates.belongToAnyOf(HttpStatus.class, HttpStatusCode.class, HttpResponse.class);

  private static final String LAZY = "org.springframework.context.annotation.Lazy";
  private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
  private static final String INJECT = "jakarta.inject.Inject";
  private static final String RESOURCE = "jakarta.annotation.Resource";

  private StructuralRules() {
  }

  /** 四个模块里的生产类。测试样例（`io.github.airouter.archtest`）不在这个范围内。 */
  public static DescribedPredicate<JavaClass> productionClasses() {
    return resideInAPackage(ROOT + ".core..")
        .or(resideInAPackage(ROOT + ".gateway.."))
        .or(resideInAPackage(ROOT + ".agent.."))
        .or(resideInAPackage(ROOT + ".app.."));
  }

  /**
   * R2 —— 保护「装配顺序由类型签名决定」。
   *
   * <p>app 是装配终点。一旦别的模块可以依赖它，装配就不再是单向的：
   * 谁先起来变成一个只有运行期才知道的问题（ADR-0003 第 1 节的依赖方向图）。
   */
  public static ArchRule nothingDependsOnApp(String root) {
    return noClasses()
        .that(resideInAPackage(root + ".core..")
            .or(resideInAPackage(root + ".gateway.."))
            .or(resideInAPackage(root + ".agent..")))
        .should().dependOnClassesThat(resideInAPackage(root + ".app.."))
        .because("app 是装配终点；被别人依赖意味着装配方向不再单向");
  }

  /**
   * R3a —— 保护「新增一个 provider 不需要动 faces 里的任何代码」。
   *
   * <p>这是 ADR-0003 列出的第一条正面后果。一旦 faces 知道 providers，
   * 加一个 provider 就得回来改 faces，这条后果立刻失效。
   */
  public static ArchRule facesDoNotDependOnProviders(String root) {
    return noClasses().that(resideInAPackage(root + ".faces.."))
        .should().dependOnClassesThat(resideInAPackage(root + ".providers.."))
        .because("入站协议翻译一旦知道出站协议，新增 provider 就不再是一个包内的事");
  }

  /** R3b —— 同一个隔离的另一个方向：provider 也不该回头去用入站的编解码。 */
  public static ArchRule providersDoNotDependOnFaces(String root) {
    return noClasses().that(resideInAPackage(root + ".providers.."))
        .should().dependOnClassesThat(resideInAPackage(root + ".faces.."))
        .because("出站协议翻译不该知道是谁把请求送进来的");
  }

  /**
   * R4 —— 保护「routing 与 governance 是纯函数」。
   *
   * <p>这两块只操作 Canonical 与抽象状态。它们一旦碰协议或 IO，就再也没法在没有容器、
   * 没有网络的情况下被单测，而延迟感知负载均衡与熔断恰恰是最需要被单测的部分。
   *
   * <p>「看不到 HTTP 状态码」是同一条规则的另一半，而且是更要紧的那一半：
   * 治理组件拿得到状态码就一定会用它判断成败，而那样一来
   * 「同一个请求在熔断器看来失败、在指标看来成功」只是时间问题。
   */
  public static ArchRule routingAndGovernanceDoNoIo(String root) {
    return noClasses()
        .that(resideInAPackage(root + ".routing..").or(resideInAPackage(root + ".governance..")))
        .should().dependOnClassesThat(resideInAPackage(root + ".faces..")
            .or(resideInAPackage(root + ".providers.."))
            .or(resideInAPackage(root + ".transport.."))
            .or(resideInAPackage(root + ".config.."))
            .or(HTTP_STATUS_TYPES))
        .because("实例选择与治理只操作 Canonical 与抽象状态，做 IO 就没法脱离容器单测；"
            + "它们也不得自己读 HTTP 状态码判断成败 —— 结论只从 Outcome 来");
  }

  /**
   * R5 —— 保护「transport 只搬字节」。
   *
   * <p>出站管道负责连接、超时与 SSE 读取。它一旦知道路由或治理，
   * 「换一个出站实现」就会牵动业务代码，而这类耦合在运行期表现为很难复现的时序问题。
   */
  public static ArchRule transportKnowsNoBusiness(String root) {
    return noClasses().that(resideInAPackage(root + ".transport.."))
        .should().dependOnClassesThat(resideInAPackage(root + ".faces..")
            .or(resideInAPackage(root + ".providers.."))
            .or(resideInAPackage(root + ".routing.."))
            .or(resideInAPackage(root + ".governance.."))
            .or(resideInAPackage(root + ".config..")))
        .because("出站管道只搬字节；它一旦知道业务，换实现就会牵动业务代码");
  }

  /**
   * R6 —— 保护「装配问题是启动期问题，不是偶发问题」。
   *
   * <p>@Lazy 不改变依赖是否成环，只把发现它的时刻从启动推迟到首次调用。
   * 类型、字段、方法三个位置都查：字段上的 @Lazy 是最常见的写法。
   *
   * <p>三个位置在一个条件里查而不是分成三条规则，是因为分成三条之后，
   * 还没有字段与方法的那个阶段（本项目的阶段 0 正是如此）会有两条子规则空转，
   * 而 ArchUnit 对空转的规则是报错的 —— 要么把它静音，要么让规则只有一个判定对象。
   * 这里选后者：判定的对象始终是「类」，成员只是它的内容。
   */
  public static ArchRule noLazyWiring(DescribedPredicate<JavaClass> scope) {
    return classes().that(scope).should(noMemberAnnotatedWith(List.of(LAZY)))
        .as("禁止 @Lazy：它不消除循环依赖，只把发现它的时刻从启动期推迟到首次调用");
  }

  /**
   * R7 —— 保护「对象不会以半成品状态被发布」。
   *
   * <p>构造函数注入是唯一允许的方式。字段注入让依赖可以在对象构造完成后才被塞进来，
   * setter 注入同理；两者都会使「这个类需要什么」从签名里消失。
   */
  public static ArchRule noFieldOrSetterInjection(DescribedPredicate<JavaClass> scope) {
    return classes().that(scope)
        .should(noMemberAnnotatedWith(List.of(AUTOWIRED, INJECT, RESOURCE)))
        .as("禁止字段注入与 setter 注入：它们允许对象在半成品状态下被发布");
  }

  /**
   * R8 —— 保护「依赖关系写在类型签名里」。
   *
   * <p>按类型 {@code getBean} 之后，一个类用了什么只有运行到那一行才知道，
   * 静态分析、单测装配、依赖方向检查全部失效。
   */
  public static ArchRule noBeanLookupByType(DescribedPredicate<JavaClass> scope) {
    return noClasses().that(scope)
        .should().callMethod(BeanFactory.class, "getBean", Class.class)
        .orShould().callMethod(BeanFactory.class, "getBean", String.class, Class.class)
        .because("按类型 getBean 让依赖从类型签名里消失，只能运行到那一行才发现");
  }

  /**
   * R9 —— 保护「结算点唯一」。
   *
   * <p>一次调用是成功还是失败，只能由**产生终态的那一处**（{@code providers}）说出来。
   * 别处自己造一个 {@code Outcome}，就意味着那个组件有自己的成败口径，
   * 而两个口径迟早会在某一次故障上给出相反的结论。
   *
   * <p>限制的是「构造」而不是「使用」：消费 {@code Outcome} 的组件越多越好，
   * 它们不改变结论，只是各自拿它做自己的事。
   *
   * <p>按类型名 {@code Outcome} 认而不是按 {@code Class} 认，是阶段 0 的一个取舍：
   * core 的领域模型（阶段 1）落地之前，规则就已经要在测试样例上证明自己会红。
   * 代价是同名类型一律受限 —— 对一个网关来说这个代价为零。
   */
  public static ArchRule settlementHasOneExit(String root) {
    return noClasses()
        .that(resideInAPackage(root + "..")
            .and(DescribedPredicate.not(resideInAPackage(root + ".providers.."))))
        .should().callConstructorWhere(CONSTRUCTS_OUTCOME)
        .because("成败的结论只能由产生终态的那一处给出：别处自己造一个，结算点就不再是唯一的一个");
  }

  private static final DescribedPredicate<JavaConstructorCall> CONSTRUCTS_OUTCOME =
      DescribedPredicate.describe("构造 " + OUTCOME,
          call -> OUTCOME.equals(call.getTarget().getOwner().getSimpleName()));

  /**
   * 类自己、它的字段与它的方法都不带这些注解。
   *
   * <p>写成类级条件而不是「字段级 + 方法级」两条规则，理由见 {@link #noLazyWiring}：
   * 判定对象始终是类，空转的问题就不存在了。
   */
  private static ArchCondition<JavaClass> noMemberAnnotatedWith(List<String> annotations) {
    String listing = String.join(" / ", annotations);
    return new ArchCondition<>("在类型、字段与方法上都不带 " + listing) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (String annotation : annotations) {
          if (item.isAnnotatedWith(annotation)) {
            events.add(SimpleConditionEvent.violated(item, item.getName() + " 自己带着 " + annotation));
          }
          for (JavaField field : item.getFields()) {
            if (field.isAnnotatedWith(annotation)) {
              events.add(SimpleConditionEvent.violated(item,
                  item.getName() + " 的字段 " + field.getName() + " 带着 " + annotation));
            }
          }
          for (JavaMethod method : item.getMethods()) {
            if (method.isAnnotatedWith(annotation)) {
              events.add(SimpleConditionEvent.violated(item,
                  item.getName() + " 的方法 " + method.getName() + " 带着 " + annotation));
            }
          }
        }
      }
    };
  }
}
