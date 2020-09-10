package com.alibaba.dubbo.demo.provider.spi;

import com.alibaba.dubbo.demo.provider.spi.Robot;

/**
 * 实现类
 * @author Baron
 * @date 2020/9/8 21:46
 */
public class OptimusPrime implements Robot {
    @Override
    public void sayHello() {
        System.out.println("Hello, I am Optimus Prime.");
    }
}
