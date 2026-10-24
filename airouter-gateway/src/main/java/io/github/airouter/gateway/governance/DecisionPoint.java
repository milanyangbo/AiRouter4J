package io.github.airouter.gateway.governance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「这是一个治理决策点」。
 *
 * <p>它存在的唯一目的是让「决策点有且仅有一个」可以被机器判定。
 * 来源项目的形态是限流在过滤器里判一次、在客户端里又判一次，
 * 两次判定用的还是不同的额度来源 —— 少了这个标记，第二次判定出现时没人会注意到。
 *
 * <p>标记本身不做任何事：它不参与运行，只在构建时被数一遍。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DecisionPoint {
}
