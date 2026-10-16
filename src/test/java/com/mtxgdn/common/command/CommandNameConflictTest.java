package com.mtxgdn.common.command;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令名唯一性回归测试。
 * <p>
 * 背景：{@link CommandRegistry#register(Command)} 用 {@code Map.put} 覆盖同名指令，
 * 而 {@link CommandScanner} 的扫描顺序来自 {@code File.listFiles()} / jar 条目顺序，
 * 两者都不保证稳定。于是「两个指令抢同一个名字」会表现为：
 * <ul>
 *   <li>谁生效取决于运行环境（开发态 {@code target/classes} 与打包后的 jar 可能不同）；</li>
 *   <li>被覆盖的一方彻底不可达，且没有任何报错。</li>
 * </ul>
 * 修复前实际存在 4 组冲突（{@code 发放/give}、{@code 灵根}、{@code 物品列表}、{@code status}），
 * 其中 {@code 灵根}、{@code 物品列表}、{@code status} 是管理指令抢占了玩家指令的名字——
 * 玩家执行 {@code /灵根} 会收到「权限不足」而不是自己的灵根信息。
 * <p>
 * 本测试锁定：① 全部指令名互不重复；② 上述 4 组冲突的归属不再回退。
 */
class CommandNameConflictTest {

    @BeforeAll
    static void scanCommands() {
        // 指令通过构造函数自注册，发现依赖包扫描（与 Main.doMain 一致）
        CommandScanner.scanAndRegister("com.mtxgdn.onebot.command");
        assertTrue(CommandRegistry.count() > 0, "指令扫描失败：未注册任何指令");
    }

    /** 名字（小写） → 声明了该名字的指令类（去重，保留声明顺序）。 */
    private static Map<String, Set<Class<?>>> collectNameOwners() {
        Map<String, Set<Class<?>>> owners = new LinkedHashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            for (String name : cmd.getNames()) {
                owners.computeIfAbsent(name.toLowerCase(), k -> new LinkedHashSet<>())
                        .add(cmd.getClass());
            }
        }
        return owners;
    }

    private static Set<String> namesOf(Class<?> commandClass) {
        Set<String> names = new LinkedHashSet<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (cmd.getClass() == commandClass) {
                for (String name : cmd.getNames()) names.add(name.toLowerCase());
            }
        }
        return names;
    }

    private static Command declaredBy(Class<?> commandClass) {
        for (Command cmd : CommandRegistry.getAllUnique()) {
            if (cmd.getClass() == commandClass) return cmd;
        }
        return null;
    }

    @Test
    @DisplayName("指令名唯一: 任何一个名字都只能被一个 Command 类注册（否则后者覆盖前者且不可达）")
    void testNoDuplicateCommandNames() {
        Map<String, Set<Class<?>>> owners = collectNameOwners();

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Set<Class<?>>> e : owners.entrySet()) {
            if (e.getValue().size() > 1) {
                List<String> simple = new ArrayList<>();
                for (Class<?> c : e.getValue()) simple.add(c.getSimpleName());
                conflicts.add("\n  \"" + e.getKey() + "\" 由 " + simple + " 同时注册");
            }
        }
        assertTrue(conflicts.isEmpty(),
                "存在重复指令名（生效者取决于扫描顺序，另一个永远不可达）:" + conflicts);
    }

    @Test
    @DisplayName("发放/give: 通用资源发放与物品发放必须各用各的名字")
    void testGiveCommandsAreDistinct() {
        assertEquals(new LinkedHashSet<>(Arrays.asList("发放", "give")),
                namesOf(com.mtxgdn.onebot.command.admin.GiveCommand.class),
                "GiveCommand 应只占用 发放/give（它按玩家ID发放金币/灵力/灵石/物品）");

        assertEquals(new LinkedHashSet<>(Arrays.asList("发放物品", "giveitem", "give_item")),
                namesOf(com.mtxgdn.onebot.command.item.ItemGiveCommand.class),
                "ItemGiveCommand 应使用 发放物品/giveitem/give_item（与权限名、admin/items/give 路由一致）");

        // 二者都必须以 admin.items.give 门控：GiveCommand 此前用的是 admin.status，
        // 而 MODERATOR 组默认恰好持有 admin.status —— 版主可凭空刷金币/灵力/灵石/物品。
        for (Class<?> c : Arrays.asList(com.mtxgdn.onebot.command.admin.GiveCommand.class,
                com.mtxgdn.onebot.command.item.ItemGiveCommand.class)) {
            Command cmd = declaredBy(c);
            assertNotNull(cmd, c.getSimpleName() + " 未被扫描到");
            assertEquals("admin.items.give", cmd.getPermission(),
                    c.getSimpleName() + " 必须以 admin.items.give 门控，不得复用 admin.status"
                            + "（MODERATOR 组默认持有 admin.status，会获得刷资源能力）");
        }

        // 名字必须真的指向预期的类，而不只是「声明了不同的名字」
        assertEquals(com.mtxgdn.onebot.command.admin.GiveCommand.class,
                CommandRegistry.getRaw("发放").getClass());
        assertEquals(com.mtxgdn.onebot.command.item.ItemGiveCommand.class,
                CommandRegistry.getRaw("发放物品").getClass());
    }

    @Test
    @DisplayName("管理指令不得抢占玩家指令的名字（灵根 / 物品列表 / status）")
    void testAdminCommandsDoNotShadowPlayerCommands() {
        // 灵根：玩家 /灵根 看自己的灵根，管理用 /修改灵根
        assertEquals(com.mtxgdn.onebot.command.player.SpiritualRootCommand.class,
                CommandRegistry.getRaw("灵根").getClass(),
                "/灵根 必须归玩家侧 SpiritualRootCommand，否则玩家会收到「权限不足」");
        assertEquals(com.mtxgdn.onebot.command.admin.SetRootCommand.class,
                CommandRegistry.getRaw("修改灵根").getClass());

        // 物品列表：玩家 /物品列表 查图鉴，管理用 /全物品列表 分页浏览
        assertEquals(com.mtxgdn.onebot.command.item.ItemMapCommand.class,
                CommandRegistry.getRaw("物品列表").getClass(),
                "/物品列表 必须归玩家侧 ItemMapCommand");
        assertEquals(com.mtxgdn.onebot.command.admin.ItemsQueryCommand.class,
                CommandRegistry.getRaw("全物品列表").getClass());

        // status：玩家 /状态 /status 看角色，管理用 /服务器状态
        assertEquals(com.mtxgdn.onebot.command.player.StatusCommand.class,
                CommandRegistry.getRaw("状态").getClass());
        assertEquals(com.mtxgdn.onebot.command.player.StatusCommand.class,
                CommandRegistry.getRaw("status").getClass(),
                "/status 必须归玩家侧 StatusCommand，否则玩家查角色状态会被「权限不足」挡住");
        assertEquals(com.mtxgdn.onebot.command.admin.StatusCommand.class,
                CommandRegistry.getRaw("服务器状态").getClass());
    }

    @Test
    @DisplayName("冲突修复不得让管理侧失去入口（每个被改名的管理指令仍可达）")
    void testAdminCommandsRemainReachable() {
        Map<String, Class<?>> expected = new HashMap<>();
        expected.put("发放", com.mtxgdn.onebot.command.admin.GiveCommand.class);
        expected.put("发放物品", com.mtxgdn.onebot.command.item.ItemGiveCommand.class);
        expected.put("修改灵根", com.mtxgdn.onebot.command.admin.SetRootCommand.class);
        expected.put("全物品列表", com.mtxgdn.onebot.command.admin.ItemsQueryCommand.class);
        expected.put("服务器状态", com.mtxgdn.onebot.command.admin.StatusCommand.class);

        for (Map.Entry<String, Class<?>> e : expected.entrySet()) {
            Command cmd = CommandRegistry.getRaw(e.getKey());
            assertNotNull(cmd, "指令 /" + e.getKey() + " 未注册");
            assertEquals(e.getValue(), cmd.getClass(), "/" + e.getKey() + " 归属错误");
        }
    }
}
