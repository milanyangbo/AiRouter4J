package io.github.airouter.archtest.layout.violations.leak;

import org.apache.ibatis.annotations.Mapper;

/**
 * 违规：mapper 接口被放到了 {@code store} 之外。
 * MyBatis 是阻塞 API，出包之后「这一行会不会阻塞事件循环」只能靠读实现（R10）。
 */
@Mapper
public interface MapperInTheWrongPlace {

  int insert(String content);
}
