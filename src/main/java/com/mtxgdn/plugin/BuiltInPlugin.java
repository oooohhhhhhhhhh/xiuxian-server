package com.mtxgdn.plugin;

/**
 * 内置插件基类。jar 内的玩法模块通过继承此类实现。
 * <p>
 * 与外部插件不同，内置插件：
 * <ul>
 *   <li>直接编译在主 jar 中，无需单独打包</li>
 *   <li>通过 {@code config/plugins.yml} 控制启用/禁用（键 {@code plugins.&lt;name&gt;}）</li>
 *   <li>拥有唯一名称，用于配置和状态查询</li>
 * </ul>
 */
public abstract class BuiltInPlugin implements Plugin {

    /** 获取插件名称（配置键、日志使用）。 */
    public abstract String getName();

    /** 获取插件版本。 */
    public abstract String getVersion();

    /** 获取插件描述。 */
    public String getDescription() {
        return "";
    }

    /** 获取插件类别（WebUI 展示分组用）。玩法模块 gameplay、消息通道 adapter、实验性功能 experimental。 */
    public String getCategory() {
        return "gameplay";
    }

    /**
     * 配置文件中缺失本插件开关时的默认值。
     * <p>绝大多数模块默认启用；实验性插件可覆写为 false，保持“实验性内容默认关闭”的语义。
     */
    public boolean getDefaultEnabled() {
        return true;
    }

    /** 配置键（{@code config/plugins.yml} 中的键名）对应本插件名。 */
    @Override
    public String toString() {
        return getName();
    }
}
