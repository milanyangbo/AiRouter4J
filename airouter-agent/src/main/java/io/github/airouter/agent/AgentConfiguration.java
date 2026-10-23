package io.github.airouter.agent;

import org.springframework.context.annotation.Configuration;

/**
 * Agent 层的装配点。
 *
 * <p>此刻它是一个空壳，存在的唯一目的是让 {@code app} 有能力把它装配起来，
 * 从而证明这条装配链路是通的。编排逻辑（ChatClient、Advisor 链、Subagent）不在本模块这一阶段的范围里，
 * 见 ADR-0004。
 */
@Configuration
public class AgentConfiguration {
}
