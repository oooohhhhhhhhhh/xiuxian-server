package com.mtxgdn.onebot.command;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.onebot.quiz.QuizQuestion;
import com.mtxgdn.onebot.quiz.QuizService;

public class QuizCommand extends Command {

    private static final QuizService quiz = QuizService.getInstance();

    public QuizCommand() {
        super(new String[]{"题库", "quiz"},
                "管理机器人题库 (查看 / 添加 / 删除)",
                "/题库 [列表|查看 <id>|添加 <答案> <题干>|删除 <id>]",
                "账号");

        addRoute(RouteDefinition.onebotOnly("list", (ctx, p, parts) -> onList(ctx)));
        addRoute(RouteDefinition.onebotOnly("show", (ctx, p, parts) -> onShow(ctx, parts)));
        addRoute(RouteDefinition.onebotOnly("add", (ctx, p, parts) -> onAdd(ctx, parts)));
        addRoute(RouteDefinition.onebotOnly("delete", (ctx, p, parts) -> onDelete(ctx, parts)));
    }

    @Override
    protected void onDefault(CommandContext ctx, com.mtxgdn.game.entity.PlayerInfo p) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 题库管理 ═══\n");
        sb.append("题库共有 ").append(quiz.size()).append(" 道题目。\n\n");
        sb.append("子命令:\n");
        sb.append("  /quiz list                      列出所有题目 id 和题干\n");
        sb.append("  /quiz show <id>                 查看指定题目的完整内容\n");
        sb.append("  /quiz add <1~4> <题干>          添加一道新题（数字为正确答案序号）\n");
        sb.append("  /quiz delete <id>               删除指定 id 的题目\n");
        ctx.reply(sb.toString());
    }

    private void onList(CommandContext ctx) {
        java.util.List<QuizQuestion> all = quiz.getAll();
        if (all.isEmpty()) {
            ctx.reply("题库为空。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 题库列表 (共 ").append(all.size()).append(" 题) ═══\n");
        for (QuizQuestion q : all) {
            sb.append("  [").append(q.getId()).append("] ")
                    .append(shortText(q.getQuestion(), 40)).append("\n");
        }
        ctx.reply(sb.toString());
    }

    private void onShow(CommandContext ctx, String[] parts) {
        if (parts.length < 2) {
            ctx.reply("用法: /quiz show <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            ctx.reply("id 必须是数字。");
            return;
        }
        QuizQuestion q = quiz.getById(id);
        if (q == null) {
            ctx.reply("未找到 id=" + id + " 的题目。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("题目 #").append(q.getId()).append("\n");
        sb.append(q.getQuestion()).append("\n");
        sb.append("正确答案: ").append(q.getAnswer());
        ctx.reply(sb.toString());
    }

    private void onAdd(CommandContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.reply("用法: /quiz add <1~4> <题干>");
            return;
        }
        int answer;
        try {
            answer = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            ctx.reply("答案必须是 1~4 的数字。");
            return;
        }
        String questionText = parts[2].trim();
        try {
            int newId = quiz.add(questionText, answer);
            ctx.reply("添加成功，新题目 id=" + newId);
        } catch (IllegalArgumentException e) {
            ctx.reply("添加失败: " + e.getMessage());
        }
    }

    private void onDelete(CommandContext ctx, String[] parts) {
        if (parts.length < 2) {
            ctx.reply("用法: /quiz delete <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            ctx.reply("id 必须是数字。");
            return;
        }
        if (quiz.delete(id)) {
            ctx.reply("已删除 id=" + id + " 的题目。");
        } else {
            ctx.reply("未找到 id=" + id + " 的题目。");
        }
    }

    private static String shortText(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
