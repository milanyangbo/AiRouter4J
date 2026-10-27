package io.github.airouter.core;

/**
 * 一个可被调用的工具声明。
 *
 * @param name 工具名
 * @param description 给模型看的描述，可能是空串而不是 null
 * @param jsonSchema 参数的 JSON Schema 原文。网关不解释它的内容，只是把它从一侧搬到另一侧
 */
public record CanonicalTool(String name, String description, String jsonSchema) {

  /** 两个文本字段缺值时统一成空串：下游不必判断 null。 */
  public CanonicalTool {
    description = description == null ? "" : description;
    jsonSchema = jsonSchema == null ? "{}" : jsonSchema;
  }
}
