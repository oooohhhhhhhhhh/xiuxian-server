package com.mtxgdn.onebot.command.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

import java.util.List;
import com.mtxgdn.i18n.I18n;

public class DebugCommand extends Command {

    public DebugCommand() {
        super(new String[]{"debug", "调试"},
                "快速调试所有指令（仅admin）",
                "/debug <指令名> [参数...]\n/debug list - 列出所有指令\n/debug test <指令名> - 测试指令",
                "管理", "admin.debug", false);

        addRoute(RouteDefinition.get("admin/debug/list", "admin.debug", this::httpListCommands));
        addRoute(RouteDefinition.post("admin/debug/test", "admin.debug", this::httpTestCommand));
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("admin.hd852a51e"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.debug")) {
            ctx.reply(I18n.t("admin.ha43d576a"));
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            showHelp(ctx);
            return;
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();

        switch (sub) {
            case "list":
                listCommands(ctx);
                break;
            case "test":
                testCommand(ctx, parts.length > 1 ? parts[1] : "");
                break;
            case "help":
                showHelp(ctx);
                break;
            default:
                ctx.reply(I18n.t("admin.hcba4cf7b", sub));
        }
    }

    private void showHelp(CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== Debug 指令帮助 =====\n");
        sb.append("/debug list - 列出所有已注册指令\n");
        sb.append("/debug test <指令名> [参数] - 测试指定指令\n");
        sb.append("/debug help - 显示此帮助\n");
        sb.append("==========================");
        ctx.reply(sb.toString());
    }

    private void listCommands(CommandContext ctx) {
        List<Command> allCommands = CommandRegistry.getAllUnique();
        StringBuilder sb = new StringBuilder();
        sb.append("===== 已注册指令列表 =====\n");
        sb.append("共 ").append(allCommands.size()).append(" 个指令\n");

        for (Command cmd : allCommands) {
            sb.append("\n【").append(String.join("/", cmd.getNames())).append("】\n");
            sb.append("  描述: ").append(cmd.getDescription()).append("\n");
            sb.append("  分类: ").append(cmd.getCategory()).append("\n");
            if (cmd.getPermission() != null) {
                sb.append("  权限: ").append(cmd.getPermission()).append("\n");
            }
            sb.append("  用法: ").append(cmd.getUsage()).append("\n");
            List<String> subs = cmd.getSubCommandNames();
            if (!subs.isEmpty()) {
                sb.append("  子命令: ").append(String.join(", ", subs)).append("\n");
            }
        }

        sb.append("==========================");
        ctx.reply(sb.toString());
    }

    private void testCommand(CommandContext ctx, String args) {
        if (args == null || args.isBlank()) {
            ctx.reply(I18n.t("admin.ha5a93f5d"));
            return;
        }

        String[] parts = args.trim().split("\\s+", 2);
        String cmdName = parts[0];
        if (cmdName.startsWith("/")) {
            cmdName = cmdName.substring(1);
        }
        String cmdArgs = parts.length > 1 ? parts[1] : "";

        Command targetCmd = CommandRegistry.get(cmdName);
        if (targetCmd == null) {
            ctx.reply(I18n.t("admin.h1eb8a8fc", cmdName));
            return;
        }

        ctx.reply(I18n.t("admin.hf579788c", cmdName));
        ctx.reply(I18n.t("debug.args", cmdArgs.isEmpty() ? "(无)" : cmdArgs));
        ctx.reply(I18n.t("admin.h3bcc1334"));

        try {
            QqBinding b = ctx.resolveBoundBinding();
            if (b == null) {
                ctx.reply(I18n.t("admin.h32b669a4"));
                return;
            }

            PlayerInfo player = com.mtxgdn.common.service.ServiceRegistry.getPlayerService().getPlayerByUserId(b.getUserId());
            if (player == null) {
                ctx.reply(I18n.t("admin.h61120d94"));
                return;
            }

            DebugCommandContext debugCtx = new DebugCommandContext(ctx.getSenderId(), ctx.getSenderNickname(), cmdArgs, ctx);

            try (java.sql.Connection conn = com.mtxgdn.db.DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    targetCmd.execute(debugCtx);
                } finally {
                    conn.rollback();
                }
            }

            ctx.reply(I18n.t("admin.hdbaa383c"));
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            sb.append("指令测试异常:\n");
            sb.append("错误类型: ").append(e.getClass().getSimpleName()).append("\n");
            sb.append("错误信息: ").append(e.getMessage()).append("\n");
            sb.append("堆栈跟踪:\n");
            for (StackTraceElement ste : e.getStackTrace()) {
                sb.append("  at ").append(ste.toString()).append("\n");
                if (sb.length() > 2000) {
                    sb.append("  ... (截断)");
                    break;
                }
            }
            ctx.reply(sb.toString());
        }
    }

    private static class DebugCommandContext extends CommandContext {

        private final CommandContext originalCtx;

        DebugCommandContext(String senderId, String senderNickname, String arg, CommandContext originalCtx) {
            super(senderId, senderNickname, arg);
            this.originalCtx = originalCtx;
        }

        @Override
        public boolean isGroup() {
            return originalCtx.isGroup();
        }

        @Override
        public void reply(String message) {
            originalCtx.reply("[DEBUG] " + message);
        }

        @Override
        public void replyPrivate(String message) {
            originalCtx.replyPrivate("[DEBUG] " + message);
        }
    }

    private JsonObject httpListCommands(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        JsonArray commands = new JsonArray();

        for (Command cmd : CommandRegistry.getAllUnique()) {
            JsonObject cmdObj = new JsonObject();
            JsonArray names = new JsonArray();
            for (String name : cmd.getNames()) {
                names.add(name);
            }
            cmdObj.add("names", names);
            cmdObj.addProperty("description", cmd.getDescription());
            cmdObj.addProperty("usage", cmd.getUsage());
            cmdObj.addProperty("category", cmd.getCategory());
            if (cmd.getPermission() != null) {
                cmdObj.addProperty("permission", cmd.getPermission());
            }

            JsonArray subCommands = new JsonArray();
            for (String sub : cmd.getSubCommandNames()) {
                subCommands.add(sub);
            }
            cmdObj.add("subCommands", subCommands);

            JsonArray restEndpoints = new JsonArray();
            for (RouteDefinition def : cmd.getRestEndpoints()) {
                JsonObject ep = new JsonObject();
                ep.addProperty("method", def.getMethod());
                ep.addProperty("path", def.getPath());
                if (def.getPermission() != null) {
                    ep.addProperty("permission", def.getPermission());
                }
                restEndpoints.add(ep);
            }
            cmdObj.add("restEndpoints", restEndpoints);

            commands.add(cmdObj);
        }

        result.add("commands", commands);
        result.addProperty("count", commands.size());
        result.addProperty("code", 200);
        return result;
    }

    private JsonObject httpTestCommand(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();

        try {
            JsonObject body = ctx.bodyJson();
            String cmdName = body.has("command") ? body.get("command").getAsString() : "";
            String cmdArgs = body.has("args") ? body.get("args").getAsString() : "";

            if (cmdName == null || cmdName.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.hcb640ff0"));
                return result;
            }

            if (cmdName.startsWith("/")) {
                cmdName = cmdName.substring(1);
            }

            Command targetCmd = CommandRegistry.get(cmdName);
            if (targetCmd == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.hd86857f4", cmdName));
                return result;
            }

            long userId = ctx.userId();
            if (userId == 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.h5af66540"));
                return result;
            }

            PlayerInfo player = com.mtxgdn.common.service.ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
            if (player == null) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("admin.h963c8950"));
                return result;
            }

            StringBuilder output = new StringBuilder();
            DebugRestCommandContext debugCtx = new DebugRestCommandContext(
                    String.valueOf(userId), player.getName(), cmdArgs, output);

            targetCmd.execute(debugCtx);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h3284ab10"));
            result.addProperty("command", cmdName);
            result.addProperty("args", cmdArgs);
            result.addProperty("output", output.toString().trim());

        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.h2872f774", e.getMessage()));

            StringBuilder stackTrace = new StringBuilder();
            for (StackTraceElement ste : e.getStackTrace()) {
                stackTrace.append(ste.toString()).append("\n");
                if (stackTrace.length() > 2000) {
                    stackTrace.append("... (截断)");
                    break;
                }
            }
            result.addProperty("stackTrace", stackTrace.toString());
        }

        return result;
    }

    private static class DebugRestCommandContext extends CommandContext {

        private final StringBuilder output;

        DebugRestCommandContext(String senderId, String senderNickname, String arg, StringBuilder output) {
            super(senderId, senderNickname, arg);
            this.output = output;
        }

        @Override
        public boolean isGroup() {
            return false;
        }

        @Override
        public void reply(String message) {
            if (output.length() > 0) output.append("\n");
            output.append("[DEBUG] ").append(message);
        }

        @Override
        public void replyPrivate(String message) {
            reply(message);
        }
    }
}