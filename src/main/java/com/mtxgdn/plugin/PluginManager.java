package com.mtxgdn.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * 插件管理器。负责扫描 ./plugins 目录、加载 jar、初始化插件，并管理插件生命周期。
 * <p>
 * 使用方式（服务端启动时调用）:
 * <pre>
 *   PluginManager pm = PluginManager.getInstance();
 *   pm.loadPlugins();   // 调用 onLoad
 *   pm.enablePlugins(); // 调用 onEnable
 *   // ... 运行中 ...
 *   pm.disablePlugins(); // 服务器关闭
 * </pre>
 */
public final class PluginManager {

    private static final GameLogger LOG = GameLogger.getLogger(PluginManager.class);
    private static final PluginManager INSTANCE = new PluginManager();

    private final Map<String, LoadedPlugin> loaded = new LinkedHashMap<>();
    private final List<PluginClassLoader> classLoaders = new ArrayList<>();
    private File pluginsDir;
    private boolean initialized = false;

    public static PluginManager getInstance() { return INSTANCE; }

    private PluginManager() { }

    /**
     * 初始化插件目录。多次调用仅第一次有效。
     * @param pluginsDir 插件目录，例如 ./plugins
     */
    public synchronized void init(File pluginsDir) {
        if (initialized) return;
        this.pluginsDir = pluginsDir;
        if (!pluginsDir.exists()) {
            if (!pluginsDir.mkdirs()) {
                LOG.warn("无法创建插件目录: " + pluginsDir.getAbsolutePath());
                return;
            }
        }
        this.initialized = true;
    }

    /** 扫描并加载所有插件（调用 onLoad 生命周期）。 */
    public synchronized LoadResult loadPlugins() {
        if (!initialized) {
            LOG.warn("PluginManager 尚未初始化，无法加载插件。");
            return new LoadResult(0, 0);
        }
        File[] jars = pluginsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOG.info("未发现任何插件 jar。");
            return new LoadResult(0, 0);
        }

        int success = 0;
        int failed = 0;
        for (File jar : jars) {
            try {
                loadSingle(jar);
                success++;
            } catch (Throwable t) {
                LOG.error("加载插件失败: " + jar.getName(), t);
                failed++;
            }
        }

        // 调用 onLoad
        for (LoadedPlugin lp : loaded.values()) {
            try {
                lp.instance.onLoad(lp.context);
            } catch (Throwable t) {
                LOG.error("插件 onLoad 异常: " + lp.info.getName(), t);
            }
        }
        LOG.info("插件加载完成: 成功 " + success + " 个，失败 " + failed + " 个");
        return new LoadResult(success, failed);
    }

    /** 启用所有已加载的插件（调用 onEnable 生命周期）。 */
    public synchronized void enablePlugins() {
        for (LoadedPlugin lp : loaded.values()) {
            try {
                lp.instance.onEnable(lp.context);
                LOG.info("插件已启用: " + lp.info);
            } catch (Throwable t) {
                LOG.error("插件 onEnable 异常: " + lp.info.getName(), t);
            }
        }
    }

    /** 停用所有插件（服务器关闭时调用）。 */
    public synchronized void disablePlugins() {
        List<LoadedPlugin> reverse = new ArrayList<>(loaded.values());
        Collections.reverse(reverse);
        for (LoadedPlugin lp : reverse) {
            try {
                lp.instance.onDisable(lp.context);
                LOG.info("插件已停用: " + lp.info.getName());
            } catch (Throwable t) {
                LOG.error("插件 onDisable 异常: " + lp.info.getName(), t);
            }
        }
        // 尝试关闭类加载器（释放文件句柄）
        for (PluginClassLoader cl : classLoaders) {
            try { cl.close(); } catch (Exception ignored) { }
        }
        classLoaders.clear();
    }

    /** 获取已加载插件列表。 */
    public List<LoadedPlugin> getLoadedPlugins() {
        return new ArrayList<>(loaded.values());
    }

    /** 按名字获取一个已加载插件。 */
    public LoadedPlugin getPlugin(String name) {
        return loaded.get(name);
    }

    public int getPluginCount() { return loaded.size(); }

    // =================== 内部实现 ===================

    private void loadSingle(File jar) throws Exception {
        // 1. 读取 plugin.json（若存在）
        PluginInfo info = readPluginJson(jar);

        // 2. 使用自定义类加载器加载 jar
        PluginClassLoader cl = new PluginClassLoader(jar, getClass().getClassLoader());
        classLoaders.add(cl);

        // 3. 解析主类名
        String mainClassName;
        if (info != null && info.getMainClass() != null && !info.getMainClass().isEmpty()) {
            mainClassName = info.getMainClass();
        } else {
            // 回退：扫描 jar 中所有实现了 Plugin 接口的类（取第一个）
            mainClassName = findPluginMainClass(jar, cl);
            if (mainClassName == null) {
                throw new RuntimeException("jar 中未找到 plugin.json，也未发现实现 Plugin 接口的类");
            }
            info = buildPluginInfoFromClass(cl, mainClassName);
        }

        if (loaded.containsKey(info.getName())) {
            LOG.warn("插件名称冲突，跳过: " + info.getName());
            return;
        }

        // 4. 实例化插件主类
        Class<?> mainCls = Class.forName(mainClassName, true, cl);
        if (!Plugin.class.isAssignableFrom(mainCls)) {
            throw new RuntimeException("主类 " + mainClassName + " 未实现 Plugin 接口");
        }
        Plugin instance = (Plugin) mainCls.getDeclaredConstructor().newInstance();

        // 5. 准备插件数据目录和上下文
        File dataFolder = new File(pluginsDir, info.getName());
        PluginContext ctx = new PluginContext(info, dataFolder, cl);

        // 6. 记录
        LoadedPlugin lp = new LoadedPlugin(info, instance, ctx, jar);
        loaded.put(info.getName(), lp);
        LOG.info("发现插件: " + info);
    }

    private PluginInfo readPluginJson(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            var entry = jf.getJarEntry("plugin.json");
            if (entry == null) entry = jf.getJarEntry("META-INF/plugin.json");
            if (entry == null) return null;
            try (InputStream is = jf.getInputStream(entry);
                 InputStreamReader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject obj = new Gson().fromJson(r, JsonObject.class);
                return new PluginInfo(
                        optStr(obj, "name", jar.getName().replace(".jar", "")),
                        optStr(obj, "version", "1.0.0"),
                        optStr(obj, "author", "匿名"),
                        optStr(obj, "description", ""),
                        optStr(obj, "main", null)
                );
            }
        } catch (Exception e) {
            LOG.warn("解析 plugin.json 失败: " + jar.getName() + "，将尝试自动扫描");
            LOG.error("解析 plugin.json 详情: " + jar.getName(), e);
            return null;
        }
    }

    private static String optStr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key)) return def;
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return def;
        return el.getAsString();
    }

    private String findPluginMainClass(File jar, ClassLoader cl) {
        try (JarFile jf = new JarFile(jar)) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("$")) continue;
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(className, false, cl);
                    if (Plugin.class.isAssignableFrom(c) && !c.equals(Plugin.class)) {
                        return className;
                    }
                } catch (Throwable ignored) {
                    // 加载失败就跳过
                }
            }
        } catch (Exception e) {
            LOG.warn("扫描 jar 查找主类失败: " + jar.getName());
            LOG.error("扫描 jar 详情: " + jar.getName(), e);
        }
        return null;
    }

    private PluginInfo buildPluginInfoFromClass(ClassLoader cl, String mainClassName) {
        try {
            Class<?> cls = Class.forName(mainClassName, false, cl);
            PluginMeta meta = cls.getAnnotation(PluginMeta.class);
            if (meta != null) {
                return new PluginInfo(meta.name(), meta.version(), meta.author(), meta.description(), mainClassName);
            }
            // 没有注解，用类名作为名字
            String simpleName = cls.getSimpleName();
            return new PluginInfo(simpleName, "1.0.0", "匿名", "", mainClassName);
        } catch (Throwable t) {
            return new PluginInfo(mainClassName, "1.0.0", "匿名", "", mainClassName);
        }
    }

    // =================== 内部数据结构 ===================

    /** 单个已加载插件的信息。 */
    public static final class LoadedPlugin {
        private final PluginInfo info;
        private final Plugin instance;
        private final PluginContext context;
        private final File jarFile;

        LoadedPlugin(PluginInfo info, Plugin instance, PluginContext context, File jarFile) {
            this.info = info;
            this.instance = instance;
            this.context = context;
            this.jarFile = jarFile;
        }

        public PluginInfo getInfo() { return info; }
        public Plugin getInstance() { return instance; }
        public PluginContext getContext() { return context; }
        public File getJarFile() { return jarFile; }

        @Override
        public String toString() { return info.toString(); }
    }

    /** 插件加载结果摘要。 */
    public static final class LoadResult {
        public final int success;
        public final int failed;

        public LoadResult(int success, int failed) {
            this.success = success;
            this.failed = failed;
        }

        @Override
        public String toString() {
            return "插件加载结果: 成功 " + success + " 个，失败 " + failed + " 个";
        }
    }
}
