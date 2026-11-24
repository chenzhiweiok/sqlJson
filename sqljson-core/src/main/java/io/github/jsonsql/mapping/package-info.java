/**
 * 结果映射：行 → 对象（{@link io.github.jsonsql.mapping.BeanMapper}），
 * 扁平 join → 主对象 + 子集合（{@link io.github.jsonsql.mapping.JoinAssembler}）。
 *
 * <p>只做两层，不生成代理、不做懒加载。
 */
package io.github.jsonsql.mapping;
