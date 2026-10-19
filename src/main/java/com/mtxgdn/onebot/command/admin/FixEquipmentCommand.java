package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.service.EquipmentFixService;
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
            ctx.reply(I18n.t("admin.hd852a51e"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.fix.equipment")) {
            ctx.reply(I18n.t("admin.ha43d576a"));
            return;
        }

        ctx.reply(I18n.t("admin.h5c19d9d2"));
        
        EquipmentFixService fixService = new EquipmentFixService();
        fixService.fixEquipmentData();
        
        ctx.reply(I18n.t("admin.h9ff0b1e0"));
    }
}