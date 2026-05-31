package com.mtxgdn.permission;

public enum PermissionCode {

    GAME_PLAYER_INFO("game.player.info", "查看角色信息", "游戏功能"),
    GAME_PLAYER_CREATE("game.player.create", "创建角色", "游戏功能"),
    GAME_CULTIVATE("game.cultivate", "修炼", "游戏功能"),
    GAME_EXPLORE("game.explore", "探索", "游戏功能"),
    GAME_SECRET_REALM("game.secret_realm", "秘境探索", "游戏功能"),
    GAME_REALM_BREAKTHROUGH("game.realm.breakthrough", "境界突破", "游戏功能"),
    GAME_ITEM_USE("game.item.use", "使用物品", "游戏功能"),
    GAME_INVENTORY_VIEW("game.inventory.view", "查看背包", "游戏功能"),
    GAME_ITEM_REGISTRY("game.item.registry", "查看物品图鉴", "游戏功能"),
    GAME_REALM_CONFIG("game.realm.config", "查看境界配置", "游戏功能"),
    GAME_SKILL_LEARN("game.skill.learn", "学习技能", "游戏功能"),
    GAME_ITEM_ADD("game.item.add", "添加物品", "游戏功能"),
    GAME_PVP_CHALLENGE("game.pvp.challenge", "PVP挑战", "游戏功能"),
    GAME_TECHNIQUE_LEARN("game.technique.learn", "学习功法", "游戏功能"),
    GAME_TECHNIQUE_EQUIP("game.technique.equip", "装备功法", "游戏功能"),
    GAME_TECHNIQUE_UPGRADE("game.technique.upgrade", "升级功法", "游戏功能"),
    GAME_CRAFTING_RECIPES("game.crafting.recipes", "查看配方", "游戏功能"),
    GAME_CRAFTING_CRAFT("game.crafting.craft", "制造物品", "游戏功能"),

    QQ_BIND("qq.bind", "绑定QQ", "QQ指令"),
    QQ_UNBIND("qq.unbind", "解绑QQ", "QQ指令"),
    QQ_COMMAND_BASIC("qq.command.basic", "基本查询指令", "QQ指令"),
    QQ_COMMAND_GAME("qq.command.game", "游戏操作指令", "QQ指令"),
    QQ_COMMAND_ADMIN("qq.command.admin", "管理指令", "QQ指令"),

    ADMIN_LOGIN("admin.login", "登录管理后台", "管理后台"),
    ADMIN_STATUS("admin.status", "查看服务器状态", "管理后台"),
    ADMIN_LOGS_VIEW("admin.logs.view", "查看日志", "管理后台"),
    ADMIN_SHUTDOWN("admin.shutdown", "关闭服务器", "管理后台"),
    ADMIN_USERS_MANAGE("admin.users.manage", "管理用户", "管理后台"),
    ADMIN_ROLES_MANAGE("admin.roles.manage", "管理权限", "管理后台"),
    ADMIN_DATABASE_CLEAR_PLAYERS("admin.database.clear_players", "清除玩家数据", "管理后台"),
    ADMIN_DATABASE_RESET_ALL("admin.database.reset_all", "重置全部数据", "管理后台");

    private final String code;
    private final String name;
    private final String category;

    PermissionCode(String code, String name, String category) {
        this.code = code;
        this.name = name;
        this.category = category;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public static PermissionCode fromCode(String code) {
        for (PermissionCode pc : values()) {
            if (pc.code.equals(code)) {
                return pc;
            }
        }
        return null;
    }
}
