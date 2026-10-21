package com.mtxgdn.plugin.gui;

import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 PluginConfig 转换成完整的插件项目文件。
 *
 * <p>生成物全部基于<b>服务端真实存在的 API</b>：</p>
 * <ul>
 *   <li>主类：{@code implements com.mtxgdn.plugin.Plugin}，用 {@code @PluginMeta} 声明元数据，
 *       生命周期方法签名与 {@link com.mtxgdn.plugin.Plugin} 一致；</li>
 *   <li>物品：{@code extends com.mtxgdn.game.item.Item}，用 {@code ItemEffect} 承载「使用时」的触发器动作；</li>
 *   <li>探索事件：{@code extends com.mtxgdn.game.explorationevent.ExplorationEvent}，
 *       触发器动作写在 {@code execute(...)} 里，由游历系统真实抽取执行；</li>
 *   <li>指令：{@code extends com.mtxgdn.common.command.Command}，覆写 {@code execute(CommandContext)}；</li>
 *   <li>秘境：{@code extends com.mtxgdn.game.secretrealm.SecretRealm}。</li>
 * </ul>
 *
 * <p>以上四类实体的构造器都会自动注册到各自的 Registry，因此生成代码只需实例化，
 * 不需要（也不应该）再重复调用 {@code context.registerItem(...)} 之类的接口——
 * 那会让 Registry 打一条「覆盖」告警。指令例外：{@code CommandRegistry} 对同一个类
 * 的重复注册是幂等的，所以额外调用一次 {@code context.registerCommand(...)} 保持与示例插件一致。</p>
 */
public final class CodeGenerator {

    /** 没有配置 extraInfo 时使用的默认值。 */
    private static final int DEFAULT_ITEM_PRICE = 888;
    private static final int DEFAULT_ITEM_STACK = 1;
    private static final int DEFAULT_ITEM_REALM = 1;
    private static final int DEFAULT_EVENT_WEIGHT = 10;
    private static final int DEFAULT_REALM_REALM = 1;
    private static final long DEFAULT_REALM_COOLDOWN_MS = 180_000L;

    private final PluginConfig config;
    private final Map<String, String> placeholders;
    private final List<String> generatedPaths;

    public CodeGenerator(PluginConfig config) {
        this.config = config;
        this.placeholders = buildPlaceholders(config);
        this.generatedPaths = new ArrayList<>();
    }

    /** 生成所有文件，返回生成的文件清单。 */
    public List<String> generateAll() throws IOException {
        File outDir = new File(config.getOutputDir());
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建输出目录: " + outDir.getAbsolutePath());
        }

        // 先为每个注册项分配唯一的类名（同名 key 也不会互相覆盖文件）
        List<Gen> items = buildEntries(config.getItems(), "Item_");
        List<Gen> events = buildEntries(config.getEvents(), "Event_");
        List<Gen> commands = buildEntries(config.getCommands(), "Cmd_");
        List<Gen> realms = buildEntries(config.getSecretRealms(), "Realm_");

        // ========== 基础文件 ==========
        generatedPaths.add(writeTemplated(outDir, "pom.xml", "pom.xml.template", EscapeMode.XML));
        generatedPaths.add(writeTemplated(outDir, "plugin.json", "plugin.json.template", EscapeMode.JSON));
        generatedPaths.add(writeMainClass(outDir, items, events, commands, realms));

        // ========== 翻译文件 ==========
        String lang = writeLangJson(outDir, items, events, realms);
        if (lang != null && !lang.isEmpty()) {
            generatedPaths.add(lang);
        }

        // ========== 物品 ==========
        for (Gen g : items) {
            generatedPaths.add(writeItemClass(outDir, g));
        }

        // ========== 探索事件 ==========
        for (Gen g : events) {
            generatedPaths.add(writeEventClass(outDir, g));
        }

        // ========== 指令 ==========
        for (Gen g : commands) {
            generatedPaths.add(writeCommandClass(outDir, g));
        }

        // ========== 秘境 ==========
        for (Gen g : realms) {
            generatedPaths.add(writeRealmClass(outDir, g));
        }

        // ========== README ==========
        generatedPaths.add(writeReadme(outDir, items, events, commands, realms));

        return generatedPaths;
    }

    // ==================== 注册项 → 生成信息 ====================

    /** 一个注册项的生成信息（类名、字段名、key、命名空间、extraInfo 解析结果）。 */
    private static final class Gen {
        final RegistrableEntry entry;
        final String className;
        final String fieldName;
        /** 注册项 key（指令会去掉前导 '/'）。 */
        final String key;
        /** 命名空间（取插件名，避免与其它插件撞 key）。 */
        final String ns;
        /** 完整 key：命名空间:key。 */
        final String fullKey;
        /** extraInfo 解析出的键值对（键已去掉空白并转小写）。 */
        final Map<String, String> extra;

        Gen(RegistrableEntry entry, String className, String ns) {
            this.entry = entry;
            this.className = className;
            this.fieldName = decapitalize(className);
            this.key = entry.getType() == RegistrableEntry.Type.COMMAND
                    ? normalizeCommandName(entry.getKey())
                    : entry.getKey().trim();
            this.ns = ns;
            this.fullKey = ns + ":" + this.key;
            this.extra = parseExtra(entry.getExtraInfo());
        }

        /** 取 extraInfo 中第一个命中的键。 */
        String opt(String... keys) {
            return pick(extra, keys);
        }

        List<Trigger> triggers() {
            return entry.getTriggers();
        }

        int enabledTriggerCount() {
            int n = 0;
            for (Trigger t : entry.getTriggers()) {
                if (t.isEnabled()) n++;
            }
            return n;
        }

        boolean hasAction(Trigger.Action action) {
            for (Trigger t : entry.getTriggers()) {
                if (t.isEnabled() && t.getAction() == action) return true;
            }
            return false;
        }
    }

    private List<Gen> buildEntries(List<RegistrableEntry> entries, String prefix) {
        List<Gen> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        int index = 0;
        for (RegistrableEntry e : entries) {
            index++;
            if (!e.isEnabled() || e.getKey() == null || e.getKey().trim().isEmpty()) continue;
            out.add(new Gen(e, uniqueClassName(used, prefix, e.getKey(), index), config.getPluginName()));
        }
        return out;
    }

    /**
     * 由 key 派生类名：只保留 ASCII 字母数字（中文/符号 key 回落到 {@code 前缀EntryN}）。
     * <p>不用中文类名是为了避免不同平台/编码下的文件名问题。</p>
     */
    private static String uniqueClassName(Set<String> used, String prefix, String key, int index) {
        String base = prefix + asciiCamel(key);
        if (base.equals(prefix)) {
            base = prefix + "Entry" + index;
        }
        String name = base;
        int n = 2;
        while (!used.add(name)) {
            name = base + "_" + n++;
        }
        return name;
    }

    private static String asciiCamel(String key) {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean upperNext = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(c);
                }
            } else {
                upperNext = true;
            }
        }
        if (sb.length() > 0 && Character.isDigit(sb.charAt(0))) {
            sb.insert(0, 'X');
        }
        return sb.toString();
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return "entry";
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 指令名去掉前导 '/' 与空白（CommandRegistry 的键不带 '/'）。 */
    private static String normalizeCommandName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        return s;
    }

    // ==================== 主类 ====================

    private String writeMainClass(File outDir, List<Gen> items, List<Gen> events,
                                  List<Gen> commands, List<Gen> realms) throws IOException {
        String pkg = config.getPackageName();
        String mainClass = mainClassName();
        boolean hasLang = !items.isEmpty() || !events.isEmpty() || !realms.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        for (Gen g : items) sb.append("import ").append(pkg).append(".item.").append(g.className).append(";\n");
        for (Gen g : events) sb.append("import ").append(pkg).append(".event.").append(g.className).append(";\n");
        for (Gen g : commands) sb.append("import ").append(pkg).append(".command.").append(g.className).append(";\n");
        for (Gen g : realms) sb.append("import ").append(pkg).append(".realm.").append(g.className).append(";\n");
        if (hasLang) sb.append("import com.mtxgdn.util.LangManager;\n");
        sb.append("import com.mtxgdn.plugin.Plugin;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n");
        sb.append("import com.mtxgdn.plugin.PluginMeta;\n\n");

        sb.append("/**\n");
        sb.append(" * ").append(comment(config.getPluginName())).append(" —— 由 PluginMaker 自动生成。\n");
        sb.append(" * <p>本插件注册了 ").append(config.totalEntries()).append(" 个注册项")
                .append("（物品 ").append(items.size()).append(" / 探索事件 ").append(events.size())
                .append(" / 指令 ").append(commands.size()).append(" / 秘境 ").append(realms.size()).append("），")
                .append("含 ").append(config.totalTriggers()).append(" 个触发器。</p>\n");
        sb.append(" * <p>生命周期：{@code onLoad} → {@code onEnable}（在此注册内容）→ {@code onDisable}（在此注销内容）。</p>\n");
        sb.append(" */\n");
        sb.append("@PluginMeta(name = ").append(javaStr(config.getPluginName()))
                .append(", version = ").append(javaStr(config.getVersion()))
                .append(", author = ").append(javaStr(config.getAuthor()))
                .append(", description = ").append(javaStr(config.getDescription()))
                .append(")\n");
        sb.append("public final class ").append(mainClass).append(" implements Plugin {\n\n");

        // 字段
        if (!items.isEmpty() || !events.isEmpty() || !commands.isEmpty() || !realms.isEmpty()) {
            sb.append("    // ===== 已注册的扩展内容（保留引用，onDisable 时注销） =====\n");
            for (Gen g : items) sb.append("    private ").append(g.className).append(' ').append(g.fieldName).append(";\n");
            for (Gen g : events) sb.append("    private ").append(g.className).append(' ').append(g.fieldName).append(";\n");
            for (Gen g : commands) sb.append("    private ").append(g.className).append(' ').append(g.fieldName).append(";\n");
            for (Gen g : realms) sb.append("    private ").append(g.className).append(' ').append(g.fieldName).append(";\n");
            sb.append("\n");
        }

        // onLoad
        sb.append("    @Override\n");
        sb.append("    public void onLoad(PluginContext context) {\n");
        sb.append("        context.getLogger().info(\"[\" + context.getInfo().getName() + \"] 正在加载...\");\n");
        if (hasLang) {
            sb.append("        // 插件自带的 lang/zh_cn.json 同时合并进 LangManager ——\n");
            sb.append("        // 物品/探索事件/秘境的名称与描述走的是这套旧词条表（PluginContext.loadLang() 只喂 i18n）。\n");
            sb.append("        loadLangFile(context);\n");
        }
        sb.append("    }\n\n");

        // onEnable
        sb.append("    @Override\n");
        sb.append("    public void onEnable(PluginContext context) {\n");
        if (!items.isEmpty()) {
            sb.append("        // ===== 物品（构造器会自动调用 ItemRegistry.register） =====\n");
            for (Gen g : items) {
                sb.append("        ").append(g.fieldName).append(" = ").append(g.className).append(".register(context);\n");
            }
            sb.append("\n");
        }
        if (!events.isEmpty()) {
            sb.append("        // ===== 探索事件（构造器会自动调用 ExplorationEventRegistry.register） =====\n");
            for (Gen g : events) {
                sb.append("        ").append(g.fieldName).append(" = ").append(g.className).append(".register(context);\n");
            }
            sb.append("\n");
        }
        if (!commands.isEmpty()) {
            sb.append("        // ===== 指令（Command 构造器会自动调用 CommandRegistry.register） =====\n");
            for (Gen g : commands) {
                sb.append("        ").append(g.fieldName).append(" = ").append(g.className).append(".register(context);\n");
            }
            sb.append("\n");
        }
        if (!realms.isEmpty()) {
            sb.append("        // ===== 秘境（构造器会自动调用 SecretRealmRegistry.register） =====\n");
            for (Gen g : realms) {
                sb.append("        ").append(g.fieldName).append(" = ").append(g.className).append(".register(context);\n");
            }
            sb.append("\n");
        }
        sb.append("        context.getLogger().info(\"[\" + context.getInfo().getName() + \"] v\"\n");
        sb.append("                + context.getInfo().getVersion() + \" 已启用：")
                .append(comment(summary(items, events, commands, realms))).append("\");\n");
        sb.append("    }\n\n");

        // onDisable
        sb.append("    @Override\n");
        sb.append("    public void onDisable(PluginContext context) {\n");
        if (!commands.isEmpty()) {
            sb.append("        // 指令必须先注销：否则玩家还能触发一个已经卸载的插件的指令\n");
            for (Gen g : commands) {
                sb.append("        if (").append(g.fieldName).append(" != null) {\n");
                sb.append("            context.unregisterCommand(").append(g.fieldName).append(");\n");
                sb.append("            ").append(g.fieldName).append(" = null;\n");
                sb.append("        }\n");
            }
            sb.append("\n");
        }
        for (Gen g : items) {
            sb.append("        if (").append(g.fieldName).append(" != null) {\n");
            sb.append("            context.unregisterItem(").append(g.fieldName).append(");\n");
            sb.append("            ").append(g.fieldName).append(" = null;\n");
            sb.append("        }\n");
        }
        if (!items.isEmpty()) sb.append("\n");
        for (Gen g : events) {
            sb.append("        if (").append(g.fieldName).append(" != null) {\n");
            sb.append("            context.unregisterExplorationEvent(").append(g.fieldName).append(");\n");
            sb.append("            ").append(g.fieldName).append(" = null;\n");
            sb.append("        }\n");
        }
        if (!events.isEmpty()) sb.append("\n");
        for (Gen g : realms) {
            sb.append("        if (").append(g.fieldName).append(" != null) {\n");
            sb.append("            context.unregisterSecretRealm(").append(g.fieldName).append(");\n");
            sb.append("            ").append(g.fieldName).append(" = null;\n");
            sb.append("        }\n");
        }
        if (!realms.isEmpty()) sb.append("\n");
        sb.append("        context.getLogger().info(\"[\" + context.getInfo().getName() + \"] 已停用。\");\n");
        sb.append("    }\n");

        if (hasLang) {
            sb.append("\n");
            sb.append("    /** 把插件自带的 lang/zh_cn.json 合并进 {@link LangManager}（物品/事件/秘境的词条）。 */\n");
            sb.append("    private static void loadLangFile(PluginContext context) {\n");
            sb.append("        try (java.io.InputStream is = context.getResource(\"lang/zh_cn.json\")) {\n");
            sb.append("            if (is == null) {\n");
            sb.append("                context.getLogger().warn(\"未找到 lang/zh_cn.json，名称将回退为词条 key\");\n");
            sb.append("                return;\n");
            sb.append("            }\n");
            sb.append("            LangManager.merge(\"zh_cn\", is);\n");
            sb.append("            context.getLogger().info(\"已加载插件语言文件: lang/zh_cn.json\");\n");
            sb.append("        } catch (Exception e) {\n");
            sb.append("            context.getLogger().warn(\"加载 lang/zh_cn.json 失败: \" + e.getMessage());\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return writeJava(outDir, null, mainClass, sb.toString());
    }

    private String summary(List<Gen> items, List<Gen> events, List<Gen> commands, List<Gen> realms) {
        StringBuilder sb = new StringBuilder();
        if (!items.isEmpty()) sb.append("物品 ").append(items.size()).append(" 个 ");
        if (!events.isEmpty()) sb.append("探索事件 ").append(events.size()).append(" 个 ");
        if (!commands.isEmpty()) sb.append("指令 ").append(commands.size()).append(" 个 ");
        if (!realms.isEmpty()) sb.append("秘境 ").append(realms.size()).append(" 个");
        String s = sb.toString().trim();
        return s.isEmpty() ? "无注册项" : s;
    }

    // ==================== 物品类 ====================

    private String writeItemClass(File outDir, Gen g) throws IOException {
        String pkg = config.getPackageName();
        ItemType type = parseItemType(g.opt("类型", "type"));
        ItemRarity rarity = parseItemRarity(g.opt("稀有度", "品质", "rarity"));
        int stack = parseIntSafe(g.opt("堆叠", "最大堆叠", "maxstack"), DEFAULT_ITEM_STACK);
        int price = parseIntSafe(g.opt("价格", "售价", "price"), DEFAULT_ITEM_PRICE);
        boolean tradeable = parseBool(g.opt("可交易", "tradeable"), true);
        int requiredRealm = parseIntSafe(g.opt("境界", "所需境界", "最低境界", "requiredrealm"), DEFAULT_ITEM_REALM);
        boolean needItemRegistry = g.hasAction(Trigger.Action.GIVE_ITEM);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".item;\n\n");
        sb.append("import com.mtxgdn.game.item.Item;\n");
        sb.append("import com.mtxgdn.game.item.ItemEffect;\n");
        sb.append("import com.mtxgdn.game.item.ItemRarity;\n");
        sb.append("import com.mtxgdn.game.item.ItemType;\n");
        if (needItemRegistry) sb.append("import com.mtxgdn.game.item.ItemRegistry;\n");
        sb.append("import com.mtxgdn.game.service.ItemService;\n");
        sb.append("import com.mtxgdn.game.service.PlayerService;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n\n");

        sb.append("/**\n");
        sb.append(" * 物品: ").append(comment(g.entry.getName())).append(" (key=").append(comment(g.key)).append(")\n");
        if (!g.entry.getDescription().isEmpty()) {
            sb.append(" * ").append(comment(g.entry.getDescription())).append("\n");
        }
        sb.append(" * <p>触发器 ").append(g.enabledTriggerCount()).append(" 个 —— 在 {@code TriggerEffect} 中执行，"
                + "返回值会作为「使用了【物品】: …」显示给玩家。</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(g.className).append(" extends Item {\n\n");

        sb.append("    /** 物品 key（不含命名空间）。 */\n");
        sb.append("    public static final String KEY = ").append(javaStr(g.key)).append(";\n");
        sb.append("    /** 物品完整 key（命名空间:key），即 ItemRegistry 中的键。 */\n");
        sb.append("    public static final String FULL_KEY = ").append(javaStr(g.fullKey)).append(";\n\n");

        sb.append("    public ").append(g.className).append("(PluginContext context) {\n");
        sb.append("        super(\n");
        sb.append("            ").append(javaStr(g.ns)).append(",          // 命名空间（用插件名，避免与其它插件撞 key）\n");
        sb.append("            KEY,                    // 物品唯一 key\n");
        sb.append("            ItemType.").append(type.name()).append(",      // 类型\n");
        sb.append("            ItemRarity.").append(rarity.name()).append(",    // 稀有度\n");
        sb.append("            ").append(stack).append(",                      // 最大堆叠数\n");
        sb.append("            ").append(price).append(",                      // 基础售价（灵石）\n");
        sb.append("            ").append(tradeable).append(",                     // 是否可交易\n");
        sb.append("            ").append(requiredRealm).append(",                      // 所需境界\n");
        sb.append("            new TriggerEffect(context)   // 使用效果：由下方触发器生成\n");
        sb.append("        );\n");
        sb.append("        // 注意：Item 构造器内部已调用 ItemRegistry.register(this)，\n");
        sb.append("        //       所以不要再调用 context.registerItem(...)，否则会打一条「物品覆盖」告警。\n");
        sb.append("    }\n\n");

        sb.append("    /** 创建并注册该物品（注册由构造器完成）。 */\n");
        sb.append("    public static ").append(g.className).append(" register(PluginContext context) {\n");
        sb.append("        ").append(g.className).append(" item = new ").append(g.className).append("(context);\n");
        sb.append("        context.getLogger().info(\"注册物品: \" + FULL_KEY);\n");
        sb.append("        return item;\n");
        sb.append("    }\n\n");

        sb.append("    /**\n");
        sb.append("     * 物品使用效果 —— 由 GUI 中配置的触发器生成。\n");
        sb.append("     * <p>返回的文本会拼进「使用了【物品】: …」这条提示。</p>\n");
        sb.append("     */\n");
        sb.append("    private static final class TriggerEffect extends ItemEffect {\n\n");
        sb.append("        /** 插件上下文（RUN_JAVA 自定义代码里可以直接用 context）。 */\n");
        sb.append("        private final PluginContext context;\n\n");
        sb.append("        TriggerEffect(PluginContext context) {\n");
        sb.append("            this.context = context;\n");
        sb.append("        }\n\n");
        sb.append("        @Override\n");
        sb.append("        public String execute(long playerId, PlayerService playerService, ItemService itemService) {\n");
        sb.append("            StringBuilder msg = new StringBuilder();\n");
        appendTriggerActions(sb, g, Mode.ITEM, 3);
        sb.append("            return msg.toString();\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");

        return writeJava(outDir, "item", g.className, sb.toString());
    }

    // ==================== 探索事件类 ====================

    private String writeEventClass(File outDir, Gen g) throws IOException {
        String pkg = config.getPackageName();
        int weight = parseIntSafe(g.opt("权重", "weight"), DEFAULT_EVENT_WEIGHT);
        boolean needItemRegistry = g.hasAction(Trigger.Action.GIVE_ITEM);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".event;\n\n");
        sb.append("import com.mtxgdn.entity.Player;\n");
        sb.append("import com.mtxgdn.game.entity.ExplorationResult;\n");
        sb.append("import com.mtxgdn.game.explorationevent.ExplorationEvent;\n");
        if (needItemRegistry) sb.append("import com.mtxgdn.game.item.ItemRegistry;\n");
        sb.append("import com.mtxgdn.game.service.ItemService;\n");
        sb.append("import com.mtxgdn.game.service.PlayerService;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Random;\n\n");

        sb.append("/**\n");
        sb.append(" * 探索事件: ").append(comment(g.entry.getName())).append(" (key=").append(comment(g.key)).append(")\n");
        if (!g.entry.getDescription().isEmpty()) {
            sb.append(" * ").append(comment(g.entry.getDescription())).append("\n");
        }
        sb.append(" * <p>玩家 /游历 时按权重被抽取，{@code execute(...)} 里执行 GUI 配置的触发器动作。</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(g.className).append(" extends ExplorationEvent {\n\n");

        sb.append("    /** 事件 key（不含命名空间）。 */\n");
        sb.append("    public static final String KEY = ").append(javaStr(g.key)).append(";\n");
        sb.append("    /** 事件完整 key（命名空间:key），即 ExplorationEventRegistry 中的键。 */\n");
        sb.append("    public static final String FULL_KEY = ").append(javaStr(g.fullKey)).append(";\n\n");

        sb.append("    /** 插件上下文（RUN_JAVA 自定义代码里可以直接用 context）。 */\n");
        sb.append("    private final PluginContext context;\n\n");

        sb.append("    public ").append(g.className).append("(PluginContext context) {\n");
        sb.append("        super(").append(javaStr(g.ns)).append(", KEY, ").append(weight).append(");   // 命名空间, key, 抽取权重\n");
        sb.append("        this.context = context;\n");
        sb.append("        // 注意：ExplorationEvent 构造器内部已调用 ExplorationEventRegistry.register(this)，\n");
        sb.append("        //       所以不要再调用 context.registerExplorationEvent(...)。\n");
        sb.append("    }\n\n");

        sb.append("    /** 创建并注册该探索事件（注册由构造器完成）。 */\n");
        sb.append("    public static ").append(g.className).append(" register(PluginContext context) {\n");
        sb.append("        ").append(g.className).append(" event = new ").append(g.className).append("(context);\n");
        sb.append("        context.getLogger().info(\"注册探索事件: \" + FULL_KEY + \"（权重 \" + event.getWeight() + \"）\");\n");
        sb.append("        return event;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void execute(Player player, PlayerService playerService, ItemService itemService,\n");
        sb.append("                        Random random, ExplorationResult result, List<String> log) {\n");
        sb.append("        long playerId = player.getId();\n");
        sb.append("        result.setEventType(KEY);\n");
        sb.append("        result.setEventDescription(").append(javaStr(g.entry.getName())).append(");\n");
        appendTriggerActions(sb, g, Mode.EVENT, 2);
        sb.append("    }\n");
        sb.append("}\n");

        return writeJava(outDir, "event", g.className, sb.toString());
    }

    // ==================== 指令类 ====================

    private String writeCommandClass(File outDir, Gen g) throws IOException {
        String pkg = config.getPackageName();
        boolean needItemRegistry = g.hasAction(Trigger.Action.GIVE_ITEM);
        boolean needServiceRegistry = g.hasAction(Trigger.Action.GIVE_ITEM)
                || g.hasAction(Trigger.Action.GIVE_SPIRIT_STONES);
        String commandName = g.key.isEmpty() ? "plugin_cmd" : g.key;
        String description = g.entry.getDescription().isEmpty() ? g.entry.getName() : g.entry.getDescription();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".command;\n\n");
        sb.append("import com.mtxgdn.common.command.Command;\n");
        sb.append("import com.mtxgdn.common.command.CommandContext;\n");
        if (needServiceRegistry) sb.append("import com.mtxgdn.common.service.ServiceRegistry;\n");
        sb.append("import com.mtxgdn.game.entity.PlayerInfo;\n");
        if (needItemRegistry) sb.append("import com.mtxgdn.game.item.ItemRegistry;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n\n");

        sb.append("/**\n");
        sb.append(" * 指令: ").append(comment(g.entry.getName())).append(" (/").append(comment(commandName)).append(")\n");
        if (!g.entry.getDescription().isEmpty()) {
            sb.append(" * ").append(comment(g.entry.getDescription())).append("\n");
        }
        sb.append(" * <p>玩家发送 {@code /").append(comment(commandName)).append("} 时执行 {@code execute(...)} 里的触发器动作。</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(g.className).append(" extends Command {\n\n");

        sb.append("    /** 指令名（不含前缀 '/'，与 CommandRegistry 的键一致）。 */\n");
        sb.append("    public static final String COMMAND = ").append(javaStr(commandName)).append(";\n\n");
        sb.append("    /** 插件上下文（由 register(...) 注入，RUN_JAVA 自定义代码里可直接使用）。 */\n");
        sb.append("    private PluginContext pluginContext;\n\n");

        sb.append("    public ").append(g.className).append("() {\n");
        sb.append("        super(\n");
        sb.append("            new String[] {").append(javaStr(commandName)).append("},   // 指令别名（不含 '/'，大小写不敏感）\n");
        sb.append("            ").append(javaStr(description)).append(",   // 描述（帮助信息里显示）\n");
        sb.append("            ").append(javaStr("/" + commandName)).append(",   // 用法\n");
        sb.append("            \"实用功能\"   // 分类（账号/我的角色/修炼/战斗/背包/探索/坊市/宗门/经济/社交/管理/实用功能）\n");
        sb.append("        );\n");
        sb.append("        // 注意：Command 构造器内部已调用 CommandRegistry.register(this)。\n");
        sb.append("    }\n\n");

        sb.append("    /** 创建并注册该指令。 */\n");
        sb.append("    public static ").append(g.className).append(" register(PluginContext context) {\n");
        sb.append("        ").append(g.className).append(" command = new ").append(g.className).append("();\n");
        sb.append("        command.pluginContext = context;\n");
        sb.append("        context.registerCommand(command);\n");
        sb.append("        context.getLogger().info(\"注册指令: /\" + COMMAND);\n");
        sb.append("        return command;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void execute(CommandContext ctx) {\n");
        sb.append("        Long userId = ctx.requireBinding();\n");
        sb.append("        if (userId == null) return;\n");
        sb.append("        PlayerInfo p = ctx.requirePlayer(userId);\n");
        sb.append("        if (p == null) return;\n");
        sb.append("        long playerId = p.getId();\n");
        sb.append("        PluginContext context = this.pluginContext;\n");
        appendTriggerActions(sb, g, Mode.COMMAND, 2);
        sb.append("    }\n");
        sb.append("}\n");

        return writeJava(outDir, "command", g.className, sb.toString());
    }

    // ==================== 秘境类 ====================

    private String writeRealmClass(File outDir, Gen g) throws IOException {
        String pkg = config.getPackageName();
        int requiredRealm = parseIntSafe(g.opt("境界", "所需境界", "最低境界", "最低等级", "requiredrealm"),
                DEFAULT_REALM_REALM);
        long cooldownMs = parseDurationMs(g.opt("冷却", "冷却时间", "冷却毫秒", "cooldown"), DEFAULT_REALM_COOLDOWN_MS);
        boolean raid = parseBool(g.opt("团队", "多人", "raid"), false);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".realm;\n\n");
        sb.append("import com.mtxgdn.game.secretrealm.SecretRealm;\n");
        sb.append("import com.mtxgdn.plugin.PluginContext;\n\n");

        sb.append("/**\n");
        sb.append(" * 秘境: ").append(comment(g.entry.getName())).append(" (key=").append(comment(g.key)).append(")\n");
        if (!g.entry.getDescription().isEmpty()) {
            sb.append(" * ").append(comment(g.entry.getDescription())).append("\n");
        }
        if (!g.entry.getExtraInfo().isEmpty()) {
            sb.append(" * <p>额外配置: ").append(comment(g.entry.getExtraInfo())).append("</p>\n");
        }
        sb.append(" * <p>所需境界 ").append(requiredRealm).append("，冷却 ").append(cooldownMs).append(" 毫秒，")
                .append(raid ? "团队秘境" : "单人秘境").append("。</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(g.className).append(" extends SecretRealm {\n\n");

        sb.append("    /** 秘境 key（不含命名空间）。 */\n");
        sb.append("    public static final String KEY = ").append(javaStr(g.key)).append(";\n");
        sb.append("    /** 秘境完整 key（命名空间:key），即 SecretRealmRegistry 中的键。 */\n");
        sb.append("    public static final String FULL_KEY = ").append(javaStr(g.fullKey)).append(";\n\n");

        sb.append("    public ").append(g.className).append("() {\n");
        sb.append("        super(").append(javaStr(g.ns)).append(", KEY, ").append(requiredRealm).append(", ")
                .append(cooldownMs).append("L, ").append(raid).append(");\n");
        sb.append("        // 命名空间, key, 所需境界, 冷却毫秒, 是否团队秘境\n");
        sb.append("        // 注意：SecretRealm 构造器内部已调用 SecretRealmRegistry.register(this)，\n");
        sb.append("        //       所以不要再调用 context.registerSecretRealm(...)。\n");
        sb.append("    }\n\n");

        sb.append("    /** 创建并注册该秘境（注册由构造器完成）。 */\n");
        sb.append("    public static ").append(g.className).append(" register(PluginContext context) {\n");
        sb.append("        ").append(g.className).append(" realm = new ").append(g.className).append("();\n");
        sb.append("        context.getLogger().info(\"注册秘境: \" + FULL_KEY + \"（所需境界 \" + realm.getRequiredRealm() + \"）\");\n");
        sb.append("        return realm;\n");
        sb.append("    }\n");
        sb.append("}\n");

        return writeJava(outDir, "realm", g.className, sb.toString());
    }

    // ==================== 触发器动作生成 ====================

    /** 触发器动作所在的方法环境（决定动作能用的变量与"给玩家发消息"的方式）。 */
    private enum Mode {
        /** 物品使用效果：msg(StringBuilder) / playerId / playerService / itemService / context */
        ITEM,
        /** 探索事件 execute：log(List) / playerId / player / playerService / itemService / random / result / context */
        EVENT,
        /** 指令 execute：ctx(CommandContext) / p / playerId / userId / context */
        COMMAND
    }

    private void appendTriggerActions(StringBuilder sb, Gen g, Mode mode, int indent) {
        int idx = 0;
        for (Trigger t : g.triggers()) {
            if (!t.isEnabled()) continue;
            idx++;
            String ind = repeat("    ", indent);
            sb.append(ind).append("// [").append(idx).append("] ")
                    .append(t.getTriggerWhen().label).append(" → ").append(t.getAction().label);
            if (!t.getDescription().isEmpty()) {
                sb.append("（").append(comment(t.getDescription())).append("）");
            }
            sb.append("\n");
            appendTriggerAction(sb, g, mode, t, indent);
            sb.append("\n");
        }
        if (idx == 0) {
            sb.append(repeat("    ", indent)).append("// （该").append(g.entry.getType().label)
                    .append("没有启用任何触发器，如需动作请在 GUI 里添加）\n");
        }
    }

    private void appendTriggerAction(StringBuilder sb, Gen g, Mode mode, Trigger t, int indent) {
        String ind = repeat("    ", indent);
        String itemSvc = itemServiceExpr(mode);
        switch (t.getAction()) {
            case SEND_MESSAGE: {
                String text = t.getActionParam().isEmpty() ? "道友安好！" : t.getActionParam();
                emitPlayerMessage(sb, mode, ind, text);
                break;
            }
            case GIVE_SPIRIT_STONES: {
                long amount = parseLongSafe(t.getActionParam(), 100);
                sb.append(ind).append(itemSvc).append(".addSpiritStones(playerId, ").append(amount).append("L);\n");
                emitPlayerMessage(sb, mode, ind, "获得 " + amount + " 灵石。");
                break;
            }
            case GIVE_ITEM: {
                String rawKey = t.getActionParam().trim();
                sb.append(ind).append("// 物品 key 可以写全（ns:key），也可以只写 key —— 缺命名空间时先按本插件、再按 mtxgdn 解析\n");
                sb.append(ind).append("String giftKey = ").append(javaStr(rawKey.isEmpty() ? "healing_pill" : rawKey)).append(";\n");
                sb.append(ind).append("if (!giftKey.contains(\":\")) {\n");
                sb.append(ind).append("    String ownKey = ").append(javaStr(g.ns + ":")).append(" + giftKey;\n");
                sb.append(ind).append("    giftKey = ItemRegistry.contains(ownKey) ? ownKey : \"mtxgdn:\" + giftKey;\n");
                sb.append(ind).append("}\n");
                sb.append(ind).append(itemSvc).append(".addItem(playerId, giftKey, 1);\n");
                emitPlayerMessage(sb, mode, ind, "获得物品：" + (rawKey.isEmpty() ? "healing_pill" : rawKey) + " ×1。");
                break;
            }
            case RUN_JAVA: {
                sb.append(ind).append("// —— 自定义 Java 代码（来自 GUI 配置）——\n");
                String code = t.getJavaCode();
                if (code == null || code.trim().isEmpty()) {
                    sb.append(ind).append("// （未填写自定义代码）\n");
                } else {
                    String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
                    for (String line : normalized.split("\n", -1)) {
                        if (line.isEmpty()) {
                            sb.append("\n");
                        } else {
                            sb.append(ind).append(line).append("\n");
                        }
                    }
                }
                break;
            }
            case LOG_ONLY:
            default: {
                String text = "事件触发: " + (t.getDescription().isEmpty() ? g.entry.getName() : t.getDescription());
                sb.append(ind).append("context.getLogger().info(").append(javaStr(text)).append(");\n");
                break;
            }
        }
    }

    /** 按方法环境给出「物品服务」的表达式。 */
    private static String itemServiceExpr(Mode mode) {
        // 指令的 execute(CommandContext) 里没有 itemService 参数，走 ServiceRegistry
        return mode == Mode.COMMAND ? "ServiceRegistry.getItemService()" : "itemService";
    }

    /** 按方法环境生成「把一句话给到玩家」的代码。 */
    private void emitPlayerMessage(StringBuilder sb, Mode mode, String ind, String text) {
        String literal = javaStr(text);
        switch (mode) {
            case ITEM:
                sb.append(ind).append("msg.append(").append(literal).append(");\n");
                break;
            case EVENT:
                sb.append(ind).append("log.add(").append(literal).append(");\n");
                break;
            case COMMAND:
            default:
                sb.append(ind).append("ctx.reply(").append(literal).append(");\n");
                break;
        }
    }

    // ==================== 翻译文件 ====================

    private String writeLangJson(File outDir, List<Gen> items, List<Gen> events, List<Gen> realms)
            throws IOException {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Map<String, String> map = new LinkedHashMap<>();

        for (Gen g : items) {
            map.put("item." + g.key + ".name", g.entry.getName());
            if (!g.entry.getDescription().isEmpty()) {
                map.put("item." + g.key + ".desc", g.entry.getDescription());
            }
        }
        for (Gen g : events) {
            map.put("explorationevent." + g.key + ".name", g.entry.getName());
            if (!g.entry.getDescription().isEmpty()) {
                map.put("explorationevent." + g.key + ".desc", g.entry.getDescription());
            }
        }
        for (Gen g : realms) {
            map.put("secretrealm." + g.key + ".name", g.entry.getName());
            if (!g.entry.getDescription().isEmpty()) {
                map.put("secretrealm." + g.key + ".desc", g.entry.getDescription());
            }
        }

        if (map.isEmpty()) return "";

        String json = gson.toJson(map);
        File target = new File(outDir, "src/main/resources/lang/zh_cn.json");
        ensureParent(target);
        Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== README ====================

    private String writeReadme(File outDir, List<Gen> items, List<Gen> events,
                               List<Gen> commands, List<Gen> realms) throws IOException {
        String pkg = config.getPackageName();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(config.getPluginName()).append(" (v").append(config.getVersion()).append(")\n\n");
        if (!config.getDescription().isEmpty()) {
            sb.append(config.getDescription()).append("\n\n");
        }
        sb.append("> 本项目由 **PluginMaker (").append(com.mtxgdn.util.BuildInfo.getDisplayVersion())
                .append(")** 自动生成，生成物直接 `mvn package` 即可编译。\n\n");

        sb.append("## 📦 项目结构\n\n");
        sb.append("```\n");
        sb.append(pkg).append("/\n");
        sb.append("├── ").append(mainClassName()).append(".java    # 主类（implements Plugin，@PluginMeta 声明元数据）\n");
        if (!items.isEmpty()) sb.append("├── item/        # 物品（extends Item）: ").append(items.size()).append("\n");
        if (!events.isEmpty()) sb.append("├── event/       # 探索事件（extends ExplorationEvent）: ").append(events.size()).append("\n");
        if (!commands.isEmpty()) sb.append("├── command/     # 指令（extends Command）: ").append(commands.size()).append("\n");
        if (!realms.isEmpty()) sb.append("├── realm/       # 秘境（extends SecretRealm）: ").append(realms.size()).append("\n");
        sb.append("```\n\n");
        sb.append("`src/main/resources/lang/zh_cn.json` 存放物品/事件/秘境的名称与描述词条。\n\n");

        sb.append("## 🚀 构建与部署\n\n");
        sb.append("```bash\n");
        sb.append("# 1) 先把服务端 jar 装进本地仓库（只需一次）\n");
        sb.append("mvn install:install-file -Dfile=服务端.jar \\\n");
        sb.append("    -DgroupId=com.mtxgdn -DartifactId=main \\\n");
        sb.append("    -Dversion=").append(com.mtxgdn.util.BuildInfo.getVersion()).append(" -Dpackaging=jar\n");
        sb.append("# 2) 打包插件\n");
        sb.append("mvn clean package\n");
        sb.append("# 3) 把 target/").append(config.getArtifactId()).append("-").append(config.getVersion())
                .append(".jar 放进服务端 ./plugins 目录，重启服务端\n");
        sb.append("```\n\n");

        sb.append("## ⚙ 注册与触发器\n\n");
        sb.append("| 注册项 | 基类 | 注册方式 | 可用触发时机 |\n");
        sb.append("| --- | --- | --- | --- |\n");
        sb.append("| 物品 | `com.mtxgdn.game.item.Item` | 构造器自动 `ItemRegistry.register` | 使用时 |\n");
        sb.append("| 探索事件 | `com.mtxgdn.game.explorationevent.ExplorationEvent` | 构造器自动 `ExplorationEventRegistry.register` | 被 /游历 抽中时 |\n");
        sb.append("| 指令 | `com.mtxgdn.common.command.Command` | 构造器自动 `CommandRegistry.register` | 玩家执行指令时 |\n");
        sb.append("| 秘境 | `com.mtxgdn.game.secretrealm.SecretRealm` | 构造器自动 `SecretRealmRegistry.register` | 暂不支持（见下） |\n\n");
        sb.append("触发器动作支持：发送消息、给予灵石、给予物品、执行自定义 Java 代码、仅记录日志。\n\n");
        sb.append("### 自定义代码可用的变量\n\n");
        sb.append("- 物品效果：`playerId`、`playerService`、`itemService`、`context`、`msg`（StringBuilder，append 的内容会显示给玩家）\n");
        sb.append("- 探索事件：`player`、`playerId`、`playerService`、`itemService`、`random`、`result`、`log`、`context`\n");
        sb.append("- 指令：`ctx`、`p`、`playerId`、`userId`、`context`\n\n");
        sb.append("### 额外配置（GUI 里的「额外配置」栏）\n\n");
        sb.append("格式为 `键: 值`，多个用 `|` 分隔，未识别的键会被忽略。\n\n");
        sb.append("- 物品：`类型`（消耗品/装备/材料/功法秘籍/法宝/任务物品/货币/种子）、`稀有度`（凡品/良品/珍品/仙品/神品/圣品）、"
                + "`堆叠`、`价格`、`可交易`（是/否）、`境界`\n");
        sb.append("- 探索事件：`权重`（抽取权重，默认 10）\n");
        sb.append("- 秘境：`境界`（所需境界）、`冷却`（如 `180秒` / `3分钟` / `180000毫秒`）、`团队`（是/否）\n\n");
        sb.append("### 关于秘境触发器\n\n");
        sb.append("服务端核心目前**没有**提供「进入/退出/遭遇秘境」的插件事件，因此秘境只做真实注册，"
                + "不提供触发时机选项——生成器不会产出调不通的代码。\n\n");
        sb.append("**作者**: ").append(config.getAuthor()).append("\n");

        File target = new File(outDir, "README.md");
        ensureParent(target);
        Files.write(target.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    // ==================== 占位符 / 模板 ====================

    private Map<String, String> buildPlaceholders(PluginConfig cfg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAME}}", cfg.getPluginName());
        m.put("{{VERSION}}", cfg.getVersion());
        m.put("{{AUTHOR}}", cfg.getAuthor());
        m.put("{{DESCRIPTION}}", cfg.getDescription());
        m.put("{{ARTIFACT_ID}}", cfg.getArtifactId());
        m.put("{{GROUP_ID}}", cfg.getGroupId());
        m.put("{{PACKAGE}}", cfg.getPackageName());
        m.put("{{PACKAGE_PATH}}", cfg.getPackagePath());
        m.put("{{MAIN_CLASS}}", mainClassName());
        m.put("{{SERVER_VERSION}}", com.mtxgdn.util.BuildInfo.getVersion());
        m.put("{{TOTAL_ENTRIES}}", String.valueOf(cfg.totalEntries()));
        m.put("{{TOTAL_TRIGGERS}}", String.valueOf(cfg.totalTriggers()));
        return m;
    }

    /**
     * 主类名清洗：只保留合法标识符字符，数字开头补下划线，空则回落 {@code Plugin}。
     * 用户随手写成 "My Plugin" 时也能生成可编译的文件。
     */
    private String mainClassName() {
        String raw = config.getMainClass() == null ? "" : config.getMainClass().trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') sb.append(c);
        }
        if (sb.length() == 0) return "Plugin";
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString();
    }

    private String apply(String template, EscapeMode mode) {
        String result = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace(e.getKey(), escapeFor(e.getValue(), mode));
        }
        return result;
    }

    /** 模板是 XML / JSON 时，占位符的取值必须先按目标格式转义，否则名字里一个引号就能把文件写坏。 */
    private enum EscapeMode { NONE, XML, JSON }

    private static String escapeFor(String v, EscapeMode mode) {
        if (v == null) return "";
        switch (mode) {
            case XML:
                return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            case JSON:
                return v.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            default:
                return v;
        }
    }

    private String writeTemplated(File outDir, String relativePath, String templateName, EscapeMode mode)
            throws IOException {
        String content = apply(loadTemplate(templateName), mode);
        File target = new File(outDir, relativePath);
        ensureParent(target);
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    /** 写一个 .java 文件（subPackage 为 null 表示直接放在插件根包下）。 */
    private String writeJava(File outDir, String subPackage, String className, String content) throws IOException {
        String rel = "src/main/java/" + config.getPackagePath() + "/"
                + (subPackage == null || subPackage.isEmpty() ? "" : subPackage + "/")
                + className + ".java";
        File target = new File(outDir, rel);
        ensureParent(target);
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return target.getAbsolutePath();
    }

    private static void ensureParent(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
    }

    // ==================== extraInfo 解析 ====================

    /** 解析 "最低等级: 1 | 体力消耗: 10" 形式的额外配置。 */
    private static Map<String, String> parseExtra(String extra) {
        Map<String, String> m = new LinkedHashMap<>();
        if (extra == null || extra.trim().isEmpty()) return m;
        for (String part : extra.split("[|;；,，\\n]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int idx = -1;
            for (char sep : new char[] {':', '：', '='}) {
                int i = p.indexOf(sep);
                if (i > 0 && (idx < 0 || i < idx)) idx = i;
            }
            if (idx <= 0) continue;
            String k = p.substring(0, idx).trim().toLowerCase().replace(" ", "");
            String v = p.substring(idx + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) m.put(k, v);
        }
        return m;
    }

    private static String pick(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static ItemType parseItemType(String raw) {
        if (raw == null) return ItemType.CONSUMABLE;
        String v = raw.trim();
        String upper = v.toUpperCase();
        for (ItemType t : ItemType.values()) {
            if (t.name().equals(upper)) return t;
        }
        return switch (v) {
            case "装备", "武器", "防具" -> ItemType.EQUIPMENT;
            case "材料", "素材" -> ItemType.MATERIAL;
            case "功法秘籍", "秘籍", "功法" -> ItemType.SKILL_BOOK;
            case "法宝" -> ItemType.TREASURE;
            case "任务物品", "任务" -> ItemType.QUEST;
            case "货币", "灵石" -> ItemType.CURRENCY;
            case "种子" -> ItemType.SEED;
            default -> ItemType.CONSUMABLE;
        };
    }

    private static ItemRarity parseItemRarity(String raw) {
        if (raw == null) return ItemRarity.RARE;
        String v = raw.trim();
        String upper = v.toUpperCase();
        for (ItemRarity r : ItemRarity.values()) {
            if (r.name().equals(upper)) return r;
        }
        return switch (v) {
            case "凡品", "普通" -> ItemRarity.COMMON;
            case "良品", "优秀" -> ItemRarity.UNCOMMON;
            case "仙品", "史诗" -> ItemRarity.EPIC;
            case "神品", "传说" -> ItemRarity.LEGENDARY;
            case "圣品", "神话" -> ItemRarity.MYTHIC;
            default -> ItemRarity.RARE;
        };
    }

    /** 解析时长：纯数字按毫秒；支持 "180秒" / "3分钟" / "2小时" / "180000毫秒"。 */
    private static long parseDurationMs(String raw, long fallback) {
        if (raw == null) return fallback;
        String v = raw.trim().toLowerCase().replace(" ", "");
        int i = 0;
        while (i < v.length() && (Character.isDigit(v.charAt(i)) || v.charAt(i) == '_')) i++;
        if (i == 0) return fallback;
        long n;
        try {
            n = Long.parseLong(v.substring(0, i).replace("_", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
        String unit = v.substring(i);
        long mult;
        if (unit.isEmpty() || unit.startsWith("毫秒") || unit.equals("ms")) {
            mult = 1L;
        } else if (unit.startsWith("秒") || unit.equals("s") || unit.equals("sec")) {
            mult = 1_000L;
        } else if (unit.startsWith("分") || unit.equals("min")) {
            mult = 60_000L;
        } else if (unit.startsWith("小时") || unit.equals("h")) {
            mult = 3_600_000L;
        } else {
            mult = 1L;
        }
        return n * mult;
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) return fallback;
        return v.equals("是") || v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y")
                || v.equals("可交易") || v.equals("团队");
    }

    private static int parseIntSafe(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ==================== 杂项 ====================

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    /** 从 classpath 中加载模板内容。 */
    private static String loadTemplate(String name) throws IOException {
        String full = "plugin-template/" + name;
        InputStream is = CodeGenerator.class.getClassLoader().getResourceAsStream(full);
        if (is == null) {
            throw new IOException("模板文件不存在: " + full);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Java 字符串字面量转义 —— 将用户输入安全地嵌入 Java 源码。 */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String javaStr(String s) {
        return "\"" + escape(s) + "\"";
    }

    /** 注释文本清理：压成一行并避免提前闭合块注释。 */
    private static String comment(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").replace("*/", "*／").trim();
    }
}
