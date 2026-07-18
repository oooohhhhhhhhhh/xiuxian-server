package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.service.EquipmentFixService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.permission.PermissionService;

public class FixEquipmentCommand extends Command {
    public FixEquipmentCommand() {
        super(new String[]{"修复装备", "fixequip"}, "修复装备数据一致性问题", "/修复装备", "管理", "admin.fix.equipment");
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.fix.equipment")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        ctx.reply("正在检查并修复装备数据一致性，请稍候...");
        
        EquipmentFixService fixService = new EquipmentFixService();
        fixService.fixEquipmentData();
        
        ctx.reply("装备数据修复完成！");
    }
}