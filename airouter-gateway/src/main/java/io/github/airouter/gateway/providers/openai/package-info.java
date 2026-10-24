/**
 * OpenAI 兼容协议的上游实现（覆盖 DeepSeek / vLLM / Ollama / GPUStack 等同类端点）。
 *
 * <p>它只认识 Canonical 与 OpenAI 的 wire format，不认识 Face —— 方向由结构规则 R3b 判红。
 * 之所以这里是「OpenAI 兼容」而不是「某一家」：这些端点的差别不足以让每一家各写一个 Provider，
 * 真正的差别被收在 per-instance 的配置里。
 */
package io.github.airouter.gateway.providers.openai;
