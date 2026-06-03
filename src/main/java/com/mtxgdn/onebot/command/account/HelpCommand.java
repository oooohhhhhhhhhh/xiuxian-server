package com.mtxgdn.onebot.command.account;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import java.util.*;

public class HelpCommand extends Command {

    public HelpCommand() {
        super(new String[]{"help", "帮助"},
                "查看所有可用指令",
                "/help",
                "系统");
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        Long userId = b != null ? b.getUserId() : null;
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (!cmd.shouldShowInHelp(userId)) continue;
            String cat = cmd.getCategory();
            categories.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(cmd.getUsage() + "  -  " + cmd.getDescription());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("修仙世界 - QQ Bot 指令列表\n");
        sb.append("==============================\n");
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            sb.append("\n【").append(entry.getKey()).append("】\n");
            for (String line : entry.getValue()) sb.append(line).append("\n");
        }
        sb.append("\n==============================\n");
        sb.append("注册: /register | 绑定: /bind | 解绑: /unbind");
        ctx.reply(sb.toString());
    }
}
