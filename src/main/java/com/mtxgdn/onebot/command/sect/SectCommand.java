package com.mtxgdn.onebot.command.sect;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.SectService;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public class SectCommand extends Command {
    private static final SectService sectService = new SectService();

    public SectCommand() {
        super(new String[]{"sect", "宗门"},
                "宗门管理：输入 /宗门 help 查看所有子命令",
                "/宗门 <子命令> [参数]", "宗门", "game.sect.manage");

        // ======== OneBot 子命令（仅 OneBot）========
        registerSub(new String[]{"create", "创建"}, this::handleCreate);
        registerSub(new String[]{"join", "加入"}, this::handleJoin);
        registerSub(new String[]{"list", "列表"}, this::handleList);
        registerSub(new String[]{"info", "信息"}, this::handleInfo);
        registerSub(new String[]{"members", "成员"}, this::handleMembers);
        registerSub(new String[]{"apply", "申请"}, this::handleApply);
        registerSub(new String[]{"approve", "通过"}, this::handleApprove);
        registerSub(new String[]{"reject", "拒绝"}, this::handleReject);
        registerSub(new String[]{"leave", "退出"}, this::handleLeave);
        registerSub(new String[]{"kick", "踢出"}, this::handleKick);
        registerSub(new String[]{"appoint", "任命"}, this::handleAppoint);
        registerSub(new String[]{"donate", "捐献"}, this::handleDonate);
        registerSub(new String[]{"warehouse", "仓库"}, this::handleWarehouse);
        registerSub(new String[]{"take", "取出"}, this::handleTake);
        registerSub(new String[]{"disband", "解散"}, this::handleDisband);
        registerSub(new String[]{"top", "排行"}, this::handleTop);
        registerSub(new String[]{"pending", "申请列表"}, this::handlePendingList);
        registerSub(new String[]{"levelup", "升级"}, this::handleLevelUp);
        registerSub(new String[]{"transfer", "转让"}, this::handleTransfer);
        registerSub(new String[]{"war", "宣战"}, this::handleWar);
        registerSub(new String[]{"help", "帮助", "?"}, this::handleHelp);

        // ======== HTTP 接口（仅 HTTP，不出现在 OneBot 帮助）========
        addRoute(RouteDefinition.get("sect/list", "game.sect.manage", this::handleListHttp));
        addRoute(RouteDefinition.get("sect/info", "game.sect.manage", this::handleMySectHttp));
        addRoute(RouteDefinition.get("sect/info/{sectId}", "game.sect.manage", this::handleSectInfoHttp));
    }

    // ==================== OneBot 默认行为 ====================

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        ctx.reply(buildOverview(p));
    }

    @Override
    protected void onUnknown(CommandContext ctx, PlayerInfo p, String sub, String[] parts) {
        ctx.reply(buildOverview(p));
    }

    // ==================== OneBot 子命令处理器 ====================

    private void handleHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        ctx.reply(buildHelp());
    }

    private void handleCreate(CommandContext ctx, PlayerInfo p, String[] parts) {
        String name = parts.length > 1 ? parts[1].trim() : "";
        String desc = parts.length > 2 ? parts[2].trim() : "";
        if (name.isEmpty()) { ctx.reply("用法: /宗门 create <宗门名> [描述]"); return; }
        var result = sectService.createSect(p.getId(), name, desc);
        ctx.reply((String) result.get("message"));
    }

    private void handleJoin(CommandContext ctx, PlayerInfo p, String[] parts) {
        String name = parts.length > 1 ? parts[1].trim() : "";
        if (name.isEmpty()) { ctx.reply("用法: /宗门 join <宗门名>"); return; }
        Sect sect = sectService.getSectByName(name);
        if (sect == null) { ctx.reply("找不到宗门【" + name + "】"); return; }
        String msg = parts.length > 2 ? parts[2] : "";
        var result = sectService.applyToSect(p.getId(), sect.getId(), msg);
        ctx.reply((String) result.get("message"));
    }

    private void handleList(CommandContext ctx, PlayerInfo p, String[] parts) {
        List<Sect> sects = sectService.getAllSects();
        if (sects.isEmpty()) { ctx.reply("天下尚无宗门。\n使用 /宗门 create <名称> 开创一个宗门！"); return; }
        StringBuilder sb = new StringBuilder("===== 宗门列表（共 ").append(sects.size()).append(" 个）=====\n");
        int rank = 1;
        for (Sect s : sects) {
            sb.append(String.format("%d. 【%s】 宗主:%s  声望:%d  成员:%d/%d\n",
                    rank++, s.getName(), s.getLeaderName() != null ? s.getLeaderName() : "未知",
                    s.getPrestige(), s.getMemberCount(), Sect.getMaxMembersForLevel(s.getLevel())));
        }
        ctx.reply(sb.toString());
    }

    private void handleInfo(CommandContext ctx, PlayerInfo p, String[] parts) {
        Sect sect;
        if (parts.length > 1 && !parts[1].isEmpty()) {
            sect = sectService.getSectByName(parts[1].trim());
        } else {
            sect = sectService.getPlayerSect(p.getId());
        }
        if (sect == null) { ctx.reply("宗门不存在或你尚未加入宗门。\n使用 /宗门 list 查看所有宗门"); return; }

        SectMember me = sectService.getMember(sect.getId(), p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("===== 【").append(sect.getName()).append("】 =====\n");
        sb.append("描述: ").append(sect.getDescription()).append("\n");
        sb.append("宗主: ").append(sect.getLeaderName() != null ? sect.getLeaderName() : "未知").append("\n");
        sb.append("等级: ").append(sect.getLevel()).append("级\n");
        sb.append("声望: ").append(sect.getPrestige()).append("\n");
        sb.append("成员: ").append(sect.getMemberCount()).append("/").append(Sect.getMaxMembersForLevel(sect.getLevel())).append("\n");
        if (me != null) {
            sb.append("你的职位: ").append(SectMember.getRoleDisplayName(me.getRole()));
            sb.append("  贡献: ").append(me.getContribution()).append("\n");
        }
        if (me == null && sectService.getPlayerSect(p.getId()) == null) {
            sb.append("\n使用 /宗门 join ").append(sect.getName()).append(" 申请加入");
        }
        ctx.reply(sb.toString());
    }

    private void handleMembers(CommandContext ctx, PlayerInfo p, String[] parts) {
        Sect sect = sectService.getPlayerSect(p.getId());
        if (sect == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectMember> members = sectService.getSectMembers(sect.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("===== 【").append(sect.getName()).append("】成员（共 ").append(members.size()).append(" 人）=====\n");
        int index = 1;
        for (SectMember m : members) {
            String role = SectMember.getRoleDisplayName(m.getRole());
            String realmName = m.getPlayerRealmName();
            if (realmName == null || realmName.isEmpty()) {
                realmName = "练气" + m.getPlayerRealm() + "层";
            }
            sb.append(String.format("%d. %s [%s] [%s]  贡献:%d\n",
                    index++, m.getPlayerName(), role, realmName, m.getContribution()));
        }
        ctx.reply(sb.toString());
    }

    private void handleApply(CommandContext ctx, PlayerInfo p, String[] parts) {
        String name = parts.length > 1 ? parts[1].trim() : "";
        if (name.isEmpty()) { ctx.reply("用法: /宗门 apply <宗门名>"); return; }
        Sect sect = sectService.getSectByName(name);
        if (sect == null) { ctx.reply("找不到宗门【" + name + "】"); return; }
        var result = sectService.applyToSect(p.getId(), sect.getId(), "");
        ctx.reply((String) result.get("message"));
    }

    private void handlePendingList(CommandContext ctx, PlayerInfo p, String[] parts) {
        SectMember member = sectService.getPlayerMember(p.getId());
        if (member == null) { ctx.reply("你还没有加入宗门"); return; }
        if (!member.canManage()) { ctx.reply("只有宗主和长老才能查看申请列表"); return; }

        List<SectApplication> apps = sectService.getPendingApplications(member.getSectId());
        if (apps.isEmpty()) { ctx.reply("目前没有待处理的入宗申请"); return; }
        StringBuilder sb = new StringBuilder("===== 待处理的入宗申请 =====\n");
        for (int i = 0; i < apps.size(); i++) {
            SectApplication a = apps.get(i);
            sb.append(String.format("%d. %s%s\n", i + 1, a.getPlayerName(),
                    a.getMessage() != null && !a.getMessage().isEmpty() ? " 留言:" + a.getMessage() : ""));
        }
        sb.append("\n使用 /宗门 approve <玩家名> / reject <玩家名> 处理申请");
        ctx.reply(sb.toString());
    }

    private void handleApprove(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /宗门 approve <玩家名>"); return; }
        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectApplication> apps = sectService.getPendingApplications(me.getSectId());
        SectApplication target = null;
        for (SectApplication a : apps) {
            if (a.getPlayerName().equals(targetName)) { target = a; break; }
        }
        if (target == null) { ctx.reply("找不到玩家【" + targetName + "】的申请，请确认名字无误"); return; }
        var result = sectService.approveApplication(p.getId(), target.getId(), true);
        ctx.reply((String) result.get("message"));
    }

    private void handleReject(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /宗门 reject <玩家名>"); return; }
        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectApplication> apps = sectService.getPendingApplications(me.getSectId());
        SectApplication target = null;
        for (SectApplication a : apps) {
            if (a.getPlayerName().equals(targetName)) { target = a; break; }
        }
        if (target == null) { ctx.reply("找不到玩家【" + targetName + "】的申请"); return; }
        var result = sectService.approveApplication(p.getId(), target.getId(), false);
        ctx.reply((String) result.get("message"));
    }

    private void handleLeave(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = sectService.leaveSect(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleKick(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /宗门 kick <玩家名>"); return; }

        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectMember> members = sectService.getSectMembers(me.getSectId());
        SectMember target = null;
        for (SectMember m : members) {
            if (m.getPlayerName().equals(targetName)) { target = m; break; }
        }
        if (target == null) { ctx.reply("找不到玩家【" + targetName + "】"); return; }

        var result = sectService.kickMember(p.getId(), target.getPlayerId());
        ctx.reply((String) result.get("message"));
    }

    private void handleAppoint(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 3) { ctx.reply("用法: /宗门 appoint <玩家名> <副宗主|长老|内门弟子|外门弟子>"); return; }
        String targetName = parts[1].trim();
        String role = parts[2].trim();

        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectMember> members = sectService.getSectMembers(me.getSectId());
        SectMember target = null;
        for (SectMember m : members) {
            if (m.getPlayerName().equals(targetName)) { target = m; break; }
        }
        if (target == null) { ctx.reply("找不到玩家【" + targetName + "】"); return; }

        var result = sectService.appointMember(p.getId(), target.getPlayerId(), role);
        ctx.reply((String) result.get("message"));
    }

    private void handleDonate(CommandContext ctx, PlayerInfo p, String[] parts) {
        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        if (parts.length < 3) { ctx.reply("用法: /宗门 donate <物品key> <数量>"); return; }
        String itemKey = parts[1].trim();
        int quantity;
        try { quantity = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) {
            ctx.reply("数量必须是数字"); return;
        }

        var result = sectService.donateToWarehouse(p.getId(), me.getSectId(), itemKey, quantity);
        ctx.reply((String) result.get("message"));
    }

    private void handleWarehouse(CommandContext ctx, PlayerInfo p, String[] parts) {
        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectWarehouseItem> items = sectService.getWarehouse(me.getSectId());
        if (items.isEmpty()) { ctx.reply("宗门仓库空空如也。\n使用 /宗门 donate <物品key> <数量> 捐献物品"); return; }
        StringBuilder sb = new StringBuilder("===== 宗门仓库 =====\n");
        if (!me.canManage()) {
            sb.append("提示：你取出物品需要消耗贡献值\n\n");
        }
        int i = 1;
        for (SectWarehouseItem item : items) {
            Item it = ItemRegistry.get(item.getItemKey());
            String name = it != null ? it.getName() : item.getItemKey();
            long cost = it != null ? sectService.getItemContributionCost(it) : 50;
            sb.append(String.format("%d. %s x%d", i++, name, item.getQuantity()));
            if (item.getDonatedByName() != null) {
                sb.append(" (捐赠:").append(item.getDonatedByName()).append(")");
            }
            if (!me.canManage()) {
                sb.append(" [贡献值:").append(cost).append("/个]");
            }
            sb.append("\n");
        }
        if (!me.canManage()) {
            sb.append("\n使用 /宗门 take <物品key> <数量> 用贡献值兑换");
        }
        ctx.reply(sb.toString());
    }

    private void handleTake(CommandContext ctx, PlayerInfo p, String[] parts) {
        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        if (parts.length < 3) { ctx.reply("用法: /宗门 take <物品key> <数量>"); return; }
        String itemKey = parts[1].trim();
        int quantity;
        try { quantity = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) {
            ctx.reply("数量必须是数字"); return;
        }
        var result = sectService.withdrawFromWarehouse(p.getId(), me.getSectId(), itemKey, quantity);
        ctx.reply((String) result.get("message"));
    }

    private void handleDisband(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = sectService.disbandSect(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleTop(CommandContext ctx, PlayerInfo p, String[] parts) {
        List<Sect> sects = sectService.getTopSects(10);
        if (sects.isEmpty()) { ctx.reply("天下尚无宗门榜单。快去开创第一个宗门！"); return; }
        StringBuilder sb = new StringBuilder("===== 宗门声望排行 =====\n");
        for (int i = 0; i < sects.size(); i++) {
            Sect s = sects.get(i);
            sb.append(String.format("%d. 【%s】 声望:%d  成员:%d  宗主:%s\n",
                    i + 1, s.getName(), s.getPrestige(), s.getMemberCount(),
                    s.getLeaderName() != null ? s.getLeaderName() : "未知"));
        }
        ctx.reply(sb.toString());
    }

    private void handleLevelUp(CommandContext ctx, PlayerInfo p, String[] parts) {
        var result = sectService.levelUp(p.getId());
        ctx.reply((String) result.get("message"));
    }

    private void handleTransfer(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /宗门 transfer <玩家名>"); return; }

        SectMember me = sectService.getPlayerMember(p.getId());
        if (me == null) { ctx.reply("你还没有加入宗门"); return; }

        List<SectMember> members = sectService.getSectMembers(me.getSectId());
        SectMember target = null;
        for (SectMember m : members) {
            if (m.getPlayerName().equals(targetName)) { target = m; break; }
        }
        if (target == null) { ctx.reply("找不到玩家【" + targetName + "】"); return; }

        var result = sectService.transferLeader(p.getId(), target.getPlayerId());
        ctx.reply((String) result.get("message"));
    }

    private void handleWar(CommandContext ctx, PlayerInfo p, String[] parts) {
        String targetName = parts.length > 1 ? parts[1].trim() : "";
        if (targetName.isEmpty()) { ctx.reply("用法: /宗门 war <宗门名>"); return; }
        Sect targetSect = sectService.getSectByName(targetName);
        if (targetSect == null) { ctx.reply("找不到宗门【" + targetName + "】"); return; }
        var result = sectService.declareWar(p.getId(), targetSect.getId());
        if ((boolean) result.get("success")) {
            ctx.reply((String) result.get("message") + "\n\n" + result.get("battleLog"));
        } else {
            ctx.reply((String) result.get("message"));
        }
    }

    // ==================== HTTP 处理器（仅 HTTP）====================

    private JsonObject handleListHttp(RouteDefinition.RestContext ctx) {
        List<Sect> sects = sectService.getAllSects();
        JsonArray arr = new JsonArray();
        for (Sect s : sects) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.getId());
            o.addProperty("name", s.getName());
            o.addProperty("description", s.getDescription());
            o.addProperty("leaderPlayerId", s.getLeaderPlayerId());
            o.addProperty("leaderName", s.getLeaderName());
            o.addProperty("level", s.getLevel());
            o.addProperty("prestige", s.getPrestige());
            o.addProperty("memberCount", s.getMemberCount());
            o.addProperty("maxMembers", Sect.getMaxMembersForLevel(s.getLevel()));
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("sects", arr);
        return GameMessage.restOk("获取成功", data);
    }

    private JsonObject handleMySectHttp(RouteDefinition.RestContext ctx) {
        Sect sec = sectService.getPlayerSect(ctx.playerId());
        if (sec == null) return GameMessage.restOk("尚未加入宗门", null);
        return buildSectJson(sec, ctx.playerId());
    }

    private JsonObject handleSectInfoHttp(RouteDefinition.RestContext ctx) {
        long sectId = ctx.pathParamLong("sectId");
        Sect sec = sectService.getSectById(sectId);
        if (sec == null) return GameMessage.restError(400, "宗门不存在");
        return buildSectJson(sec, ctx.playerId());
    }

    // ==================== 帮助文本 ====================

    private String buildOverview(PlayerInfo p) {
        Sect mySect = sectService.getPlayerSect(p.getId());
        if (mySect != null) {
            SectMember me = sectService.getMember(mySect.getId(), p.getId());
            StringBuilder sb = new StringBuilder();
            sb.append("════ 【").append(mySect.getName()).append("】 ════\n");
            sb.append("声望: ").append(mySect.getPrestige());
            sb.append("  成员: ").append(mySect.getMemberCount()).append("/").append(Sect.getMaxMembersForLevel(mySect.getLevel()));
            if (me != null) {
                sb.append("  职位: ").append(SectMember.getRoleDisplayName(me.getRole()));
                sb.append("  贡献: ").append(me.getContribution());
            }
            sb.append("\n\n▍快捷操作\n");
            sb.append("  /宗门 info        宗门详情\n");
            sb.append("  /宗门 members     成员列表\n");
            sb.append("  /宗门 donate      捐献物品\n");
            sb.append("  /宗门 warehouse   宗门仓库\n");
            if (me != null && me.canManage()) {
                sb.append("  /\u5b97\u95e8 pending     \u5ba1\u6279\u7533\u8bf7\n");
                sb.append("  /\u5b97\u95e8 kick        \u7ba1\u7406\u6210\u5458\n");
            }
            sb.append("  /\u5b97\u95e8 take        \u53d6\u51fa\u7269\u54c1");
            if (me != null && !me.canManage()) {
                sb.append("(\u6d88\u8017\u8d21\u732e\u503c)");
            }
            sb.append("\n");
            if (me != null && me.isLeader()) {
                sb.append("  /\u5b97\u95e8 levelup     \u5347\u7ea7\u5b97\u95e8\n");
                sb.append("  /\u5b97\u95e8 transfer    \u8f6c\u8ba9\u5b97\u4e3b\n");
                sb.append("  /\u5b97\u95e8 war         \u5ba3\u6218\u5b97\u95e8\n");
            }
            sb.append("\n输入 /宗门 help 查看全部命令");
            return sb.toString();
        } else {
            return """
════ 宗门系统 ════

▍宗门大厅
  /宗门 list           浏览天下宗门
  /宗门 top            宗门声望排行
  /宗门 create <名称>  开创宗门（需金丹期+500灵石）

▍加入宗门
  /宗门 apply  <名称>  申请加入
  /宗门 join   <名称>  同上
  /宗门 info   <名称>  查看宗门详情

输入 /宗门 help 查看全部命令""";
        }
    }

    private String buildHelp() {
        return """
===== 宗门系统 =====
/宗门 create <名称> [描述]  创建宗门（需要金丹期以上，500灵石）
/宗门 join <宗门名>          申请加入宗门
/宗门 list                   宗门列表
/宗门 info [名称]            查看宗门信息（不填则查看自己的宗门）
/宗门 members                查看宗门成员
/宗门 apply <宗门名>         申请加入宗门
/宗门 pending                查看待处理申请（宗主/长老）
/宗门 approve <玩家名>       通过申请
/宗门 reject <玩家名>        拒绝申请
/宗门 leave                  退出宗门
/宗门 kick <玩家名>          踢出成员（宗主/长老）
/宗门 appoint <玩家名> <副宗主|长老|内门弟子|外门弟子>  任命职位（宗主可任命所有，副宗主可任命长老及以下，长老可任命内门外门）
/宗门 transfer <玩家名>      转让宗主之位（宗主，需200灵石）
/宗门 levelup                升级宗门（宗主，消耗声望）
/宗门 war <宗门名>           向其他宗门宣战（宗主，消耗1000声望+300灵石）
/宗门 donate <物品key> <数量>  向宗门仓库捐献
/宗门 warehouse              查看宗门仓库
/宗门 take <物品key> <数量>  从仓库取出（宗主/长老）
/宗门 disband                解散宗门（宗主）
/宗门 top                    宗门排行""";
    }

    private JsonObject buildSectJson(Sect sect, int playerId) {
        JsonObject data = new JsonObject();
        data.addProperty("id", sect.getId());
        data.addProperty("name", sect.getName());
        data.addProperty("description", sect.getDescription());
        data.addProperty("leaderPlayerId", sect.getLeaderPlayerId());
        data.addProperty("leaderName", sect.getLeaderName());
        data.addProperty("level", sect.getLevel());
        data.addProperty("prestige", sect.getPrestige());
        data.addProperty("memberCount", sect.getMemberCount());
        data.addProperty("maxMembers", Sect.getMaxMembersForLevel(sect.getLevel()));
        SectMember me = sectService.getMember(sect.getId(), playerId);
        if (me != null) {
            data.addProperty("myRole", me.getRole());
            data.addProperty("myRoleDisplay", SectMember.getRoleDisplayName(me.getRole()));
            data.addProperty("myContribution", me.getContribution());
        }
        return GameMessage.restOk("获取成功", data);
    }
}
