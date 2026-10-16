package com.mtxgdn.adapter;

/**
 * 第三方适配器实例接口。
 * <p>
 * 外部插件通过 {@link AdapterFactory} 创建适配器实例并交给
 * {@link AdapterManager} 统一管理（启动/停止/状态查询与内置适配器一致）。
 */
public interface ManagedAdapter {

    /** 适配器类型名（与 adapters.json 中的 type 字段一致）。 */
    String getType();

    /** 启动适配器（建立监听/连接等）。config 为 adapters.json 中该实例的配置。 */
    void start(AdapterConfig config) throws Exception;

    /** 停止适配器并释放资源（应可重复调用）。 */
    void stop();
}
