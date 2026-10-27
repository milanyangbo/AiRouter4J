package io.github.airouter.gateway.providers;


/**
 * 一种形态对应的**怎么造一个 Provider**（ADR-0006 第 6 节第 4 项）。
 *
 * <p>它是一个单方法接口，由 {@link UpstreamProviderFactory} 按形态登记。
 * 把它做成接口而不是让工厂直接 switch 形态，是为了让「新增一种形态」是一次**登记**，
 * 而不是在已有方法里劈出一个分支。
 *
 * <p>入参一次性给全（{@link ProviderInputs}）而不是随着需求增长逐个加参数：
 * 签名一旦变成了七个 String，类型系统就再也分不清哪个是凭证、哪个是模型名。
 */
@FunctionalInterface
public interface ProviderConstructor {

  /**
   * @param inputs 这一次要造的东西，见 {@link ProviderInputs}
   * @return 这个实例的出站能力
   */
  UpstreamProvider create(ProviderInputs inputs);
}
