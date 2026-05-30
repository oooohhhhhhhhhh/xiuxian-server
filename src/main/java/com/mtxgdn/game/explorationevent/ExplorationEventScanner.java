package com.mtxgdn.game.explorationevent;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ExplorationEventScanner {

    private static final String EVENT_PACKAGE = "data.mtxgdn.explorationevent";

    private ExplorationEventScanner() {
    }

    public static ScanResult scanAndRegister() {
        List<String> registered = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        List<Class<?>> classes;
        try {
            classes = findClassesInPackage(EVENT_PACKAGE);
        } catch (Exception e) {
            failed.add("扫描包失败: " + e.getMessage());
            return new ScanResult(registered, skipped, failed);
        }

        for (Class<?> clazz : classes) {
            try {
                if (!ExplorationEvent.class.isAssignableFrom(clazz)) {
                    skipped.add(clazz.getSimpleName() + " (未继承ExplorationEvent)");
                    continue;
                }
                if (clazz.equals(ExplorationEvent.class)) {
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

    private static void findClassesInJar(URL resource, String packagePath, List<Class<?>> classes) throws Exception {
        String jarPath = resource.getPath();
        if (jarPath.startsWith("file:")) jarPath = jarPath.substring("file:".length());
        int sep = jarPath.indexOf("!/");
        if (sep > 0) jarPath = jarPath.substring(0, sep);

        try (JarFile jarFile = new JarFile(new File(jarPath))) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(packagePath + "/") && name.endsWith(".class") && !name.contains("$")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    try {
                        classes.add(Class.forName(className));
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    }
                }
            }
        }
    }

    public static class ScanResult {
        private final List<String> registered;
        private final List<String> skipped;
        private final List<String> failed;

        ScanResult(List<String> registered, List<String> skipped, List<String> failed) {
            this.registered = registered;
            this.skipped = skipped;
            this.failed = failed;
        }

        public List<String> getRegistered() {
            return registered;
        }

        public List<String> getSkipped() {
            return skipped;
        }

        public List<String> getFailed() {
            return failed;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[ExplorationEventScanner] 扫描完成: ")
                    .append(registered.size()).append(" 个已注册");
            if (!skipped.isEmpty()) {
                sb.append(", ").append(skipped.size()).append(" 个跳过");
            }
            if (!failed.isEmpty()) {
                sb.append(", ").append(failed.size()).append(" 个失败");
            }
            for (String r : registered) {
                sb.append("\n  [✓] ").append(r);
            }
            for (String s : skipped) {
                sb.append("\n  [-] ").append(s);
            }
            for (String f : failed) {
                sb.append("\n  [✗] ").append(f);
            }
            return sb.toString();
        }
    }
}
