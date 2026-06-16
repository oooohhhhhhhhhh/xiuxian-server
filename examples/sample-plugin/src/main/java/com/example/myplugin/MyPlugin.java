package com.example.myplugin;

import com.example.myplugin.command.HelloCommand;
import com.example.myplugin.item.DemoItem;
import com.mtxgdn.plugin.Plugin;
import com.mtxgdn.plugin.PluginContext;
import com.mtxgdn.plugin.PluginMeta;

/**
 * 插件主类。
 * 通过 @PluginMeta 注解声明元数据（也可以不写注解、完全使用 plugin.json）。
 */
@PluginMeta(name = "示例插件", version = "1.0.0", author = "开发者",
        description = "一个最简单的示例插件")
public class MyPlugin implements Plugin {

    @Override
    public void onLoad(PluginContext context) {
        // 预加载阶段，可读取配置
        context.getLogger().info("示例插件正在加载...");
    }

    @Override
    public void onEnable(PluginContext context) {
        // 启用阶段：在此注册命令、物品等
        context.registerCommand(new HelloCommand());
        context.registerItem(new DemoItem());
        context.getLogger().info("示例插件启用成功！");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("示例插件已停用。");
    }
}