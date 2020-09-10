package com.baron.spi;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.demo.provider.spi.Robot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * @author Baron
 * @date 2020/9/8 21:37
 */
public class MyTest {

    /**
     * 测试 Java SPI 机制
     * 第一步：声明一个扩展点的接口类{@link com.alibaba.dubbo.demo.provider.spi.Robot}
     * 第二步：编写实现类{@link com.alibaba.dubbo.demo.provider.spi.Bumblebee} 和 {@link com.alibaba.dubbo.demo.provider.spi.OptimusPrime}
     * 第三步：在META-INF/services 目录下 创建 com.alibaba.dubbo.demo.provider.spi.Robot 文件，内容为
     *        com.alibaba.dubbo.demo.provider.spi.OptimusPrime
     *        com.alibaba.dubbo.demo.provider.spi.Bumblebee
     * 第四步：编写当前测试类
     * @throws Exception
     */
    @Test
    public void testJavaSPI() throws Exception {
        ServiceLoader<Robot> serviceLoader = ServiceLoader.load(Robot.class);
        System.out.println("Java SPI");
        serviceLoader.forEach(Robot::sayHello);
    }

    /**
     * 测试 Dubbo SPI 机制
     * 第一步：声明一个扩展点(SPI)的接口类 {@link com.alibaba.dubbo.demo.provider.spi.Robot}
     * 第二步：编写实现类{@link com.alibaba.dubbo.demo.provider.spi.Bumblebee} 和 {@link com.alibaba.dubbo.demo.provider.spi.OptimusPrime}
     * 第三步：在 META-INF/dubbo 目录下 创建 com.alibaba.dubbo.demo.provider.spi.Robot 文件，内容为
     *         bumblebee = com.alibaba.dubbo.demo.provider.spi.Bumblebee
     *         optimusPrime = com.alibaba.dubbo.demo.provider.spi.OptimusPrime
     * 第四步：编写当前测试类
     * @throws Exception
     */
    @Test
    public void testDubboSPI() throws Exception {
        ExtensionLoader<Robot> extensionLoader = ExtensionLoader.getExtensionLoader(Robot.class);
        Robot optimusPrime = extensionLoader.getExtension("optimusPrime");
        optimusPrime.sayHello();
        Robot bumblebee = extensionLoader.getExtension("bumblebee");
        bumblebee.sayHello();
    }

    @Test
    public void test(){
        int urlTypeIndex = 1;
        StringBuilder code = null;
        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                urlTypeIndex);
        System.out.println(s);

        s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
        System.out.println(s);

    }
}
