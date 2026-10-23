package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.service.EquipmentDataChecker;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.i18n.I18n;

public class FixEquipmentCommand extends Command {
    public FixEquipmentCommand() {
        super(new String[]{"修复装备", "fixequip"}, "修复装备数据一致性问题", "/修复装备", "管理", "admin.fix.equipment");
    
        setI18nKeys("cmd.fix_equipment.desc", "cmd.fix_equipment.usage");
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("admin.please_bind_your_account_first_2"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.fix.equipment")) {
            ctx.reply(I18n.t("admin.insufficient_permission_3"));
            return;
        }

        ctx.reply(I18n.t("admin.checking_and_repairing_equipment_data"));
        
        // 注意：这里必须走 forceCheck，否则启动时已经跑过一次的自检会让这条指令变成空操作
        EquipmentDataChecker checker = new EquipmentDataChecker();
        checker.forceCheckEquipmentData();
        
        ctx.reply(I18n.t("admin.equipment_data_repair_complete"));
    }
}