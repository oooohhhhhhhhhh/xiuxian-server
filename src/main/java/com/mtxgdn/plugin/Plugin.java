package com.mtxgdn.plugin;

/**
 * 插件基接口。所有插件主类必须实现此接口。
 * <p>
 * 生命周期:
 * <pre>
 *   onLoad()    —— 加载阶段：可读取配置，准备数据
 *   onEnable()  —— 启用阶段：在此注册命令/物品/事件/秘境等
 *   onDisable() —— 停用阶段：在此释放资源
 * </pre>
 */
public interface Plugin {

    /** 插件加载时调用（在服务端注册之前）。 */
    default void onLoad(PluginContext context) { }

    /** 插件启用时调用（在此注册各种扩展内容）。 */
    default void onEnable(PluginContext context) { }

    /** 插件停用/服务器关闭时调用。 */
    default void onDisable(PluginContext context) { }
}
