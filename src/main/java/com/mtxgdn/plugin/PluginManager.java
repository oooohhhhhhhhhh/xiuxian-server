package com.mtxgdn.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

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
            enableSingle(lp);
        }
    }

    /** 启用单个已加载但未启用的插件。 */
    public synchronized boolean enablePlugin(String name) {
        LoadedPlugin lp = loaded.get(name);
        if (lp == null) return false;
        if (lp.enabled) {
            LOG.warn("插件已经启用: " + name);
            return false;
        }
        enableSingle(lp);
        return true;
    }

    /** 停用单个已启用的插件（不卸载，仅调 onDisable）。 */
    public synchronized boolean disablePlugin(String name) {
        LoadedPlugin lp = loaded.get(name);
        if (lp == null) return false;
        if (!lp.enabled) {
            LOG.warn("插件未启用: " + name);
            return false;
        }
        try {
            lp.instance.onDisable(lp.context);
            LOG.info("插件已停用: " + lp.info.getName());
        } catch (Throwable t) {
            LOG.error("插件 onDisable 异常: " + lp.info.getName(), t);
        }
        lp.enabled = false;
        return true;
    }

    private void enableSingle(LoadedPlugin lp) {
        if (lp.enabled) return;
        try {
            lp.instance.onEnable(lp.context);
            lp.enabled = true;
            LOG.info("插件已启用: " + lp.info);
        } catch (Throwable t) {
            LOG.error("插件 onEnable 异常: " + lp.info.getName(), t);
        }
    }

    /** 停用所有插件（服务器关闭时调用）。 */
    public synchronized void disablePlugins() {
        List<LoadedPlugin> reverse = new ArrayList<>(loaded.values());
        Collections.reverse(reverse);
        for (LoadedPlugin lp : reverse) {
            disableSingle(lp);
        }
        loaded.clear();
    }

    /** 卸载指定插件（调用 onDisable 并清理资源）。 */
    public synchronized boolean unloadPlugin(String name) {
        LoadedPlugin lp = loaded.remove(name);
        if (lp == null) return false;
        disableSingle(lp);
        return true;
    }

    /** 重新加载指定插件。会先卸载再加载，jar 文件原地不变。 */
    public synchronized boolean reloadPlugin(String name) {
        LoadedPlugin lp = loaded.get(name);
        if (lp == null) {
            LOG.warn("插件未加载，无法重载: " + name);
            return false;
        }
        File jarFile = lp.jarFile;
        // 卸载
        disableSingle(lp);
        loaded.remove(name);
        // 重新加载
        try {
            loadSingle(jarFile);
            // 对新加载的插件调用 onLoad 和 onEnable
            LoadedPlugin reloaded = loaded.get(name);
            if (reloaded != null) {
                reloaded.instance.onLoad(reloaded.context);
                enableSingle(reloaded);
                LOG.info("插件重载成功: " + name);
            }
            return true;
        } catch (Throwable t) {
            LOG.error("插件重载失败: " + name, t);
            return false;
        }
    }

    private void disableSingle(LoadedPlugin lp) {
        try {
            lp.instance.onDisable(lp.context);
            LOG.info("插件已停用: " + lp.info.getName());
        } catch (Throwable t) {
            LOG.error("插件 onDisable 异常: " + lp.info.getName(), t);
        }
        // 清理事件处理器
        com.mtxgdn.plugin.event.PluginEventManager.getInstance().unregisterAll(lp.info.getName());
        // 清理 Web 资源
        PluginWebManager.getInstance().unregisterPlugin(lp.info.getName());
        // 关闭类加载器（释放文件句柄）
        try {
            PluginClassLoader cl = (PluginClassLoader) lp.context.getClassLoader();
            classLoaders.remove(cl);
            cl.close();
        } catch (Exception ignored) { }
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

    // =================== 热替换 / 动态加载 ===================

    /**
     * 加载并启用单个插件（从指定 jar 文件）。
     * 如果该插件已加载，则先卸载再重新加载（热重载）。
     */
    public synchronized boolean loadPlugin(File jar) {
        if (!jar.exists() || !jar.getName().toLowerCase().endsWith(".jar")) {
            LOG.warn("无效的插件文件: " + jar.getAbsolutePath());
            return false;
        }
        try {
            // 读取元数据，检查是否已加载
            PluginInfo info = readPluginJson(jar);
            if (info != null && loaded.containsKey(info.getName())) {
                // 已加载：执行热重载
                LOG.info("插件已加载，执行热重载: " + info.getName());
                return reloadPlugin(info.getName());
            }
            loadSingle(jar);
            LoadedPlugin lp = findNewlyLoaded(jar);
            if (lp != null) {
                lp.instance.onLoad(lp.context);
                enableSingle(lp);
                LOG.info("插件加载并启用成功: " + lp.info);
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOG.error("加载插件失败: " + jar.getName(), t);
            return false;
        }
    }

    /** 获取 plugins 目录下可用的未加载 jar 文件列表。 */
    public List<File> getAvailableJars() {
        if (!initialized || pluginsDir == null) return Collections.emptyList();
        File[] jars = pluginsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".jar"));
        if (jars == null) return Collections.emptyList();
        Set<String> loadedJars = loaded.values().stream()
                .map(lp -> lp.jarFile.getName())
                .collect(Collectors.toSet());
        return Arrays.stream(jars)
                .filter(j -> !loadedJars.contains(j.getName()))
                .collect(Collectors.toList());
    }

    /** 获取所有已加载插件的详细状态列表。 */
    public List<Map<String, Object>> getPluginStatuses() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LoadedPlugin lp : loaded.values()) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", lp.info.getName());
            status.put("version", lp.info.getVersion());
            status.put("author", lp.info.getAuthor());
            status.put("description", lp.info.getDescription());
            status.put("mainClass", lp.info.getMainClass());
            status.put("jarFile", lp.jarFile.getName());
            status.put("enabled", lp.enabled);
            status.put("loaded", true);
            result.add(status);
        }
        // 也列出可用但未加载的 jar
        for (File jar : getAvailableJars()) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("jarFile", jar.getName());
            status.put("loaded", false);
            status.put("enabled", false);
            // 尝试读取基本信息
            try {
                PluginInfo info = readPluginJson(jar);
                if (info != null) {
                    status.put("name", info.getName());
                    status.put("version", info.getVersion());
                    status.put("author", info.getAuthor());
                    status.put("description", info.getDescription());
                    status.put("mainClass", info.getMainClass());
                } else {
                    status.put("name", jar.getName().replace(".jar", ""));
                    status.put("version", "?");
                    status.put("author", "?");
                    status.put("description", "");
                }
            } catch (Exception e) {
                status.put("name", jar.getName().replace(".jar", ""));
            }
            result.add(status);
        }
        return result;
    }

    // =================== 文件监听（热替换） ===================

    private ScheduledExecutorService fileWatcher;

    /**
     * 启动插件目录文件监听器，检测 jar 文件变更并自动热重载。
     * 检测到 jar 文件被修改（更新）时自动触发 reloadPlugin()。
     */
    public synchronized void startFileWatcher() {
        if (fileWatcher != null) return;
        if (!initialized) {
            LOG.warn("PluginManager 未初始化，无法启动文件监听");
            return;
        }
        fileWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plugin-file-watcher");
            t.setDaemon(true);
            return t;
        });

        final Map<String, Long> lastModified = new HashMap<>();
        // 初始化记录所有 jar 的修改时间
        File[] jars = pluginsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                lastModified.put(jar.getName(), jar.lastModified());
            }
        }

        fileWatcher.scheduleWithFixedDelay(() -> {
            try {
                File[] current = pluginsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".jar"));
                if (current == null) return;

                Set<String> currentNames = new HashSet<>();
                for (File jar : current) {
                    currentNames.add(jar.getName());
                    Long prev = lastModified.get(jar.getName());
                    if (prev == null) {
                        // 新增的 jar
                        LOG.info("[文件监听] 检测到新插件: " + jar.getName());
                        // 稍等确保文件写入完成
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        loadPlugin(jar);
                        lastModified.put(jar.getName(), jar.lastModified());
                    } else if (jar.lastModified() > prev) {
                        // 修改过的 jar
                        LOG.info("[文件监听] 检测到插件更新: " + jar.getName());
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        // 查找对应插件名
                        String pluginName = findPluginNameByJar(jar.getName());
                        if (pluginName != null) {
                            reloadPlugin(pluginName);
                        }
                        lastModified.put(jar.getName(), jar.lastModified());
                    }
                }
                // 检测删除的 jar
                for (String name : new HashSet<>(lastModified.keySet())) {
                    if (!currentNames.contains(name)) {
                        LOG.info("[文件监听] 检测到插件删除: " + name);
                        String pluginName = findPluginNameByJar(name);
                        if (pluginName != null) {
                            unloadPlugin(pluginName);
                        }
                        lastModified.remove(name);
                    }
                }
            } catch (Exception e) {
                LOG.error("[文件监听] 异常", e);
            }
        }, 3, 3, TimeUnit.SECONDS);

        LOG.info("插件文件监听已启动（扫描间隔 3 秒）");
    }

    /** 停止文件监听。 */
    public synchronized void stopFileWatcher() {
        if (fileWatcher != null) {
            fileWatcher.shutdown();
            fileWatcher = null;
            LOG.info("插件文件监听已停止");
        }
    }

    public boolean isFileWatcherRunning() {
        return fileWatcher != null && !fileWatcher.isShutdown();
    }

    private String findPluginNameByJar(String jarName) {
        for (LoadedPlugin lp : loaded.values()) {
            if (lp.jarFile.getName().equals(jarName)) {
                return lp.info.getName();
            }
        }
        // 回退：尝试从 jar 读取 plugin.json
        File jar = new File(pluginsDir, jarName);
        if (jar.exists()) {
            PluginInfo info = readPluginJson(jar);
            if (info != null) return info.getName();
        }
        return null;
    }

    private LoadedPlugin findNewlyLoaded(File jar) {
        for (LoadedPlugin lp : loaded.values()) {
            if (lp.jarFile.equals(jar)) return lp;
        }
        return null;
    }

    // =================== 内部实现 ===================

    private void loadSingle(File jar) throws Exception {
        // 1. 读取 plugin.json（若存在，先检查名称冲突避免无谓创建 ClassLoader）
        PluginInfo info = readPluginJson(jar);
        if (info != null && loaded.containsKey(info.getName())) {
            LOG.warn("插件名称冲突，跳过: " + info.getName());
            return;
        }

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
                closeClassLoader(cl);
                throw new RuntimeException("jar 中未找到 plugin.json，也未发现实现 Plugin 接口的类");
            }
            info = buildPluginInfoFromClass(cl, mainClassName);
            // 回退路径也需要检查名称冲突
            if (loaded.containsKey(info.getName())) {
                LOG.warn("插件名称冲突，跳过: " + info.getName());
                closeClassLoader(cl);
                return;
            }
        }

        // 4. 实例化插件主类
        Class<?> mainCls = Class.forName(mainClassName, true, cl);
        if (!Plugin.class.isAssignableFrom(mainCls)) {
            closeClassLoader(cl);
            throw new RuntimeException("主类 " + mainClassName + " 未实现 Plugin 接口");
        }
        Plugin instance = (Plugin) mainCls.getDeclaredConstructor().newInstance();

        // 5. 准备插件数据目录和上下文
        File dataFolder = new File(pluginsDir, info.getName());
        PluginContext ctx = new PluginContext(info, dataFolder, cl);

        // 5.1 自动加载插件翻译文件
        ctx.loadLang();

        // 6. 记录
        LoadedPlugin lp = new LoadedPlugin(info, instance, ctx, jar);
        loaded.put(info.getName(), lp);
        LOG.info("发现插件: " + info);
    }

    /** 从 classLoaders 列表中移除并关闭指定的 ClassLoader。 */
    private void closeClassLoader(PluginClassLoader cl) {
        classLoaders.remove(cl);
        try { cl.close(); } catch (Exception ignored) { }
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
        private volatile boolean enabled = false;

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
        public boolean isEnabled() { return enabled; }
        void setEnabled(boolean enabled) { this.enabled = enabled; }

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
