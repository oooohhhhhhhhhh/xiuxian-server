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

    private static final Map<String, String> CATEGORY_ICONS = new LinkedHashMap<>();
    private static final Map<String, String> CATEGORY_COLORS = new LinkedHashMap<>();
    static {
        CATEGORY_ICONS.put("账号", "👤");
        CATEGORY_ICONS.put("修炼", "🧘");
        CATEGORY_ICONS.put("战斗", "⚔️");
        CATEGORY_ICONS.put("探索", "🗺️");
        CATEGORY_ICONS.put("经济", "💰");
        CATEGORY_ICONS.put("社交", "👥");
        CATEGORY_ICONS.put("洞府", "🏠");
        CATEGORY_ICONS.put("宗门", "🏯");
        CATEGORY_ICONS.put("管理", "⚙️");
        CATEGORY_ICONS.put("阵法", "🔮");
        CATEGORY_ICONS.put("任务", "📋");

        CATEGORY_COLORS.put("账号", "「");
        CATEGORY_COLORS.put("修炼", "『");
        CATEGORY_COLORS.put("战斗", "【");
        CATEGORY_COLORS.put("探索", "《");
        CATEGORY_COLORS.put("经济", "「");
        CATEGORY_COLORS.put("社交", "『");
        CATEGORY_COLORS.put("洞府", "【");
        CATEGORY_COLORS.put("宗门", "《");
        CATEGORY_COLORS.put("管理", "「");
        CATEGORY_COLORS.put("阵法", "『");
        CATEGORY_COLORS.put("任务", "【");
    }

    public HelpCommand() {
        super(new String[]{"帮助", "help"},
                "查看所有可用指令",
                "/帮助",
                "账号");
    }

    @Override
    public void execute(CommandContext ctx) {
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

        Map<String, List<Command>> categories = new LinkedHashMap<>();
        int totalCmds = 0;
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (!cmd.shouldShowInHelp(userId)) continue;
            categories.computeIfAbsent(cmd.getCategory(), k -> new ArrayList<>()).add(cmd);
            totalCmds++;
        }

        List<String> orderedCats = new ArrayList<>(categories.keySet());
        orderedCats.sort(Comparator.comparingInt(cat -> {
            List<Command> cmds = categories.get(cat);
            return cmds.isEmpty() ? Integer.MAX_VALUE : cmds.get(0).getCategoryOrder();
        }));

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════╗\n");
        sb.append("║            🎮 修仙世界 · 指令大全             ║\n");
        sb.append("╠══════════════════════════════════════════════╣\n");
        sb.append("║  ✨ 共 ").append(padLeft(String.valueOf(totalCmds), 2)).append(" 条指令 · ").append(categories.size()).append(" 大分类  ✨\n");
        sb.append("╚══════════════════════════════════════════════╝\n");

        for (String cat : orderedCats) {
            List<Command> cmds = categories.get(cat);
            if (cmds.isEmpty()) continue;
            String icon = CATEGORY_ICONS.getOrDefault(cat, "📌");
            String prefix = CATEGORY_COLORS.getOrDefault(cat, "「");
            String suffix = getSuffix(prefix);
            
            sb.append("\n").append(icon).append(" ").append(prefix).append(cat).append(suffix).append(" (").append(cmds.size()).append(")\n");
            sb.append("┌──────────────────────────────────────────────┐\n");
            
            for (Command cmd : cmds) {
                sb.append(formatCommand(cmd)).append("\n");
            }
            
            sb.append("└──────────────────────────────────────────────┘");
        }

        sb.append("\n\n╔══════════════════════════════════════════════╗\n");
        sb.append("║  💡 使用提示                                 ║\n");
        sb.append("╠══════════════════════════════════════════════╣\n");
        sb.append("║  /帮助 <关键词>  快速搜索相关指令             ║\n");
        sb.append("║  /<指令名> help  查看该指令详细用法          ║\n");
        sb.append("║  /状态           查看角色当前状态            ║\n");
        sb.append("╚══════════════════════════════════════════════╝\n");
        sb.append("                    🌙 修仙愉快 🌙");
        ctx.reply(sb.toString());
    }

    private String getSuffix(String prefix) {
        return switch (prefix) {
            case "「" -> "」";
            case "『" -> "』";
            case "【" -> "】";
            case "《" -> "》";
            default -> "」";
        };
    }

    private String padLeft(String str, int length) {
        if (str.length() >= length) return str;
        return " ".repeat(length - str.length()) + str;
    }

    private String formatCommand(Command cmd) {
        String name = cmd.getNames()[0];
        String desc = cmd.getDescription();
        if (desc == null || desc.isBlank()) desc = "";

        List<String> subs = cmd.getSubCommandNames();
        String subStr = "";
        if (!subs.isEmpty()) {
            subStr = " [" + String.join("|", subs) + "]";
        }

        String fullName = "/" + name + subStr;
        
        if (fullName.length() <= 18) {
            return String.format("│  %-18s ── %s", fullName, desc);
        } else if (fullName.length() <= 30) {
            return String.format("│  %-30s ─ %s", fullName, desc);
        } else {
            return "│  " + fullName + "\n│  " + " ".repeat(18) + "   ─ " + desc;
        }
    }
}
