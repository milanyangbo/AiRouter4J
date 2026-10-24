/**
 * Ollama 原生协议的上游实现（{@code /api/chat}）。
 *
 * <p>它不是「OpenAI 兼容」的一种，而是另一种 wire 形态：逐行 JSON 而不是 SSE、
 * 生成参数在 {@code options} 里、用量是平铺的两个计数、没有流标识、没有结束哨兵。
 * 差异清单见 {@link io.github.airouter.gateway.providers.ollama.OllamaUpstreamCodec}。
 *
 * <p>这个包存在的意义超出「多支持一家端点」：它是 ADR-0005「新增 Provider 不需要改动 Face」
 * 的第一个实证。此前那条主张只写在文档里，而它属于那种无法被推翻的声明 ——
 * 只有真的接进第二种形态，且 Face 与 {@code core} 的差异统计为空，它才成为一个事实。
 */
package io.github.airouter.gateway.providers.ollama;
