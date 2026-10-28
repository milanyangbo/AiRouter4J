package io.github.airouter.gateway.select;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 把一个字符串算成一个 64 位数：**同一个字符串在任何进程里都得到同一个值**。
 *
 * <p>它给两个 hash 策略用，而这两个策略都用这个值做同一类事 ——
 * 把一个「键」（调用方标识，或环上的一个虚节点名）落到数轴上的一点。
 * <p>这个值因此必须是稳定的：不随进程重启而变，也不随 JDK 版本而变。
 *
 * <p>为什么不用 {@link String#hashCode()}：它是被**规定**的（因此在同一个 JDK 上确实稳定），
 * 但它的分布对一个调用方标识、一个 IP 这类短字符串很差 ——
 * 相邻的字符串产生相邻的值，而一致性哈希恰恰要求「不相干的两个键落在不相干的位置」。
 * 分布差的直接后果是：权重 3:1 的两个实例，实际拿到的键比例不是 3:1。
 *
 * <p>取前 8 个字节拼成一个 long，而不是留着整份摘要：落点要的是一个可比较的数，
 * 不是一份 MessageDigest。碰撞在这个量级（几万个键、几万个节点）上可以不考虑。
 */
final class StableHash {

  private StableHash() {
  }

  /**
   * @param text 要算的东西，通常是实例标识 + 虚拟节点序号
   * @return 一个数轴上的位置，可能为负
   */
  static long of(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8));
      long value = 0L;
      for (int index = 0; index < Long.BYTES; index++) {
        value = (value << Byte.SIZE) | (digest[index] & 0xFFL);
      }
      return value;
    } catch (NoSuchAlgorithmException absent) {
      throw new IllegalStateException("SHA-256 不可用：它属于每个 JDK 都必须提供的摘要算法", absent);
    }
  }
}
