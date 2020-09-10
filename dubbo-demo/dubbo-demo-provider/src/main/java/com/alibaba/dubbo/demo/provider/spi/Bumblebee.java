package com.alibaba.dubbo.demo.provider.spi;

/**
 * 实现类
 * @author Baron
 * @date 2020/9/8 21:46
 */
public class Bumblebee implements Robot {
    @Override
    public void sayHello() {
        System.out.println("Hello, I am Bumblebee.");
    }
}
