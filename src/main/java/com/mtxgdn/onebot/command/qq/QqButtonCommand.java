package com.mtxgdn.onebot.command.qq;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.qq.QqButton;
import com.mtxgdn.qq.QqOfficialAdapter;

import java.util.ArrayList;
import java.util.List;
import com.mtxgdn.i18n.I18n;

/**
 * /按钮 — QQ 官方适配器「消息交互」按钮演示/工具指令。
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code /按钮} — 发送一组演示按钮（签到 / 状态 / 帮助 / 宗门 信息）</li>
 *   <li>{@code /按钮 <指令串>} — 发送一个执行指定指令的按钮（如 {@code /按钮 签到}）</li>
 * </ul>
 *
 * <p>点击按钮后服务端收到 {@code INTERACTION_CREATE} 事件，按按钮绑定的指令执行。
 * 该指令<b>仅在 QQ 官方适配器运行时显示</b>（/帮助 中隐藏，其他渠道调用提示不支持）。</p>
 */
public class QqButtonCommand extends Command {

    public QqButtonCommand() {
        super(new String[]{"按钮", "button", "互动按钮"},
                "QQ官方消息交互按钮（点击执行指令）",
                "/按钮 [指令串] — 无参数发送演示按钮组，带参数发送单个指令按钮",
                "实用功能",
                "game.player.info");
    }

    /** 仅在 QQ 官方适配器运行（enabled 且已启动）时于 /帮助 中显示 */
    @Override
    public boolean shouldShowInHelp(Long userId) {
        if (com.mtxgdn.adapter.AdapterManager.getFirstQqOfficialAdapter() == null) {
            return false;
        }
        return super.shouldShowInHelp(userId);
    }

    @Override
    public void execute(CommandContext ctx) {
        // 先确认当前通道是 QQ 官方适配器，避免其他渠道用户被要求绑卡后才发现不支持
        if (!(ctx instanceof OneBotClientCommandContext)
                || !(((OneBotClientCommandContext) ctx).getSession() instanceof QqOfficialAdapter)) {
            ctx.reply(I18n.t("qq.h3e5241b0"));
            return;
        }

        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply(I18n.t("qq.hde5e9ffe")); return; }

        // 顶层已确认是 QQ 官方适配器通道
        OneBotClientCommandContext occ = (OneBotClientCommandContext) ctx;
        QqOfficialAdapter qq = (QqOfficialAdapter) occ.getSession();

        String arg = ctx.getArg() == null ? "" : ctx.getArg().trim();
        boolean isGroup = occ.isGroup();

        if (arg.isEmpty()) {
            // 演示按钮组：点击即执行对应指令
            List<List<QqButton>> rows = new ArrayList<>();
            rows.add(List.of(
                    QqButton.of("btn-signin", "✅ 签到", 3, "签到"),
                    QqButton.of("btn-status", "📊 状态", 1, "状态")));
            rows.add(List.of(
                    QqButton.of("btn-daily", "🌤 每日", 4, "每日"),
                    QqButton.of("btn-sect", "🏯 宗门 信息", 2, "宗门 信息")));
            String tip = "点击下方按钮即可快速执行对应指令：\n（按钮数据即指令，回调后自动执行）";
            send(qq, occ, isGroup, tip, rows);
        } else {
            String cmd = arg.startsWith("/") ? arg.substring(1) : arg;
            String label = cmd.length() > 12 ? cmd.substring(0, 12) : cmd;
            List<QqButton> single = List.of(QqButton.of("btn-cmd", "▶ " + label, 1, cmd));
            send(qq, occ, isGroup, "点击按钮执行指令：/" + cmd, List.of(single));
        }
    }

    private void send(QqOfficialAdapter qq, OneBotClientCommandContext occ,
                      boolean isGroup, String text, List<List<QqButton>> rows) {
        if (isGroup) {
            qq.sendGroupMsgWithKeyboard(occ.getGroupId(), text, rows);
        } else {
            qq.sendPrivateMsgWithKeyboard(occ.getSenderId(), text, rows);
        }
    }
}
