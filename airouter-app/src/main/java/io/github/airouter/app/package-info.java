/**
 * 启动与装配。
 *
 * <p>这是依赖方向的终点：app 认识所有人，没有人认识 app（由结构规则 R2 判红）。
 * 跨模块的结构规则也住在这个模块的测试里，因为只有它在 classpath 上看得到全部四个模块。
 */
package io.github.airouter.app;
