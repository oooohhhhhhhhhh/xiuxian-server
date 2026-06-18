package com.mtxgdn.plugin.gui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件制作工具主窗口。
 * <p>
 * Tab 结构：基础配置 / 物品 / 事件 / 指令 / 秘境 / 生成 & 预览。
 * 每种注册项（物品/事件/指令/秘境）有自己的配置面板，其中触发器是每个注册项的子属性。
 */
public class PluginMakerGUI extends JFrame {

    private final PluginConfig config;
    private BasicConfigPanel basicPanel;
    private EntriesPanel itemsPanel;
    private EntriesPanel eventsPanel;
    private EntriesPanel commandsPanel;
    private EntriesPanel realmsPanel;
    private JTextArea logArea;

    public static void launch() {
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        SwingUtilities.invokeLater(() -> {
            Theme.installLookAndFeel();
            PluginMakerGUI gui = new PluginMakerGUI();
            gui.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            SwingUtilities.updateComponentTreeUI(gui);
            gui.setVisible(true);
        });
    }

    private PluginMakerGUI() {
        this.config = new PluginConfig();
        setTitle("🛠 插件制作工具 · Plugin Maker");
        setMinimumSize(new Dimension(920, 640));
        setPreferredSize(new Dimension(1150, 780));
        setSize(1150, 780);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG_PRIMARY);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        add(root);
        log("欢迎使用 Plugin Maker");
        log("请在【基础配置】填写插件信息，然后在【物品/事件/指令/秘境】中配置各项及其触发器。");
    }

    // ==================== 顶部 ====================

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout(16, 4));
        p.setBackground(Theme.BG_PRIMARY);
        p.setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 4));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(Theme.BG_PRIMARY);

        JLabel title = Theme.titleLabel("🛠 插件制作工具", 20f);
        title.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        left.add(title);

        JLabel subtitle = Theme.hintLabel("快速生成可部署的 Server 插件项目（含物品、事件、指令、秘境及其触发器）");
        subtitle.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
        left.add(subtitle);

        JLabel right = Theme.hintLabel("Plugin Maker · v1.4.1-alpha1");
        right.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 4));

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ==================== 中部：Tab + 日志 ====================

    private JComponent buildBody() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(Theme.BG_PRIMARY);
        split.setLeftComponent(buildTabbedPane());
        split.setRightComponent(buildLogPanel());
        split.setResizeWeight(0.75);
        split.setDividerSize(3);
        return split;
    }

    private JComponent buildTabbedPane() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        Theme.styleTabbedPane(tabs);
        tabs.setFont(Theme.fontBold(12.5f));

        basicPanel = new BasicConfigPanel(config);
        itemsPanel = new EntriesPanel(RegistrableEntry.Type.ITEM, config.getItems());
        eventsPanel = new EntriesPanel(RegistrableEntry.Type.EVENT, config.getEvents());
        commandsPanel = new EntriesPanel(RegistrableEntry.Type.COMMAND, config.getCommands());
        realmsPanel = new EntriesPanel(RegistrableEntry.Type.SECRET_REALM, config.getSecretRealms());

        tabs.addTab(" 📋 基础配置 ", basicPanel);
        tabs.addTab(" 📦 物品 (" + config.getItems().size() + ") ", itemsPanel);
        tabs.addTab(" ⚡ 事件 (" + config.getEvents().size() + ") ", eventsPanel);
        tabs.addTab(" 💬 指令 (" + config.getCommands().size() + ") ", commandsPanel);
        tabs.addTab(" 🏔 秘境 (" + config.getSecretRealms().size() + ") ", realmsPanel);
        tabs.addTab(" ✅ 生成 & 预览 ", buildPreviewTab());

        return tabs;
    }

    private JComponent buildPreviewTab() {
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBackground(Theme.BG_PRIMARY);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        // 使用说明
        JPanel hintCard = new JPanel(new BorderLayout(8, 8));
        hintCard.setBackground(Theme.BG_SECONDARY);
        hintCard.setBorder(Theme.cardBorder("📖 操作说明"));

        JTextArea hint = new JTextArea(
                "1. 在【基础配置】中填写插件名称、版本、GroupId、ArtifactId、主类名、输出目录。\n" +
                "2. 在【物品】【事件】【指令】【秘境】各标签页中添加/编辑注册项，每个注册项内可配置多个触发器。\n" +
                "3. 触发器的含义：当该注册项（例如某个物品）发生指定时机（例如玩家使用物品）时，执行动作（发送消息/给物品/执行代码等）。\n" +
                "4. 完成后点击底部【💾 保存配置】把当前配置存为 JSON，下次可通过【📂 加载配置】恢复。\n" +
                "5. 点击底部【🚀 生成插件项目】即可在输出目录下生成完整的 Maven 项目。\n" +
                "6. 在生成目录中执行 mvn clean package 即可得到 JAR 包，放入服务端 ./plugins 目录即可加载。"
        );
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setEditable(false);
        hint.setFont(Theme.fontRegular(12.5f));
        hint.setBackground(Theme.BG_SECONDARY);
        hint.setForeground(Theme.FG_TEXT);
        hintCard.add(hint, BorderLayout.CENTER);
        p.add(hintCard, BorderLayout.NORTH);

        // 当前配置预览
        JPanel previewCard = new JPanel(new BorderLayout(8, 8));
        previewCard.setBackground(Theme.BG_SECONDARY);
        previewCard.setBorder(Theme.cardBorder("🧾 当前配置预览"));
        JTextArea preview = new JTextArea(configPreview(), 16, 40);
        preview.setFont(Theme.fontMono(12f));
        preview.setEditable(false);
        preview.setBackground(Theme.BG_SECONDARY);
        preview.setForeground(Theme.FG_TEXT);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        previewScroll.getViewport().setBackground(Theme.BG_SECONDARY);
        previewScroll.getVerticalScrollBar().setUnitIncrement(16);
        previewCard.add(previewScroll, BorderLayout.CENTER);

        JButton refreshBtn = new JButton("🔄 重新读取配置");
        Theme.styleButton(refreshBtn);
        refreshBtn.addActionListener(e -> {
            if (basicPanel != null) basicPanel.applyToConfig();
            preview.setText(configPreview());
        });
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.setBackground(Theme.BG_SECONDARY);
        btnRow.add(refreshBtn);
        previewCard.add(btnRow, BorderLayout.SOUTH);

        p.add(previewCard, BorderLayout.CENTER);
        return p;
    }

    private String configPreview() {
        StringBuilder sb = new StringBuilder();
        sb.append("插件名称:     ").append(config.getPluginName()).append('\n');
        sb.append("版本:         ").append(config.getVersion()).append('\n');
        sb.append("作者:         ").append(config.getAuthor()).append('\n');
        sb.append("描述:         ").append(config.getDescription()).append('\n');
        sb.append("GroupId:      ").append(config.getGroupId()).append('\n');
        sb.append("ArtifactId:   ").append(config.getArtifactId()).append('\n');
        sb.append("包名:         ").append(config.getPackageName()).append('\n');
        sb.append("主类:         ").append(config.getMainClass()).append('\n');
        sb.append("输出目录:     ").append(config.getOutputDir()).append('\n');
        sb.append("\n================ 注册项 =================\n");
        dumpEntries(sb, "📦 物品", config.getItems());
        dumpEntries(sb, "⚡ 事件", config.getEvents());
        dumpEntries(sb, "💬 指令", config.getCommands());
        dumpEntries(sb, "🏔 秘境", config.getSecretRealms());
        sb.append("\n总计: ").append(config.totalEntries()).append(" 个注册项, ").append(config.totalTriggers()).append(" 个触发器\n");
        return sb.toString();
    }

    private void dumpEntries(StringBuilder sb, String title, List<RegistrableEntry> list) {
        sb.append('\n').append(title).append(" (").append(list.size()).append(")\n");
        if (list.isEmpty()) {
            sb.append("    (空)\n");
            return;
        }
        int i = 0;
        for (RegistrableEntry e : list) {
            i++;
            sb.append("    [").append(i).append("] ")
                    .append(e.isEnabled() ? "●" : "○").append(' ')
                    .append(e.getName().isEmpty() ? "(未命名)" : e.getName())
                    .append("  [key=").append(e.getKey().isEmpty() ? "未设置" : e.getKey()).append("]")
                    .append("  触发器: ").append(e.getTriggers().size()).append('\n');
            for (Trigger t : e.getTriggers()) {
                sb.append("         - ").append(t.isEnabled() ? "☑" : "☐").append(' ')
                        .append(t.getTriggerWhen().label).append(" → ").append(t.getAction().label);
                if (t.getAction() != Trigger.Action.RUN_JAVA && !t.getActionParam().isEmpty()) {
                    sb.append(" (").append(truncate(t.getActionParam(), 30)).append(")");
                }
                if (t.getDescription() != null && !t.getDescription().isEmpty()) {
                    sb.append(" [").append(t.getDescription()).append("]");
                }
                sb.append('\n');
            }
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    // ==================== 日志面板 ====================

    private JComponent buildLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout(8, 8));
        logPanel.setBackground(Theme.BG_PRIMARY);

        JLabel title = Theme.titleLabel("📜 运行日志", 13f);
        title.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));

        logArea = new JTextArea("", 28, 30);
        logArea.setFont(Theme.fontMono(11.5f));
        logArea.setForeground(new Color(200, 210, 220));
        logArea.setBackground(Theme.BG_CODE);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JScrollPane logScroll = new JScrollPane(logArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        logScroll.getViewport().setBackground(Theme.BG_CODE);
        logScroll.getVerticalScrollBar().setUnitIncrement(16);

        logPanel.add(title, BorderLayout.NORTH);
        logPanel.add(logScroll, BorderLayout.CENTER);
        return logPanel;
    }

    // ==================== 底部操作栏 ====================

    private JComponent buildFooter() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(Theme.BG_PRIMARY);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 4, 0, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setBackground(Theme.BG_PRIMARY);

        JButton saveBtn = new JButton("💾 保存配置");
        Theme.styleButton(saveBtn);
        saveBtn.addActionListener(e -> saveConfig());

        JButton loadBtn = new JButton("📂 加载配置");
        Theme.styleButton(loadBtn);
        loadBtn.addActionListener(e -> loadConfig());

        left.add(saveBtn);
        left.add(loadBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(Theme.BG_PRIMARY);

        JButton genBtn = new JButton("🚀 生成插件项目");
        Theme.stylePrimaryButton(genBtn);
        genBtn.addActionListener(e -> generatePlugin());

        right.add(genBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ==================== 操作 ====================

    private void saveConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("plugin-config.json"));
        int r = chooser.showSaveDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return;
        try {
            if (basicPanel != null) basicPanel.applyToConfig();
            config.save(chooser.getSelectedFile());
            log("✔ 配置已保存至 " + chooser.getSelectedFile().getPath());
        } catch (IOException e) {
            log("✖ 保存失败：" + e.getMessage());
            JOptionPane.showMessageDialog(this, "保存失败：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadConfig() {
        JFileChooser chooser = new JFileChooser();
        int r = chooser.showOpenDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return;
        try {
            PluginConfig loaded = PluginConfig.load(chooser.getSelectedFile());
            config.setPluginName(loaded.getPluginName());
            config.setVersion(loaded.getVersion());
            config.setAuthor(loaded.getAuthor());
            config.setDescription(loaded.getDescription());
            config.setArtifactId(loaded.getArtifactId());
            config.setGroupId(loaded.getGroupId());
            config.setMainClass(loaded.getMainClass());
            config.setOutputDir(loaded.getOutputDir());

            config.getItems().clear();
            config.getItems().addAll(loaded.getItems());
            config.getEvents().clear();
            config.getEvents().addAll(loaded.getEvents());
            config.getCommands().clear();
            config.getCommands().addAll(loaded.getCommands());
            config.getSecretRealms().clear();
            config.getSecretRealms().addAll(loaded.getSecretRealms());

            // 从 config 重新刷新 UI —— 这里需要重新构建 Panels
            rebuildAllEntriesPanels();
            if (basicPanel != null) basicPanel.refreshFromConfig();

            log("✔ 已加载配置 " + chooser.getSelectedFile().getPath());
        } catch (IOException e) {
            log("✖ 加载失败：" + e.getMessage());
            JOptionPane.showMessageDialog(this, "加载失败：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 从文件载入后，重建各注册项面板（因为 list 引用已改变）。 */
    private void rebuildAllEntriesPanels() {
        // 找到当前 tabbed pane，替换各 tab 内容
        if (!(getContentPane().getComponent(0) instanceof JPanel root)) return;
        // 简单策略：重新构造整个内容区域。为简单起见，直接 dispose 并打开新窗口。
        // 但更友好的做法是在 Preview 页的组件上做动态刷新。
        // 为稳定起见，这里弹出提示并仅刷新 basicPanel。
        JOptionPane.showMessageDialog(this,
                "配置已从文件载入。\n若要看到更新后的物品/事件/指令/秘境列表，\n请关闭本窗口并重新打开（或重新运行 java -jar xxx.jar --plugin-make-gui）。",
                "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    private void generatePlugin() {
        if (basicPanel != null) basicPanel.applyToConfig();

        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            String msg = "请先修正以下问题：\n\n  · " + String.join("\n  · ", errors);
            JOptionPane.showMessageDialog(this, msg, "⚠ 配置不完整", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            log("▶ 开始生成插件项目（" + config.getPluginName() + " v" + config.getVersion() + "）...");
            log("  物品: " + config.getItems().size() + " 项 / 事件: " + config.getEvents().size() +
                    " 项 / 指令: " + config.getCommands().size() + " 项 / 秘境: " + config.getSecretRealms().size() + " 项");
            log("  触发器合计: " + config.totalTriggers() + " 个");
            CodeGenerator gen = new CodeGenerator(config);
            List<String> files = gen.generateAll();
            log("✔ 生成完成，共 " + files.size() + " 个文件：");
            for (String f : files) log("   - " + f);
            log("  提示：在生成目录中执行 mvn clean package 可编译为 JAR 包");
            log("  将 JAR 放入服务端 ./plugins 目录即可被加载。");

            JOptionPane.showMessageDialog(this,
                    "✔ 项目已生成（共 " + files.size() + " 个文件）\n输出目录：" + config.getOutputDir(),
                    "生成完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            log("✖ 生成失败：" + e.getMessage());
            e.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "生成失败：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<String> validateConfig(PluginConfig c) {
        List<String> errors = new ArrayList<>();
        if (c.getPluginName() == null || c.getPluginName().trim().isEmpty()) errors.add("插件名称不能为空");
        if (c.getVersion() == null || c.getVersion().trim().isEmpty()) errors.add("版本号不能为空");
        if (c.getGroupId() == null || c.getGroupId().trim().isEmpty()) errors.add("GroupId 不能为空");
        if (c.getArtifactId() == null || c.getArtifactId().trim().isEmpty()) errors.add("ArtifactId 不能为空");
        if (c.getMainClass() == null || c.getMainClass().trim().isEmpty()) errors.add("主类名不能为空");
        if (c.getOutputDir() == null || c.getOutputDir().trim().isEmpty()) errors.add("输出目录不能为空");
        // 检查每个注册项的 key
        for (RegistrableEntry e : c.getItems()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty()) {
                errors.add("存在物品未设置 key");
                break;
            }
        }
        return errors;
    }

    private void log(String msg) {
        if (logArea == null) return;
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
