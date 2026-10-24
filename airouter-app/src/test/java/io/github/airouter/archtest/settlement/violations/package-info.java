/**
 * 结算相关规则的违规样例（R4 的状态码那一半、R9）。
 *
 * <p>它们单独一棵子树，是为了让「这两条规则真的会红」能被各自单独断言 ——
 * 放在 {@code layout.violations} 里的话，样例会被别的违规一起判出来，
 * 于是某一条规则悄悄失效时，「规则会红」仍然是绿的。
 */
package io.github.airouter.archtest.settlement.violations;
