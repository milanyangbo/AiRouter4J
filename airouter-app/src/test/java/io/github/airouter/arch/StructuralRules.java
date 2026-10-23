package io.github.airouter.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;
import org.springframework.beans.factory.BeanFactory;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 结构约束的唯一定义处。总共八条，刻意不再增加 —— 规则越多，越容易走到「加个例外算了」那条路上，
 * 而来源项目那份 186 行抑制文件就是那条路的终点（ADR-0003 第 3 节）。
 *
 * <table>
 *   <caption>八条规则与它们各自保护的东西</caption>
 *   <tr><td>R1</td><td>core 的依赖树里没有框架</td><td>Maven enforcer，见 airouter-core/pom.xml</td></tr>
 *   <tr><td>R2</td><td>没有模块依赖 app</td><td>{@link #nothingDependsOnApp}</td></tr>
 *   <tr><td>R3</td><td>faces 与 providers 互不感知</td><td>{@link #facesDoNotDependOnProviders} / {@link #providersDoNotDependOnFaces}</td></tr>
 *   <tr><td>R4</td><td>routing 与 governance 不碰协议与 IO</td><td>{@link #routingAndGovernanceDoNoIo}</td></tr>
 *   <tr><td>R5</td><td>transport 不知道业务包</td><td>{@link #transportKnowsNoBusiness}</td></tr>
 *   <tr><td>R6</td><td>禁止 @Lazy</td><td>{@link #noLazyWiring}</td></tr>
 *   <tr><td>R7</td><td>禁止字段注入与 setter 注入</td><td>{@link #noFieldOrSetterInjection}</td></tr>
 *   <tr><td>R8</td><td>禁止按类型 getBean</td><td>{@link #noBeanLookupByType}</td></tr>
 * </table>
 *
 * <p>R3 到 R5 以包根为参数：同一组规则既作用在真实模块上，也作用在测试样例上，
 * 这样「规则真的会红」这件事本身是被断言过的，而不是靠人记得去试一次。
 */
public final class StructuralRules {

  /** 生产代码的包根。 */
  public static final String ROOT = "io.github.airouter";

  private static final String LAZY = "org.springframework.context.annotation.Lazy";
  private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
  private static final String INJECT = "jakarta.inject.Inject";
  private static final String RESOURCE = "jakarta.annotation.Resource";

  private StructuralRules() {
  }

  /** 四个模块里的生产类。测试样例（{@code io.github.airouter.archtest}）不在这个范围内。 */
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
   * <p>这是 ADR-0003 列出的第一条正面后果，也是后面「第二个 provider」那一票要断言的东西。
   * 一旦 faces 知道 providers，加一个 provider 就得回来改 faces，这条后果立刻失效。
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
   * 没有网络的情况下被单测，而延迟感知负载均衡与熔断恰恰是最需要被单测的部分（ADR-0007）。
   */
  public static ArchRule routingAndGovernanceDoNoIo(String root) {
    return noClasses()
        .that(resideInAPackage(root + ".routing..").or(resideInAPackage(root + ".governance..")))
        .should().dependOnClassesThat(resideInAPackage(root + ".faces..")
            .or(resideInAPackage(root + ".providers.."))
            .or(resideInAPackage(root + ".transport.."))
            .or(resideInAPackage(root + ".config..")))
        .because("实例选择与治理只操作 Canonical 与抽象状态，做 IO 就没法脱离容器单测");
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
   */
  public static ArchRule noLazyWiring(DescribedPredicate<JavaClass> scope) {
    return CompositeArchRule.of(List.of(
        noClasses().that(scope).should().beAnnotatedWith(LAZY),
        noFields().that(JavaMember.Predicates.declaredIn(scope)).should().beAnnotatedWith(LAZY),
        noMethods().that(JavaMember.Predicates.declaredIn(scope)).should().beAnnotatedWith(LAZY)))
        .as("禁止 @Lazy：它不消除循环依赖，只把发现它的时刻从启动期推迟到首次调用");
  }

  /**
   * R7 —— 保护「对象不会以半成品状态被发布」。
   *
   * <p>构造函数注入是唯一允许的方式。字段注入让依赖可以在对象构造完成后才被塞进来，
   * setter 注入同理；两者都会使「这个类需要什么」从签名里消失（ADR-0003 第 4 节）。
   */
  public static ArchRule noFieldOrSetterInjection(DescribedPredicate<JavaClass> scope) {
    return CompositeArchRule.of(List.of(
        noFields().that(JavaMember.Predicates.declaredIn(scope))
            .should().beAnnotatedWith(AUTOWIRED)
            .orShould().beAnnotatedWith(INJECT)
            .orShould().beAnnotatedWith(RESOURCE),
        noMethods().that(JavaMember.Predicates.declaredIn(scope))
            .should().beAnnotatedWith(AUTOWIRED)
            .orShould().beAnnotatedWith(INJECT)
            .orShould().beAnnotatedWith(RESOURCE)))
        .as("禁止字段注入与 setter 注入：它们允许对象在半成品状态下被发布");
  }

  /**
   * R8 —— 保护「依赖关系写在类型签名里」。
   *
   * <p>按类型 {@code getBean} 之后，一个类用了什么只有运行到那一行才知道，
   * 静态分析、单测装配、依赖方向检查全部失效（ADR-0003 第 4 节）。
   */
  public static ArchRule noBeanLookupByType(DescribedPredicate<JavaClass> scope) {
    return noClasses().that(scope)
        .should().callMethod(BeanFactory.class, "getBean", Class.class)
        .orShould().callMethod(BeanFactory.class, "getBean", String.class, Class.class)
        .because("按类型 getBean 让依赖从类型签名里消失，只能运行到那一行才发现");
  }
}
