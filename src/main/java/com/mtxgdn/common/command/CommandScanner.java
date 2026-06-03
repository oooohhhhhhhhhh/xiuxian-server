package com.mtxgdn.common.command;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class CommandScanner {

    private CommandScanner() {
    }

    public static ScanResult scanAndRegister(String packageName) {
        List<String> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        List<Class<?>> classes;
        try {
            classes = findClassesInPackage(packageName);
        } catch (Exception e) {
            failed.add("扫描包失败: " + e.getMessage());
            return new ScanResult(registered, skipped, failed);
        }

        for (Class<?> clazz : classes) {
            try {
                if (!Command.class.isAssignableFrom(clazz)) {
                    skipped.add(clazz.getSimpleName() + " (未继承Command)");
                    continue;
                }
                if (clazz.equals(Command.class)) {
                    skipped.add(clazz.getSimpleName() + " (基类)");
                    continue;
                }
                if (Modifier.isAbstract(clazz.getModifiers())) {
                    skipped.add(clazz.getSimpleName() + " (抽象类)");
                    continue;
                }
                if (!Modifier.isPublic(clazz.getModifiers())) {
                    skipped.add(clazz.getSimpleName() + " (非public)");
                    continue;
                }

                clazz.getDeclaredConstructor().newInstance();
                registered.add(clazz.getSimpleName());
            } catch (NoSuchMethodException e) {
                failed.add(clazz.getSimpleName() + " (缺少无参构造)");
            } catch (Exception e) {
                failed.add(clazz.getSimpleName() + " (实例化失败: " + e.getMessage() + ")");
            }
        }

        return new ScanResult(registered, skipped, failed);
    }

    private static List<Class<?>> findClassesInPackage(String packageName) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        Enumeration<URL> resources = classLoader.getResources(packagePath);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if ("file".equals(resource.getProtocol())) {
                findClassesInDirectory(new File(resource.toURI()), packageName, classes);
            } else if ("jar".equals(resource.getProtocol())) {
                findClassesInJar(resource, packagePath, classes);
            }
        }

        if (classes.isEmpty()) {
            String targetClassesPath = System.getProperty("user.dir") + "/target/classes/" + packagePath;
            findClassesInDirectory(new File(targetClassesPath), packageName, classes);
        }

        return classes;
    }

    private static void findClassesInDirectory(File directory, String packageName, List<Class<?>> classes) {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                findClassesInDirectory(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }
    }

    private static void findClassesInJar(URL jarUrl, String packagePath, List<Class<?>> classes) throws Exception {
        String jarPath = jarUrl.getPath();
        if (jarPath.contains("!")) {
            jarPath = jarPath.substring(0, jarPath.indexOf('!'));
        }
        jarPath = jarPath.replace("file:", "");

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")
                        && !entryName.contains("$")) {
                    String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');
                    try {
                        classes.add(Class.forName(className));
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    }
                }
            }
        }
    }

    public static class ScanResult {
        public final List<String> registered;
        public final List<String> skipped;
        public final List<String> failed;

        ScanResult(List<String> registered, List<String> skipped, List<String> failed) {
            this.registered = registered;
            this.skipped = skipped;
            this.failed = failed;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("命令扫描完成: 注册 ").append(registered.size())
                    .append(" 个, 跳过 ").append(skipped.size())
                    .append(" 个, 失败 ").append(failed.size()).append(" 个");
            if (!failed.isEmpty()) {
                sb.append("\n  失败: ").append(failed);
            }
            return sb.toString();
        }
    }
}
