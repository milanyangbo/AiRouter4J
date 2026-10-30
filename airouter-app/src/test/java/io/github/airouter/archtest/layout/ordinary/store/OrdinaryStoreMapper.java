package io.github.airouter.archtest.layout.ordinary.store;

import org.apache.ibatis.annotations.Mapper;

/** 普通写法：mapper 接口是包私有的，待在 {@code store} 包里，SQL 在它的 XML 里。 */
@Mapper
interface OrdinaryStoreMapper {

  int insert(String content);
}
