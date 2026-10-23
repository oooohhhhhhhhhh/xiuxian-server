package com.mtxgdn.qq;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.StatsCollector;

/**
 * 消息路由域：把"一条已解析的玩家消息"送到正确的地方 —— 黑名单、离线奖励、
 * 待处理对话流程、指令管线。
 *
 * <p>拆分前 {@code handlePrivateMessage} / {@code handleGroupMessage} /
 * {@code dispatchCommand} 与网关、发送、账号流程挤在一个类里。现在它们的职责很清楚：
 * <b>只做分流</b>，不发消息（交给 {@link QqMessageSender}）、不推进对话状态机
 * （交给 {@link QqAccountFlow}）。</p>
 *
 * <p><b>原样保留的防坑逻辑</b>（一行未改）：</p>
 * <ul>
 *   <li><b>限流</b>：每个 openid 60 秒内最多 10 条指令（{@code RateLimiter.allow("qq:" + senderQq, 10, 60)}），
 *       私聊与群聊各判一次，超限明确回执而不是静默丢弃；</li>
 *   <li><b>黑名单拦截</b>：与 OneBot ws_server 模式一致，命中直接 return（不回执，避免探活）；</li>
 *   <li><b>私聊兜底提示</b>：未识别的非指令文本给出"注册 / 绑定 / /帮助"的下一步指引，
 *       而不是静默忽略；</li>
 *   <li><b>私聊专用指令在群里被拒</b>：{@code isPrivateOnly()} 的指令在群聊里回执
 *       "请私聊机器人使用此指令"，避免账号密码暴露在群里；</li>
 *   <li><b>指令执行离开 worker 线程</b>：{@code CommandExecutor.submit} 投递到独立线程池，
 *       拒绝时回执"服务器繁忙，请稍后再试。"（Grizzly worker 线程被 BCrypt 占满会拖垮所有 REST）；</li>
 *   <li><b>模块停用提示</b>：指令存在但所属内置模块已停用时给出提示，而非静默无响应。</li>
 * </ul>
 *
 * <p>构造器里拿到的 {@code facade} 只用于一件事：构造 {@link OneBotClientCommandContext}。
 * 该上下文会把 session / sender / accountFlow 三个引用存下来，指令执行时再回调，
 * 所以必须传<b>适配器门面本身</b>（拆分前传的就是 {@code this}）。</p>
 */
final class QqMessageRouter {

    private final QqAdapterContext ctx;
    private final QqMessageSender sender;
    private final QqAccountFlow accountFlow;
    private final QqOfficialAdapter facade;

    QqMessageRouter(QqAdapterContext ctx, QqMessageSender sender,
                    QqAccountFlow accountFlow, QqOfficialAdapter facade) {
        this.ctx = ctx;
        this.sender = sender;
        this.accountFlow = accountFlow;
        this.facade = facade;
    }

    // ==================== 消息路由（复用现有业务逻辑） ====================

    void handlePrivateMessage(String senderQq, String senderNickname, String rawMessage) {
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, null);

        // 黑名单拦截（与 OneBot ws_server 模式一致，覆盖 QQ 官方通道）
        if (ctx.blacklistService.isBlacklisted(senderQq)) {
            return;
        }

        QqBinding binding = ctx.bindingService.findByQq(senderQq);
        if (binding != null) {
            processOfflineReward(senderQq, binding.getUserId());
        }

        QqPendingSession pending = ctx.pendingSessions.get(senderQq);
        if (pending != null) {
            accountFlow.handlePendingFlow(senderQq, pending, trimmed);
            return;
        }

        // 私聊里的非指令文本（例如玩家直接发用户名/密码想"登录"）此前被静默忽略，
        // 玩家完全得不到反馈，只能反复重试；这里给出明确的下一步指引。
        if (!isCommand(trimmed)) {
            if (trimmed.isEmpty()) return;
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "未识别的输入。" + (binding == null
                            ? "你还没有绑定账号：\n注册新角色: /注册 <角色名>\n绑定已有账号: /绑定"
                            : "你是已绑定的玩家，发送 /帮助 查看全部指令。"));
            return;
        }

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "操作太频繁，请稍后再试");
            return;
        }
        dispatchCommand(senderQq, senderNickname, null, parsed[0], parsed[1]);
    }

    void handleGroupMessage(Long groupId, String senderQq, String senderNickname, String rawMessage) {
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, groupId);

        // 黑名单拦截（与 OneBot ws_server 模式一致，覆盖 QQ 官方通道）
        if (ctx.blacklistService.isBlacklisted(senderQq)) {
            return;
        }

        QqBinding binding = ctx.bindingService.findByQq(senderQq);
        if (binding != null) {
            processOfflineReward(senderQq, binding.getUserId());
        }

        if (!isCommand(trimmed)) return;

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "操作太频繁，请稍后再试");
            return;
        }

        Command command = CommandRegistry.get(parsed[0]);
        if (command != null && command.isPrivateOnly()) {
            sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "请私聊机器人使用此指令，保护您的账户安全。");
            return;
        }
        dispatchCommand(senderQq, senderNickname, groupId, parsed[0], parsed[1]);
    }

    // ==================== 指令分发 ====================

    private void dispatchCommand(String senderQq, String senderNickname, Long groupId, String cmd, String arg) {
        StatsCollector.getInstance().recordCommand(cmd, groupId);

        // "/cancel" 是向导流程的保留取消词（待处理向导在上游已拦截）；
        // 无待取消操作时给出提示，避免误触其他指令或静默无响应
        if ("cancel".equalsIgnoreCase(cmd)) {
            sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "当前没有待取消的操作。");
            return;
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) {
            // 命令存在但所属内置模块已停用：给出提示而非静默忽略
            Command raw = CommandRegistry.getRaw(cmd);
            if (raw != null && !raw.isModuleEnabled()) {
                sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "该玩法系统已停用，暂时无法使用。");
            }
            return;
        }

        String permission = command.getPermission();
        if (permission != null) {
            QqBinding b = ctx.bindingService.findByQq(senderQq);
            if (b == null) {
                sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId,
                        "请先注册或绑定账号。\n注册: /register <角色名>\n绑定: /bind");
                return;
            }
            if (!PermissionService.hasPermission(b.getUserId(), permission)) {
                sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "权限不足，你无权使用此功能。");
                return;
            }
        }

        OneBotClientCommandContext commandCtx = new OneBotClientCommandContext(
                facade, ctx.selfId, senderQq, senderNickname, groupId, arg, facade, facade,
                ctx.bindingService::findByQq);
        // 插件事件：命令执行
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND)
                        .data("command", cmd)
                        .data("arg", arg == null ? "" : arg)
                        .data("senderQq", senderQq)
                        .data("groupId", groupId == null ? 0L : groupId)
                        .build());
        // 指令执行挪到独立线程池：见 CommandExecutor 的说明。
        // 原来直接跑在 Grizzly worker 线程上，与所有 REST 请求抢同一个池。
        final Command execCmd = command;
        final OneBotClientCommandContext execCtx = commandCtx;
        if (!com.mtxgdn.common.command.CommandExecutor.submit("qq:/" + cmd,
                () -> execCmd.execute(execCtx))) {
            sender.replyToSource(ctx.self, ctx.selfId, senderQq, groupId, "服务器繁忙，请稍后再试。");
        }
    }

    private boolean isCommand(String msg) {
        if (ctx.commandPrefix != null && !ctx.commandPrefix.isEmpty()) {
            return msg.startsWith(ctx.commandPrefix);
        }
        return msg.startsWith("/") || msg.startsWith(".") || msg.startsWith("！") || msg.startsWith("!");
    }

    private String[] parseCommand(String raw) {
        String cmdLine = raw;
        if (ctx.commandPrefix != null && !ctx.commandPrefix.isEmpty()) {
            if (cmdLine.startsWith(ctx.commandPrefix)) {
                cmdLine = "/" + cmdLine.substring(ctx.commandPrefix.length());
            }
        } else {
            if (cmdLine.startsWith(".")) cmdLine = "/" + cmdLine.substring(1);
            if (cmdLine.startsWith("！")) cmdLine = "/" + cmdLine.substring(1);
            if (cmdLine.startsWith("!")) cmdLine = "/" + cmdLine.substring(1);
        }
        String[] parts = cmdLine.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        return new String[]{cmd, arg};
    }

    // ==================== 离线奖励 ====================

    private void processOfflineReward(String senderQq, Long userId) {
        OfflineRewardService offlineService = ServiceRegistry.getOfflineRewardService();
        OfflineRewardService.OfflineRewardResult result = offlineService.processOfflineRewards(userId);

        if (!result.hasReward) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 离线奖励 =====\n");
        sb.append("离线时长: ").append(result.offlineHours).append("小时").append(result.offlineMinutes % 60).append("分钟\n");

        if (result.hpRecovered > 0) {
            sb.append("生命恢复: +").append(result.hpRecovered).append("\n");
        }
        if (result.mpRecovered > 0) {
            sb.append("法力恢复: +").append(result.mpRecovered).append("\n");
        }

        if (result.wasCultivating) {
            sb.append("修炼获得经验: +").append(result.expGained).append("\n");
            if (result.rawExpGained != result.expGained) {
                sb.append("(原始经验: ").append(result.rawExpGained).append(")\n");
            }

            if (result.heartDemonTriggered) {
                sb.append("\n⚠️ 心魔发作！\n");
                sb.append(result.heartDemonNarrative).append("\n");
                sb.append("损失经验: ").append(result.heartDemonExpLost).append("\n");
            }
        }

        sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, sb.toString());
    }
}
