package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBinding;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerBindingService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import java.util.*;

public class HelpCommand extends Command {

    public HelpCommand() {
        super(new String[]{"帮助", "help"},
                "查看所有可用指令",
                "/帮助",
                "账号");
    }

    @Override
    public void execute(CommandContext ctx) {
        // 同时查找 QQ 绑定和 MC 绑定
        Long userId = null;
        QqBinding qqBinding = new QqBindingService().findByQq(ctx.getSenderId());
        if (qqBinding != null) {
            userId = qqBinding.getUserId();
        } else {
            MinecraftPlayerBinding mcBinding = new MinecraftPlayerBindingService().findByMcUuid(ctx.getSenderId());
            if (mcBinding != null) {
                userId = mcBinding.getUserId();
            }
        }

        // 按分类动态分组，按 Command.getCategoryOrder() 排序
        Map<String, List<Command>> categories = new LinkedHashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (!cmd.shouldShowInHelp(userId)) continue;
            categories.computeIfAbsent(cmd.getCategory(), k -> new ArrayList<>()).add(cmd);
        }

        // 按优先级排序分类
        List<String> orderedCats = new ArrayList<>(categories.keySet());
        orderedCats.sort(Comparator.comparingInt(cat -> {
            // 取该分类下第一个命令的优先级
            List<Command> cmds = categories.get(cat);
            return cmds.isEmpty() ? Integer.MAX_VALUE : cmds.get(0).getCategoryOrder();
        }));

        StringBuilder sb = new StringBuilder();
        sb.append("════ 修仙世界 · 指令列表 ════\n");

        for (String cat : orderedCats) {
            List<Command> cmds = categories.get(cat);
            if (cmds.isEmpty()) continue;
            sb.append("\n▍").append(cat).append("\n");
            for (Command cmd : cmds) {
                sb.append(formatCommand(cmd)).append("\n");
            }
        }

        sb.append("\n══════════════════════════\n");
        sb.append("提示: 所有指令支持中英文，如 /状态 或 /status\n");
        sb.append("项目地址：https://github.com/oooohhhhhhhhhh/xiuxian-server/");
        ctx.reply(sb.toString());
    }

    private String formatCommand(Command cmd) {
        String name = cmd.getNames()[0];
        String desc = cmd.getDescription();

        // 如果有子命令，紧凑展示
        List<String> subs = cmd.getSubCommandNames();
        if (!subs.isEmpty()) {
            String subStr = String.join("|", subs);
            if (subStr.length() > 24) {
                subStr = subStr.substring(0, 22) + "..";
            }
            name = name + " [" + subStr + "]";
        }

        // 描述可为空时回退
        if (desc == null || desc.isBlank()) desc = "";

        // 固定宽度对齐
        if (name.length() < 13) {
            return String.format("  %-13s %s", "/" + name, desc);
        } else {
            return String.format("  %s  %s", "/" + name, desc);
        }
    }
}
