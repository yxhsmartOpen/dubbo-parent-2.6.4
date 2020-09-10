package com.alibaba.dubbo.demo.provider.spi;

import com.alibaba.dubbo.common.extension.SPI;

/**
 * 扩展点
 * @author Baron
 * @date 2020/9/8 21:44
 */
@SPI
public interface Robot {
    void sayHello();
}
