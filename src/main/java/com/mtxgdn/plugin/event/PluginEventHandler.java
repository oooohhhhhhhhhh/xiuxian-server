package com.mtxgdn.plugin.event;

/**
 * 事件处理器接口。插件通过注册此接口的实例来监听事件。
 * <p>
 * 简单用法：
 * <pre>
 * context.registerHandler(PluginEvent.Type.COMMAND, event -> {
 *     String cmd = event.get("command");
 *     if ("/你好".equals(cmd)) {
 *         event.get("reply", "道友安好！");
 *     }
 * });
 * </pre>
 */
@FunctionalInterface
public interface PluginEventHandler {

    /**
     * 事件发生时被调用。
     * @param event 事件对象（包含类型、数据、时间戳等）
     */
    void handle(PluginEvent event);
}
