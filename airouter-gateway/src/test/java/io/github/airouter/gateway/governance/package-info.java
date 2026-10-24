/**
 * 熔断器状态机的用例。
 *
 * <p>它们不经过容器也不经过网络：这一层的东西本来就只操作抽象状态，
 * 拉进 Spring 反而会让「冷却是否结束」只能靠真的等一段来验证。
 */
package io.github.airouter.gateway.governance;
