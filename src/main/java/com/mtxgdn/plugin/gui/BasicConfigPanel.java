package com.mtxgdn.plugin.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.File;

/**
 * 基础配置选项卡面板 —— 配置插件名称、版本、包名、输出目录、功能开关。
 * <p>
 * 使用 Swing GridBagLayout 实现响应式布局，遵循现代 UI 设计：
 * - 分组（基础信息 / 包信息 / 输出目录 / 功能开关）
 * - 清晰的标签 + 输入控件
 * - 一致的间距和视觉层次
 */
final class BasicConfigPanel extends JPanel {

    private final PluginConfig config;

    // 基础信息控件
    private final JTextField pluginNameField = new JTextField(25);
    private final JTextField versionField = new JTextField(12);
    private final JTextField authorField = new JTextField(20);
    private final JTextArea descriptionArea = new JTextArea(3, 25);

    // 包信息控件
    private final JTextField groupIdField = new JTextField(25);
    private final JTextField artifactIdField = new JTextField(25);
    private final JTextField mainClassField = new JTextField(20);

    // 输出目录控件
    private final JTextField outputDirField = new JTextField(30);

    // 功能开关控件
    private final JCheckBox includeCommandBox = new JCheckBox("注册示例命令 (/你好)");
    private final JCheckBox includeItemBox = new JCheckBox("注册示例物品");
    private final JCheckBox includeEventBox = new JCheckBox("注册事件触发器");
    private final JCheckBox includeSecretRealmBox = new JCheckBox("注册示例秘境");

    BasicConfigPanel(PluginConfig config) {
        this.config = config;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        add(buildBasicInfoGroup());
        add(Box.createVerticalStrut(10));
        add(buildPackageInfoGroup());
        add(Box.createVerticalStrut(10));
        add(buildOutputGroup());
        add(Box.createVerticalStrut(10));
        add(buildFeatureSwitches());
        add(Box.createVerticalGlue());

        loadFromConfig();
    }

    /** 将输入应用到配置对象。返回 true 表示验证通过。 */
    boolean applyToConfig() {
        // 基本验证
        String name = pluginNameField.getText().trim();
        String groupId = groupIdField.getText().trim();
        String artifactId = artifactIdField.getText().trim();
        String mainClass = mainClassField.getText().trim();
        String output = outputDirField.getText().trim();

        if (name.isEmpty()) { return error("插件名称不能为空"); }
        if (groupId.isEmpty()) { return error("GroupId 不能为空"); }
        if (artifactId.isEmpty()) { return error("ArtifactId 不能为空"); }
        if (mainClass.isEmpty()) { return error("主类名不能为空"); }
        if (output.isEmpty()) { return error("输出目录不能为空"); }
        if (!isValidJavaIdentifier(mainClass)) { return error("主类名 '" + mainClass + "' 不是合法的 Java 标识符"); }

        config.setPluginName(name);
        config.setVersion(versionField.getText().trim());
        config.setAuthor(authorField.getText().trim());
        config.setDescription(descriptionArea.getText().trim());
        config.setGroupId(groupId);
        config.setArtifactId(artifactId);
        config.setMainClass(mainClass);
        config.setOutputDir(output);
        config.setIncludeCommand(includeCommandBox.isSelected());
        config.setIncludeItem(includeItemBox.isSelected());
        config.setIncludeEvent(includeEventBox.isSelected());
        config.setIncludeSecretRealm(includeSecretRealmBox.isSelected());
        return true;
    }

    /** 从 config 加载值到 UI 控件。 */
    void loadFromConfig() {
        pluginNameField.setText(config.getPluginName());
        versionField.setText(config.getVersion());
        authorField.setText(config.getAuthor());
        descriptionArea.setText(config.getDescription());
        groupIdField.setText(config.getGroupId());
        artifactIdField.setText(config.getArtifactId());
        mainClassField.setText(config.getMainClass());
        outputDirField.setText(config.getOutputDir());
        includeCommandBox.setSelected(config.isIncludeCommand());
        includeItemBox.setSelected(config.isIncludeItem());
        includeEventBox.setSelected(config.isIncludeEvent());
        includeSecretRealmBox.setSelected(config.isIncludeSecretRealm());
    }

    // ==================== UI 构建辅助 ====================

    private JPanel buildBasicInfoGroup() {
        JPanel panel = titledPanel("插件信息");
        panel.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        // 名称
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("插件名称:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(pluginNameField, c);

        // 版本
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("版本号:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(versionField, c);

        // 作者
        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("作者:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(authorField, c);

        // 描述（多行文本）
        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("描述:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setFont(UIManager.getFont("TextField.font"));
        JScrollPane sp = new JScrollPane(descriptionArea);
        sp.setPreferredSize(new Dimension(350, 80));
        panel.add(sp, c);

        return panel;
    }

    private JPanel buildPackageInfoGroup() {
        JPanel panel = titledPanel("Java 包信息");
        panel.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("GroupId:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(groupIdField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("ArtifactId:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(artifactIdField, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("主类名:"), c);
        c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(mainClassField, c);

        JLabel hint = new JLabel("包名 = GroupId.ArtifactId（例如 com.example.my-plugin）");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11.0f));
        hint.setForeground(Color.GRAY);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
        panel.add(hint, c);

        return panel;
    }

    private JPanel buildOutputGroup() {
        JPanel panel = titledPanel("输出目录");
        panel.setLayout(new BorderLayout(5, 5));
        panel.add(new JLabel("目标路径:"), BorderLayout.WEST);
        panel.add(outputDirField, BorderLayout.CENTER);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择输出目录");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                outputDirField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildFeatureSwitches() {
        JPanel panel = titledPanel("功能开关（控制要生成的内容）");
        panel.setLayout(new GridLayout(2, 2, 10, 5));
        includeCommandBox.setToolTipText("生成一个 /你好 命令，响应时赠送灵石");
        includeItemBox.setToolTipText("生成一个示例物品类");
        includeEventBox.setToolTipText("生成事件处理器 —— 需配合下方「触发器」使用");
        includeSecretRealmBox.setToolTipText("生成一个示例秘境类");
        panel.add(includeCommandBox);
        panel.add(includeItemBox);
        panel.add(includeEventBox);
        panel.add(includeSecretRealmBox);
        return panel;
    }

    private static JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BorderLayout(10, 5));
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static boolean isValidJavaIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private boolean error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "输入有误", JOptionPane.WARNING_MESSAGE);
        return false;
    }
}
