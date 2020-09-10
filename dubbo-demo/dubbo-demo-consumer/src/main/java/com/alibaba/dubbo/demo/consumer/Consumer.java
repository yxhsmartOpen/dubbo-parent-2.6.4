/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Consumer {

    public static void main(String[] args) {
        /** 防止获取IPV6地址，这种方式仅在调试模式下有效
         但是您可以传递使用 -Djava.net.preferIPv4Stack = true，那么无论是否在调试模式下它都可以正常工作 */
        System.setProperty("java.net.preferIPv4Stack", "true");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();
        // 获取远程服务代理
        DemoService demoService = (DemoService) context.getBean("demoService");

        while (true) {
            try {
                Thread.sleep(1000);
                // 调用远程方法
                String hello = demoService.sayHello("world");
                // 得到结果
                System.out.println(hello+System.currentTimeMillis());

            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }


        }

    }
}
