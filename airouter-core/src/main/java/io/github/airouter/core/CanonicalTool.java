package io.github.airouter.core;

/**
 * 一个可被调用的工具声明。
 *
 * @param name 工具名
 * @param description 给模型看的描述，可能为空串
 * @param jsonSchema 参数的 JSON Schema 原文。网关不解释它的内容，只是把它从一侧搬到另一侧
 */
public record CanonicalTool(String name, String description, String jsonSchema) {
}
