/**
 * {@link io.github.airouter.gateway.store} 的测试。
 *
 * <p>放在同一个包里是为了能够构造那些**故意不对外**的类型：
 * {@code StoreAvailability} 与 {@code SchemaMigration} 都是包私有的，
 * 「库不在」这一档行为因此只能由同包的代码走一遍 ——
 * 这也顺带保证了测试走的是与生产装配同一条路径，不是另写一份替身。
 */
package io.github.airouter.gateway.store;
