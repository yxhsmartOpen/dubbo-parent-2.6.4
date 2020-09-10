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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 加载 dubbo扩展点，包括我们两部分，dubbo本身的扩展点，和我们自己实现的扩展点。
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * 服务目录
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    /**
     * dubbo 目录
     */
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    /**
     * dubbo 内部目录
     */
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    /**
     * 名称分隔器
     */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * 查看 ConcurrentMap源码得知，其本身提供线程安全性和原子性保证
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     * 扩展实例对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    /**
     * 扩展点类型
     */
    private final Class<?> type;

    /**
     * 扩展工程对象
     */
    private final ExtensionFactory objectFactory;

    /**
     * 缓存对象名称
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    /**
     * 缓存类
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    /**
     * 又是一个缓存对象 Map，key为字符串，value为目标支持对象 Holder
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    /**
     * Holder的翻译是 持有人 的意思
     * cachedAdaptiveInstance 这个对象按字面意思 “缓存适应性实例"
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    /**
     * Throwable是java.lang包中一个专门用来处理异常的类。它有两个子类，即Error 和Exception，它们分别用来处理两组异常。
     * createAdaptiveInstanceError 这个对象按字面意思是“创建适应性实例异常”
     */
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
        /** 通过一个三目运算符判断 传入的扩展点接口类是否和  ExtensionFactory.class 相等，相等的话为 null，否则 将 ExtensionFactory.class 作为参数传入ExtensionLoader.getExtensionLoader
         * 方法，为什么这里还会有一个 ExtensionLoader.getExtensionLoader(ExtensionFactory.class) 操作呢，个人觉得这一步必须要实现的，目的是要把 ExtensionFactory这个扩展点加入到一个key-value的缓存对象Map
         * 中*/
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    /**
     * 判断类对象是否声明了 @SPI 注解
     */
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 根据扩展点类型获取扩展加载类
     *
     * @param type 扩展点类型
     * @param <T>  泛型
     * @return 扩展加载类
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        /** 假如传入的type为我们自己创建的扩展点（SPI），为什么说是假如，因为传入这里的type有可能是duboo自己的扩展点{@link com.alibaba.dubbo.common.extension.ExtensionFactory}  对象
         * 则该语句的含义是扩展点（SPI）的类型必须为接口，否则会抛出如下异常 */
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        /** 检查扩展点（SPI）接口是否添加了注解{@link com.alibaba.dubbo.common.extension.SPI}，否则会会抛出如下异常 */
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        /** 根据扩展点接口类去缓存对象Map 中查询 */
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        /** 如果 扩展点加载类为 null */
        if (loader == null) {
            /** 把扩展点接口类作为key，将扩展点接口类作为参数，通过构造函数new 一个扩展点加载类 */
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * 根据名称获取扩展对象，如果名称为null直接抛出异常
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            // 获取默认的拓展实现类
            return getDefaultExtension();
        }
        // Holder，顾名思义，用于持有目标对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        /** 这里有事一个双重检查锁创建现场安全的单例对象 */
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 创建拓展实例
                    instance = createExtension(name);
                    // 设置实例到 holder 中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 按字面意思该方法的含义是获取一个自适应扩展点对象。
     * 这里用到了经典的双重检查锁来创建单例对象 instance
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 从缓存中获取自适应拓展
        Object instance = cachedAdaptiveInstance.get();
        /** 什么情况下 cachedAdaptiveInstance.get() 会为null 呢 */
        // 缓存未命中
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 创建适应性扩展对象
                            instance = createAdaptiveExtension();
                            // 将适应性扩展对象存放到缓存当中
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建扩展对象
     * <p>
     * createExtension 方法的逻辑稍复杂一下，包含了如下的步骤：
     * 1、通过 getExtensionClasses 获取所有的拓展类
     * 2、通过反射创建拓展对象
     * 3、向拓展对象中注入依赖
     * 4、将拓展对象包裹在相应的 Wrapper 对象中
     * 以上步骤中，第一个步骤是加载拓展类的关键，第三和第四个步骤是 Dubbo IOC 与 AOP 的具体实现。
     *
     * @param name 名称
     * @return 返回一个扩展对象
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        //1、 从配置文件中加载所有的拓展类，可得到“配置项名称”到“配置类”的映射关系表
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                //2、 通过反射创建实例
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            //3、 向实例中注入依赖（dubbo IOC 的实现）
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            //4、将拓展对象包裹在相应的 Wrapper 对象中（dubbo AOP 的实现）
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                // 循环创建 Wrapper 实例
                for (Class<?> wrapperClass : wrapperClasses) {
                    // 将当前 instance 作为参数传给 Wrapper 的构造方法，并通过反射创建 Wrapper 实例。
                    // 然后向 Wrapper 实例中注入依赖，最后将 Wrapper 实例再次赋值给 instance 变量
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * Dubbo IOC 是通过 setter 方法注入依赖。Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，
     * 检测方法名是否具有 setter 方法特征。若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。
     * <p>
     * objectFactory 变量的类型为 AdaptiveExtensionFactory，AdaptiveExtensionFactory 内部维护了一个 ExtensionFactory 列表，用于存
     * 储其他类型的 ExtensionFactory。Dubbo 目前提供了两种 ExtensionFactory，分别是 SpiExtensionFactory 和 SpringExtensionFactory。前
     * 者用于创建自适应的拓展，后者是用于从 Spring 的 IOC 容器中获取所需的拓展。
     * <p>
     * Dubbo IOC 目前仅支持 setter 方式注入
     *
     * @param instance 实例对象
     * @return 返回 instance 实例对象
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                // 遍历目标类的所有方法
                for (Method method : instance.getClass().getMethods()) {
                    // 检测方法是否以 set 开头，且方法仅有一个参数，且方法访问级别为 public
                    /*if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {

                    }*/
                    // 3个条件，单独提出boolean变量，并且从名字可以看出要判断啥东西，这个地方不怕名字过长。表意很重要
                    boolean isSingleParamPublicSetMethod = method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers());
                    // 反向判断，阅读时会很容易理解，要不然得仔细阅读3个条件，还要适当猜一猜。不满足条件直接continue换下一个，简单明了。
                    if (!isSingleParamPublicSetMethod) {
                        continue;
                    }
                    // 获取参数类型
                    Class<?> pt = method.getParameterTypes()[0];
                    try {
                        // 获取属性名，比如 setName 方法对应属性名 name
                        String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() +
                                method.getName().substring(4) : "";
                        // 从 ObjectFactory 中获取依赖对象
                        Object object = objectFactory.getExtension(pt, property);
                        if (object != null) {
                            // 通过反射调用 setter 方法设置依赖
                            method.invoke(instance, object);
                        }
                    } catch (Exception e) {
                        logger.error("fail to inject via method " + method.getName()
                                + " of interface " + type.getName() + ": " + e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        }
        return clazz;
    }

    /**
     * 获取所有的扩展类
     * 先检查缓存，若缓存未命中，则通过 synchronized 加锁。加锁后再次检查缓存，并判空。此时如果 classes 仍为 null，则通过 loadExtensionClasses 加载拓展类
     *
     * @return 返回缓存集合对象
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 从缓存中获取已加载的拓展类
        Map<String, Class<?>> classes = cachedClasses.get();
        // 双重检查锁创建线程安全的单例对象
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 加载扩展类
                    classes = loadExtensionClasses();
                    // 将扩展对象加入缓存
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * loadExtensionClasses 方法总共做了两件事情，一是对 SPI 注解进行解析，二是调用 loadDirectory 方法加载指定文件夹配置文件。
     *
     * @return 返回扩展类缓存集合对象
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        // 获取 SPI 注解，这里的 type 变量是在调用 getExtensionLoader 方法时传入的
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                // 对 SPI 注解内容进行切分
                String[] names = NAME_SEPARATOR.split(value);
                // 检测 SPI 注解内容是否合法，不合法则抛出异常
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 设置默认名称，参考 getDefaultExtension 方法
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // 从 META-INF/dubbo/internal 目录下加载扩展类信息
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        // 从 META-INF/dubbo 目录下加载扩展类信息
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        // 从 META-INF/services 目录下加载扩展类信息
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 分别从 META-INF/dubbo/internal 、META-INF/dubbo、META-INF/services 目录依次加载扩展类信息
     * <p>
     * loadDirectory 方法先通过 classLoader 获取所有资源链接，然后再通过 loadResource 方法加载资源。
     *
     * @param extensionClasses 扩展类信息
     * @param dir              目录名称
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
        // fileName = 文件夹路径 + type 全限定名
        String fileName = dir + type.getName();
        try {
            // 列举所有的 URL
            Enumeration<java.net.URL> urls;
            // 找到类加载器
            ClassLoader classLoader = findClassLoader();
            // 根据文件名加载所有的同名文件
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 加载资源
     * loadResource 方法用于读取和解析配置文件，并通过反射加载类，最后调用 loadClass 方法进行其他操作。
     *
     * @param extensionClasses 扩展类信息
     * @param classLoader      类加载器
     * @param resourceURL      资源URL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            // 打开缓冲区
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                // 按行读取配置内容
                while ((line = reader.readLine()) != null) {
                    // 定位 # 字符
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        // 截取 # 之前的字符串，# 之后的内容为注释，需要忽略
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                // 以等于号 = 为界，截取键与值
                                name = line.substring(0, i).trim();
                                // 获取扩展点实现类全限定名
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                // Class.forName(line, true, classLoader) 通过反射获取自适应扩展点，作为参数传入
                                // 加载类，并通过 loadClass 方法对类进行缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * loadClass 方法用于主要用于操作缓存。loadClass 方法操作了不同的缓存，比如 cachedAdaptiveClass、cachedWrapperClasses 和 cachedNames 等等
     *
     * @param extensionClasses 扩展类
     * @param resourceURL      资源链接
     * @param clazz            反射类
     * @param name             名称
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        // 检测目标类上是否有 Adaptive 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                // 设置 cachedAdaptiveClass缓存
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
            // 检测 clazz 是否是 Wrapper 类型
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            // 存储 clazz 到 cachedWrapperClasses 缓存中
            wrappers.add(clazz);
            // 程序进入此分支，表明 clazz 是一个普通的拓展类
        } else {
            // 检测 clazz 是否有默认的构造方法，如果没有，则抛出异常
            clazz.getConstructor();
            if (name == null || name.length() == 0) {
                // 如果 name 为空，则尝试从 Extension 注解中获取 name，或使用小写的类名作为 name
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // 切分 name
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                    // 如果类上有 Activate 注解，则使用 names 数组的第一个元素作为键，
                    // 存储 name 到 Activate 注解对象的映射关系
                    cachedActivates.put(names[0], activate);
                }
                for (String n : names) {
                    if (!cachedNames.containsKey(clazz)) {
                        // 存储 Class 到名称的映射关系
                        cachedNames.put(clazz, n);
                    }
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        // 存储名称到 Class 的映射关系
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    /**
     * 判断目标类是否是Wrapper 类型
     *
     * @param clazz
     * @return
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建自适应扩展对象
     * <p>
     * createAdaptiveExtension 方法的代码比较少，但却包含了三个逻辑，分别如下：
     * 1、调用 getAdaptiveExtensionClass 方法获取自适应拓展 Class 对象
     * 2、通过反射进行实例化
     * 3、调用 injectExtension 方法向拓展实例中注入依赖
     * 前两个逻辑比较好理解，第三个逻辑用于向自适应拓展对象中注入依赖。这个逻辑看似多余，但有存在的必要，这里简单说明一下。前面说过，Dubbo 中有两种类型的自适应拓展，一种
     * 是手工编码的，一种是自动生成的。手工编码的自适应拓展中可能存在着一些依赖，而自动生成的 Adaptive 拓展则不会依赖其他类。这里调用 injectExtension 方法的目的是为
     * 手工编码的自适应拓展注入依赖，这一点需要大家注意一下。关于 injectExtension 方法，前文已经分析过了，这里不再赘述。
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 获取自适应拓展类，并通过反射实例化
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * getAdaptiveExtensionClass 方法同样包含了三个逻辑，如下：
     * <p>
     * 1、调用 getExtensionClasses 获取所有的拓展类
     * 2、检查缓存，若缓存不为空，则返回缓存
     * 3、若缓存为空，则调用 createAdaptiveExtensionClass 创建自适应拓展类
     * 这三个逻辑看起来平淡无奇，似乎没有多讲的必要。但是这些平淡无奇的代码中隐藏了着一些细节，需要说明一下。首先从第一个逻辑说起，getExtensionClasses 这个
     * 方法用于获取某个接口的所有实现类。比如该方法可以获取 Protocol 接口的 DubboProtocol、HttpProtocol、InjvmProtocol 等实现类。在获取实现类的过程中，
     * 如果某个实现类被 Adaptive 注解修饰了，那么该类就会被赋值给 cachedAdaptiveClass 变量。此时，上面步骤中的第二步条件成立（缓存不为空），直接返
     * 回 cachedAdaptiveClass 即可。如果所有的实现类均未被 Adaptive 注解修饰，那么执行第三步逻辑，创建自适应拓展类。
     *
     * @return cachedAdaptiveClass
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 通过 SPI 获取所有的拓展类
        getExtensionClasses();
        // 检查缓存，若缓存不为空，则直接返回缓存
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 创建自适应拓展类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * createAdaptiveExtensionClass 方法用于生成自适应拓展类，该方法首先会生成自适应拓展类的源码，然后通过 Compiler 实例（Dubbo 默认使
     * 用 javassist 作为编译器）编译源码，得到代理类 Class 实例
     *
     * @return class 类
     */
    private Class<?> createAdaptiveExtensionClass() {
        // 构建自适应拓展代码
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        // 获取编译器实现类
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 编译代码，生成 Class
        return compiler.compile(code, classLoader);
    }

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();

        /**
         * 第一步：注解检测
         *   在生成代理类源码之前，createAdaptiveExtensionClassCode 方法首先会通过反射检测接口方法是否包含 Adaptive 注解。对于要生成自适应拓展的接口，Dubbo 要求该
         *   接口至少有一个方法被 Adaptive 注解修饰。若不满足此条件，就会抛出运行时异常。
         */
        // 通过反射获取所有的方法
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        // 遍历方法列表
        for (Method m : methods) {
            // 检测方法上是否有 Adaptive 注解
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation) {
            // 若所有的方法上均无 Adaptive 注解，则抛出异常
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        /**
         * 第二步：生成类
         *   通过 Adaptive 注解检测后，即可开始生成代码。代码生成的顺序与 Java 文件内容顺序一致，首先会生成 package 语句，然后生成 import 语句，紧接着生成类名等代码。
         */
        // 生成 package 代码：package + type 所在包
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成 import 代码：import + ExtensionLoader 全限定名
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        codeBuilder.append("\npublic class ").
                append(type.getSimpleName()).
                append("$Adaptive").
                append(" implements ").
                append(type.getCanonicalName()).
                append(" {");

        for (Method method : methods) {
            // 返回类型
            Class<?> rt = method.getReturnType();
            // 参数类型
            Class<?>[] pts = method.getParameterTypes();
            // 异常类型
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);

            /**
             * 第三步：生成方法
             *    第1小步：无 Adaptive 注解方法代码生成逻辑
             *
             *    如果方法上无 Adaptive 注解，则生成 throw new UnsupportedOperationException(...) 代码
             */
            if (adaptiveAnnotation == null) {
                // 生成的代码格式如下：
                // throw new UnsupportedOperationException(
                //     "method " + 方法签名 + of interface + 全限定接口名 + is not adaptive method!”)
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            }
            /**
             * 第三步：生成方法
             *    第2小步：有 Adaptive 注解，先获取 URL 数据
             *
             *    前面说过方法代理逻辑会从 URL 中提取目标拓展的名称，因此代码生成逻辑的一个重要的任务是从方法的参数列表或者其他参数中获取 URL 数据。举例说明一下，我们
             *    要为 Protocol 接口的 refer 和 export 方法生成代理逻辑。在运行时，通过反射得到的方法定义大致如下：
             *       Invoker refer(Class<T> arg0, URL arg1) throws RpcException;
             *       Exporter export(Invoker<T> arg0) throws RpcException;
             *
             *     对于 refer 方法，通过遍历 refer 的参数列表即可获取 URL 数据，这个还比较简单。对于 export 方法，获取 URL 数据则要麻烦一些。export 参数列表中
             *     没有 URL 参数，因此需要从 Invoker 参数中获取 URL 数据。获取方式是调用 Invoker 中可返回 URL 的 getter 方法，比如 getUrl。如果 Invoker 中无
             *     相关 getter 方法，此时则会抛出异常。整个逻辑如下：
             */
            else {
                int urlTypeIndex = -1;
                // 遍历参数列表，确定 URL 参数位置
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // urlTypeIndex != -1，表示参数列表中存在 URL 参数
                if (urlTypeIndex != -1) {
                    // 为 URL 类型参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null)
                    //     throw new IllegalArgumentException("url == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    // 为 URL 类型参数生成赋值代码，形如 URL url = arg1
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }

                // 参数列表中不存在 URL 类型参数
                else {
                    String attribMethod = null;

                    // 循环标签，使用可以跳出多层循环
                    LBL_PTS:
                    // 遍历方法的参数类型列表
                    for (int i = 0; i < pts.length; ++i) {
                        // 获取某一类型参数的全部方法
                        Method[] ms = pts[i].getMethods();
                        // 遍历方法列表，寻找可返回 URL 的 getter 方法
                        for (Method m : ms) {
                            String name = m.getName();
                            // 1. 方法名以 get 开头，或方法名大于3个字符
                            // 2. 方法的访问权限为 public
                            // 3. 非静态方法
                            // 4. 方法参数数量为0(无参)
                            // 5. 方法返回值类型为 URL
                            boolean isNoParamPublicNoStaticGetMenthodReturnTypeURL = (name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class;
                            if (isNoParamPublicNoStaticGetMenthodReturnTypeURL) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                // 结束 for (int i = 0; i < pts.length; ++i) 循环
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        // 如果所有参数中均不包含可返回 URL 的 getter 方法，则抛出异常
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // 为可返回 URL 的参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null)
                    //     throw new IllegalArgumentException("参数全限定名 + argument == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);

                    // 为 getter 方法返回的 URL 生成判空代码，格式如下：
                    // if (argN.getter方法名() == null)
                    //     throw new IllegalArgumentException(参数全限定名 + argument getUrl() == null);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成赋值语句，格式如下：
                    // URL全限定名 url = argN.getter方法名()，比如
                    // com.alibaba.dubbo.common.URL url = invoker.getUrl();
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                /**
                 * 第三步：生成方法
                 *   第3小步：获取 Adaptive 注解值
                 *
                 *   Adaptive 注解值 value 类型为 String[]，可填写多个值，默认情况下为空数组。若 value 为非空数组，直接获取数组内容即可。若 value 为空数组，则需进行
                 *   额外处理。处理过程是将类名转换为字符数组，然后遍历字符数组，并将字符放入 StringBuilder 中。若字符为大写字母，则向 StringBuilder 中添加点号，随后
                 *   将字符变为小写存入 StringBuilder 中。比如 LoadBalance 经过处理后，得到 load.balance。
                 */
                String[] value = adaptiveAnnotation.value();
                // value 为空数组
                if (value.length == 0) {
                    // 获取类名，并将类名转换为字符数组
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    // 遍历字节数组
                    for (int i = 0; i < charArray.length; i++) {
                        // 检测当前字符是否为大写字母
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                // 向 sb 中添加点号
                                sb.append(".");
                            }
                            // 将字符变为小写，并添加到 sb 中
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            // 添加字符到 sb 中
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                /**
                 * 第三步：生成方法
                 *   第4小步：检测 Invocation 参数
                 *   此段逻辑是检测方法列表中是否存在 Invocation 类型的参数，若存在，则为其生成判空代码和其他一些代码。
                 */
                boolean hasInvocation = false;
                // 遍历参数类型列表
                for (int i = 0; i < pts.length; ++i) {
                    // 判断当前参数名称是否等于 com.alibaba.dubbo.rpc.Invocation
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // 为 Invocation 类型参数生成判空代码
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // 生成 getMethodName 方法调用代码，格式为：
                        //    String methodName = argN.getMethodName();
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                /**
                 * 第三步：生成方法
                 *   第5小步： 生成拓展名获取逻辑
                 *
                 *   本段逻辑用于根据 SPI 和 Adaptive 注解值生成“获取拓展名逻辑”，同时生成逻辑也受 Invocation 类型参数影响，综合因素导致本段逻辑相对复杂。本段逻辑可能会生成但不限于下面的代码：
                 *   String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
                 *   或
                 *   String extName = url.getMethodParameter(methodName, "loadbalance", "random");
                 *   亦或是
                 *   String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
                 */
                // 设置默认拓展名，cachedDefaultName 源于 SPI 注解值，默认情况下，
                // SPI 注解值为空串，此时 cachedDefaultName = null
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;

                // 遍历 value，这里的 value 是 Adaptive 的注解值，‘第3小步：获取 Adaptive 注解值’ 具体节分析过 value 变量的获取过程。
                // 此处循环目的是生成从 URL 中获取拓展名的代码，生成的代码会赋值给 getNameCode 变量。注意这个循环的遍历顺序是由后向前遍历的。
                for (int i = value.length - 1; i >= 0; --i) {
                    // 当 i 为最后一个元素的坐标时
                    if (i == value.length - 1) {
                        // 默认拓展名非空
                        if (null != defaultExtName) {
                            // protocol 是 url 的一部分，可通过 getProtocol 方法获取，其他的则是从
                            // URL 参数中获取。因为获取方式不同，所以这里要判断 value[i] 是否为 protocol
                            if (!"protocol".equals(value[i])) {
                                // hasInvocation 用于标识方法参数列表中是否有 Invocation 类型参数
                                if (hasInvocation) {
                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getMethodParameter(methodName, value[i], defaultExtName)
                                    // 以 LoadBalance 接口的 select 方法为例，最终生成的代码如下：
                                    //   url.getMethodParameter(methodName, "loadbalance", "random")
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getParameter(value[i], defaultExtName)
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                                }
                            } else {
                                // 生成的代码功能等价于下面的代码：
                                //   ( url.getProtocol() == null ? defaultExtName : url.getProtocol() )
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                            }
                            // 默认拓展名为空
                        } else {
                            if (!"protocol".equals(value[i])) {
                                if (hasInvocation) {
                                    // 生成代码格式同上
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getParameter(value[i])
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                                }
                            } else {
                                // 生成从 url 中获取协议的代码，比如 "dubbo"
                                getNameCode = "url.getProtocol()";
                            }
                        }
                    } else {
                        if (!"protocol".equals(value[i])) {
                            if (hasInvocation) {
                                // 生成代码格式同上
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            } else {
                                // 生成的代码功能等价于下面的代码：
                                //   url.getParameter(value[i], getNameCode)
                                // 以 Transporter 接口的 connect 方法为例，最终生成的代码如下：
                                //   url.getParameter("client", url.getParameter("transporter", "netty"))
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                            }
                        } else {
                            // 生成的代码功能等价于下面的代码：
                            //   url.getProtocol() == null ? getNameCode : url.getProtocol()
                            // 以 Protocol 接口的 connect 方法为例，最终生成的代码如下：
                            //   url.getProtocol() == null ? "dubbo" : url.getProtocol()
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                        }
                    }
                }
                // 生成 extName 赋值代码
                code.append("\nString extName = ").append(getNameCode).append(";");
                // 生成 extName 判空代码
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                /**
                 *  第三步：生成方法
                 *     第6小步：生成拓展加载与目标方法调用逻辑
                 *
                 *     本段代码逻辑用于根据拓展名加载拓展实例，并调用拓展实例的目标方法。
                 */
                // 生成拓展获取代码，格式如下：
                // type全限定名 extension = (type全限定名)ExtensionLoader全限定名
                //     .getExtensionLoader(type全限定名.class).getExtension(extName);
                // Tips: 格式化字符串中的 %<s 表示使用前一个转换符所描述的参数，即 type 全限定名
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // 如果方法返回值类型非 void，则生成 return 语句
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                // 生成目标方法调用逻辑，格式为：
                // extension.方法名(arg0, arg2, ..., argN);
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0) {
                        code.append(", ");
                    }
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            /**
             * 第三步：生成方法
             *   第7小步：生成完整的方法
             *   本节进行代码生成的收尾工作，主要用于生成方法定义的代码
             */
            // public + 返回值全限定名 + 方法名 + (
            codeBuilder.append("\npublic ").
                    append(rt.getCanonicalName()).
                    append(" ").
                    append(method.getName()).
                    append("(");
            // 添加参数列表代码
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");

            // 添加异常抛出代码
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}