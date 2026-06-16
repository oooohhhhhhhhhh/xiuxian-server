package com.mtxgdn.plugin.gui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 插件制作工具主窗口 —— GUI 模式入口。
 * <p>
 * 使用 Swing 构建，包含三个选项卡：基础配置 / 触发器 / 预览生成。
 * 顶部工具栏：保存配置 / 加载配置 / 生成插件。
 */
public final class PluginMakerGUI {

    private final PluginConfig config = new PluginConfig();
    private final BasicConfigPanel basicPanel;
    private final TriggerPanel triggerPanel;

    private JFrame frame;
    private JTextArea logArea;
    private JFileChooser fileChooser;

    public PluginMakerGUI() {
        this.basicPanel = new BasicConfigPanel(config);
        this.triggerPanel = new TriggerPanel(config);
    }

    /** 显示 GUI 窗口（阻塞当前线程直到窗口关闭）。 */
    public void show() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            buildFrame();
        });
    }

    private void buildFrame() {
        frame = new JFrame("PluginMaker —— 修仙服务端插件制作工具");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(860, 620));
        frame.setSize(960, 720);
        frame.setLocationRelativeTo(null);

        // 选项卡
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("① 基础配置", basicPanel);
        tabs.addTab("② 触发器管理", triggerPanel);
        tabs.addTab("③ 预览与生成", buildPreviewPanel());

        // 顶部工具栏
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(buildToolButton("💾 保存配置", e -> saveConfig()));
        toolbar.add(buildToolButton("📂 加载配置", e -> loadConfig()));
        toolbar.addSeparator();
        toolbar.add(buildToolButton("🔄 刷新预览", e -> updatePreview()));
        toolbar.addSeparator();
        JButton genBtn = buildToolButton("🚀 生成插件项目", e -> generate());
        genBtn.setFont(genBtn.getFont().deriveFont(Font.BOLD));
        toolbar.add(genBtn);

        // 标题栏
        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel("  修仙服务端 · 插件制作工具  V1.4.1-alpha1");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14.0f));
        header.add(title, BorderLayout.CENTER);
        header.add(toolbar, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 6));

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(header, BorderLayout.NORTH);
        frame.getContentPane().add(tabs, BorderLayout.CENTER);
        frame.getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);
        frame.setVisible(true);

        fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("配置文件 (*.plugin.json)", "plugin.json", "json"));

        log("就绪。请在「基础配置」中填写插件信息，然后在「触发器管理」中配置事件触发器，最后点击「生成插件项目」。");
    }

    // ==================== 预览面板 ====================

    private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        logArea = new JTextArea(12, 70);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JLabel previewLabel = new JLabel("📝 配置摘要：");
        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD, 13.0f));

        panel.add(previewLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        // 生成按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        JButton genBtn = new JButton("🚀 生成插件项目");
        genBtn.setFont(genBtn.getFont().deriveFont(Font.BOLD, 13.0f));
        genBtn.addActionListener(e -> generate());
        bottom.add(genBtn);
        panel.add(bottom, BorderLayout.SOUTH);

        updatePreview();
        return panel;
    }

    private void updatePreview() {
        basicPanel.applyToConfig();
        triggerPanel.applyToConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("插件名称: ").append(config.getPluginName()).append("\n");
        sb.append("版本号:   ").append(config.getVersion()).append("\n");
        sb.append("作者:     ").append(config.getAuthor()).append("\n");
        sb.append("描述:     ").append(config.getDescription()).append("\n");
        sb.append("Java 包:  ").append(config.getPackageName()).append(" (主类: ").append(config.getMainClass()).append(")\n");
        sb.append("输出目录: ").append(config.getOutputDir()).append("\n");
        sb.append("────────────────────────────────────────\n");
        sb.append("功能模块:\n");
        sb.append("  • ").append(flag(config.isIncludeCommand())).append(" 示例命令\n");
        sb.append("  • ").append(flag(config.isIncludeItem())).append(" 示例物品\n");
        sb.append("  • ").append(flag(config.isIncludeEvent())).append(" 事件系统\n");
        sb.append("  • ").append(flag(config.isIncludeSecretRealm())).append(" 示例秘境\n");
        sb.append("\n事件触发器: ").append(config.getTriggers().size()).append(" 条\n");
        for (int i = 0; i < config.getTriggers().size(); i++) {
            com.mtxgdn.plugin.gui.TriggerConfig t = config.getTriggers().get(i);
            sb.append(String.format("  %02d. %s  %s → %s (%s)%n",
                    i + 1,
                    t.isEnabled() ? "🟢" : "⚪",
                    t.getEventType() == com.mtxgdn.plugin.event.PluginEvent.Type.CUSTOM
                            ? "自定义[" + t.getCustomKey() + "]"
                            : t.getEventType().name(),
                    t.getAction().label,
                    t.getDescription()));
        }
        sb.append("\n── 预计将生成以下文件 ──\n");
        sb.append("  pom.xml\n");
        sb.append("  plugin.json\n");
        sb.append("  src/main/java/").append(config.getPackagePath()).append("/").append(config.getMainClass()).append(".java\n");
        if (config.isIncludeCommand()) {
            sb.append("  src/main/java/").append(config.getPackagePath()).append("/command/HelloCommand.java\n");
        }
        if (config.isIncludeItem()) {
            sb.append("  src/main/java/").append(config.getPackagePath()).append("/item/DemoItem.java\n");
        }
        if (config.isIncludeEvent() || !config.getTriggers().isEmpty()) {
            sb.append("  src/main/java/").append(config.getPackagePath()).append("/").append(config.getMainClass()).append("Triggers.java\n");
        }
        if (config.isIncludeSecretRealm()) {
            sb.append("  src/main/java/").append(config.getPackagePath()).append("/").append(config.getMainClass()).append("Realm.java\n");
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(0);
    }

    // ==================== 工具栏操作 ====================

    private void saveConfig() {
        basicPanel.applyToConfig();
        triggerPanel.applyToConfig();
        fileChooser.setSelectedFile(new File(config.getPluginName() + ".plugin.json"));
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                File f = fileChooser.getSelectedFile();
                if (!f.getName().endsWith(".json")) f = new File(f.getAbsolutePath() + ".json");
                config.save(f);
                log("✅ 配置已保存到 " + f.getAbsolutePath());
                JOptionPane.showMessageDialog(frame, "配置已保存到 " + f.getName(), "完成", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                log("❌ 保存失败: " + ex.getMessage());
                JOptionPane.showMessageDialog(frame, "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void loadConfig() {
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                PluginConfig loaded = PluginConfig.load(fileChooser.getSelectedFile());
                // 将加载的配置应用到当前 config
                copyConfig(loaded, config);
                basicPanel.loadFromConfig();
                triggerPanel.refreshTable();
                updatePreview();
                log("✅ 配置已从 " + fileChooser.getSelectedFile().getName() + " 加载");
                JOptionPane.showMessageDialog(frame, "配置已加载", "完成", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                log("❌ 加载失败: " + ex.getMessage());
                JOptionPane.showMessageDialog(frame, "加载失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void generate() {
        if (!basicPanel.applyToConfig()) return;
        triggerPanel.applyToConfig();
        try {
            log("🚀 开始生成插件项目...");
            log("  输出目录: " + new File(config.getOutputDir()).getAbsolutePath());
            List<String> files = new CodeGenerator(config).generateAll();
            log("✅ 生成完成！共 " + files.size() + " 个文件：");
            for (String f : files) log("   - " + f);
            log("");
            log("下一步:");
            log("  1) 进入目录: cd " + config.getOutputDir());
            log("  2) 将服务端 jar 安装到本地 Maven 仓库（详见 pom.xml）");
            log("  3) 执行: mvn package");
            log("  4) 将 target/" + config.getArtifactId() + "-" + config.getVersion() + ".jar 放入服务端 ./plugins/");
            log("  5) 重启服务端，插件将被自动加载 ✨");
            JOptionPane.showMessageDialog(frame,
                    "生成完成！共 " + files.size() + " 个文件。\n输出目录: " + new File(config.getOutputDir()).getAbsolutePath(),
                    "插件项目生成成功",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            log("❌ 生成失败: " + ex.getMessage());
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "生成失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== 辅助 ====================

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        p.add(new JLabel("PluginMaker · 事件系统 V1.4.1-alpha1"), BorderLayout.WEST);
        return p;
    }

    private JButton buildToolButton(String text, java.awt.event.ActionListener a) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(a);
        return b;
    }

    private void log(String msg) {
        if (logArea != null) {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
        System.out.println("[PluginMaker] " + msg);
    }

    private static String flag(boolean b) { return b ? "[x]" : "[ ]"; }

    private static void copyConfig(PluginConfig from, PluginConfig to) {
        to.setPluginName(from.getPluginName());
        to.setVersion(from.getVersion());
        to.setAuthor(from.getAuthor());
        to.setDescription(from.getDescription());
        to.setGroupId(from.getGroupId());
        to.setArtifactId(from.getArtifactId());
        to.setMainClass(from.getMainClass());
        to.setOutputDir(from.getOutputDir());
        to.setIncludeCommand(from.isIncludeCommand());
        to.setIncludeItem(from.isIncludeItem());
        to.setIncludeEvent(from.isIncludeEvent());
        to.setIncludeSecretRealm(from.isIncludeSecretRealm());
        to.getTriggers().clear();
        to.getTriggers().addAll(from.getTriggers());
    }
}
