package com.mtxgdn.common.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandRegistry {

    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(CommandRegistry.class);

    private static final Map<String, Command> commands = new LinkedHashMap<>();

    private CommandRegistry() {
    }

    /**
     * 注册一个指令。构造 {@link Command} 时自动调用。
     * <p>
     * 同名指令会被后者覆盖，而覆盖结果取决于类扫描顺序（目录条目顺序 / jar 条目顺序
     * 均不保证稳定），因此这里对「同一个名字被两个不同 Command 类注册」显式告警，
     * 避免出现「换个环境启动就变成另一个指令生效」的静默故障。
     * <p>
     * 同一个类重复注册（例如测试里重复扫描、插件热重载）不算冲突，不告警。
     */
    public static void register(Command command) {
        for (String name : command.getNames()) {
            String key = name.toLowerCase();
            Command previous = commands.put(key, command);
            if (previous != null && previous.getClass() != command.getClass()) {
                LOG.warn("指令名冲突: \"" + name + "\" 同时被 "
                        + previous.getClass().getName() + " 与 " + command.getClass().getName()
                        + " 注册；后者生效，前者无法再通过该名字触发（请为其中一个改名）");
            }
        }
    }

    /** 取消注册一个命令（通过命令实体的所有名称移除）。 */
    public static void unregister(Command command) {
        if (command == null) return;
        for (String name : command.getNames()) {
            commands.remove(name.toLowerCase(), command);
        }
    }

    /**
     * 获取命令。若命令所属的内置玩法模块已被停用，视同不存在（返回 null），
     * 使所有指令入口（OneBot/QQ/MC 适配器）统一生效。
     */
    public static Command get(String name) {
        Command command = commands.get(name.toLowerCase());
        if (command == null) return null;
        return command.isModuleEnabled() ? command : null;
    }

    /** 绕过模块门控直接查询（用于向玩家提示“该玩法已停用”）。 */
    public static Command getRaw(String name) {
        return commands.get(name.toLowerCase());
    }

    public static List<Command> getAllUnique() {
        List<Command> unique = new ArrayList<>();
        for (Command cmd : commands.values()) {
            if (!unique.contains(cmd)) {
                unique.add(cmd);
            }
        }
        return unique;
    }

    public static int count() {
        return getAllUnique().size();
    }

    public static void clear() {
        commands.clear();
    }
}
