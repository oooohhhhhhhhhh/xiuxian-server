package com.mtxgdn.adapter;

/**
 * 第三方适配器工厂。外部插件在 {@code onEnable} 中通过
 * {@link com.mtxgdn.plugin.PluginContext#registerAdapterFactory(AdapterFactory)}
 * 注册自定义适配器类型，之后 adapters.json 中该 type 的实例即可被
 * {@link AdapterManager} 正常启动/停止，与内置适配器行为一致。
 */
public interface AdapterFactory {

    /** 适配器类型名（adapters.json 中 type 字段使用）。 */
    String getType();

    /** 为一个配置实例创建适配器。 */
    ManagedAdapter create(AdapterConfig config);
}
