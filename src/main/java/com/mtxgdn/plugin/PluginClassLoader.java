package com.mtxgdn.plugin;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件专用类加载器。
 * 优先使用系统类加载器加载服务端核心类（避免版本冲突），
 * 对插件自身的类则从其 jar 包加载。
 */
class PluginClassLoader extends URLClassLoader {

    private final ClassLoader parent;

    PluginClassLoader(File jarFile, ClassLoader parent) throws Exception {
        super(new URL[]{jarFile.toURI().toURL()}, null);
        this.parent = parent;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 已加载？
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 2. 属于服务端核心包（com.mtxgdn.*）？让父类（服务端）加载
            if (name != null && (name.startsWith("com.mtxgdn.")
                    || name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jakarta.") || name.startsWith("sun.")
                    || name.startsWith("org.glassfish.") || name.startsWith("io.jsonwebtoken.")
                    || name.startsWith("com.google.gson.") || name.startsWith("org.yaml.")
                    || name.startsWith("com.zaxxer.") || name.startsWith("org.mindrot.")
                    || name.startsWith("com.mysql.") || name.startsWith("org.sqlite."))) {
                try {
                    c = parent.loadClass(name);
                    if (c != null) {
                        if (resolve) resolveClass(c);
                        return c;
                    }
                } catch (ClassNotFoundException ignored) {
                    // 继续尝试自己加载
                }
            }

            // 3. 从插件 jar 自身加载
            try {
                c = findClass(name);
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            } catch (ClassNotFoundException ignored) {
                // 继续尝试父类
            }

            // 4. 兜底：交给父类加载器
            if (parent != null) {
                c = parent.loadClass(name);
                if (resolve && c != null) resolveClass(c);
                return c;
            }
            throw new ClassNotFoundException(name);
        }
    }
}
