package com.mtxgdn.plugin.builtin;

import com.mtxgdn.adapter.AdapterManager;
import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 内置“适配器通道”插件：把 config/adapters.json 中的消息通道类型注册为内置插件，
 * 使其出现在插件管理页，可在运行时对整个通道（该类型下的全部实例）启停。
 * <p>
 * 语义说明：
 * <ul>
 *   <li>本插件是“类型级总开关”（built_in_plugins.&lt;type&gt;）；实例级 enabled
 *       仍由 adapters.json 控制，两者都为 true 时实例才会运行。</li>
 *   <li>服务器启动阶段 enableAll 先于适配器系统就绪，故 onEnable/onDisable
 *       仅在 {@link AdapterManager#isBootStarted()} 之后才真正启动/停止通道；
 *       启动期的实例启停由 Main 中的 {@code AdapterManager.startAll()} 完成。</li>
 * </ul>
 */
public final class AdapterChannelPlugin extends BuiltInPlugin {

    private final String type;
    private final String title;
    private final String description;

    public AdapterChannelPlugin(String type, String title, String description) {
        this.type = type;
        this.title = title;
        this.description = description;
    }

    @Override
    public String getName() {
        return type;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return description;
    }

    /** 类别：消息适配器通道（区别于玩法模块）。 */
    @Override
    public String getCategory() {
        return "adapter";
    }

    /** 供日志/UI 展示的类型标题。 */
    public String getTitle() {
        return title;
    }

    @Override
    public void onEnable(PluginContext context) {
        if (AdapterManager.isBootStarted()) {
            AdapterManager.startType(type);
        }
    }

    @Override
    public void onDisable(PluginContext context) {
        if (AdapterManager.isBootStarted()) {
            AdapterManager.stopType(type);
        }
    }
}
