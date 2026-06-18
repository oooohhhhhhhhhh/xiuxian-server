package com.mtxgdn.plugin.gui;

import javax.swing.*;
import java.awt.*;

/**
 * 基础配置面板 —— 插件基础信息（名称、版本、作者、GroupId、ArtifactId、主类、输出目录等）。
 */
public class BasicConfigPanel extends JPanel {

    private final PluginConfig config;

    private JTextField nameField;
    private JTextField versionField;
    private JTextField authorField;
    private JTextArea descArea;
    private JTextField groupIdField;
    private JTextField artifactIdField;
    private JTextField mainClassField;
    private JTextField outputDirField;

    public BasicConfigPanel(PluginConfig config) {
        this.config = config;
        setLayout(new BorderLayout(10, 10));
        setBackground(Theme.BG_PRIMARY);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
        buildUI();
    }

    private void buildUI() {
        // 顶部说明
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setBackground(Theme.BG_PRIMARY);
        JLabel title = Theme.titleLabel("📋 基础配置", 15f);
        title.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 0));
        JLabel subtitle = Theme.hintLabel("填写插件基本信息 —— 这些信息会被写入 pom.xml、plugin.json 及主类。");
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 0));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        // 主体：两列卡片
        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(Theme.BG_PRIMARY);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(6, 6, 6, 6);

        // ============ 左侧：插件信息 ============
        JPanel infoCard = new JPanel(new GridBagLayout());
        infoCard.setBackground(Theme.BG_SECONDARY);
        infoCard.setBorder(Theme.cardBorder("插件信息"));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 8, 5, 8);
        c.weightx = 0; c.weighty = 0;

        int row = 0;
        c.gridx = 0; c.gridy = row;
        infoCard.add(Theme.label("插件名称："), c);
        c.gridx = 1; c.weightx = 1;
        nameField = new JTextField(config.getPluginName(), 28);
        Theme.styleInput(nameField);
        infoCard.add(nameField, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        infoCard.add(Theme.label("版本："), c);
        c.gridx = 1; c.weightx = 1;
        versionField = new JTextField(config.getVersion(), 28);
        Theme.styleInput(versionField);
        infoCard.add(versionField, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0;
        infoCard.add(Theme.label("作者："), c);
        c.gridx = 1; c.weightx = 1;
        authorField = new JTextField(config.getAuthor(), 28);
        Theme.styleInput(authorField);
        infoCard.add(authorField, c);
        row++;

        c.gridx = 0; c.gridy = row; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        JLabel descLabel = Theme.label("描述：");
        descLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        infoCard.add(descLabel, c);
        c.gridx = 1; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        descArea = new JTextArea(config.getDescription(), 4, 28);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setFont(Theme.fontRegular(12.5f));
        descArea.setBackground(Theme.BG_INPUT);
        descArea.setForeground(Theme.FG_TEXT);
        descArea.setCaretColor(Theme.FG_TEXT);
        descArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        JScrollPane descScroll = new JScrollPane(descArea);
        descScroll.setBorder(BorderFactory.createEmptyBorder());
        descScroll.getViewport().setBackground(Theme.BG_INPUT);
        infoCard.add(descScroll, c);

        g.gridx = 0; g.gridy = 0; g.weightx = 1; g.weighty = 0; g.fill = GridBagConstraints.HORIZONTAL;
        content.add(infoCard, g);

        // ============ 右侧：项目信息 ============
        JPanel projCard = new JPanel(new GridBagLayout());
        projCard.setBackground(Theme.BG_SECONDARY);
        projCard.setBorder(Theme.cardBorder("项目信息"));
        GridBagConstraints d = new GridBagConstraints();
        d.anchor = GridBagConstraints.WEST;
        d.fill = GridBagConstraints.HORIZONTAL;
        d.insets = new Insets(5, 8, 5, 8);
        d.weightx = 0; d.weighty = 0;

        int r2 = 0;
        d.gridx = 0; d.gridy = r2;
        projCard.add(Theme.label("GroupId："), d);
        d.gridx = 1; d.weightx = 1;
        groupIdField = new JTextField(config.getGroupId(), 28);
        Theme.styleInput(groupIdField);
        projCard.add(groupIdField, d);
        r2++;

        d.gridx = 0; d.gridy = r2; d.weightx = 0;
        projCard.add(Theme.label("ArtifactId："), d);
        d.gridx = 1; d.weightx = 1;
        artifactIdField = new JTextField(config.getArtifactId(), 28);
        Theme.styleInput(artifactIdField);
        projCard.add(artifactIdField, d);
        r2++;

        d.gridx = 0; d.gridy = r2; d.weightx = 0;
        projCard.add(Theme.label("主类名："), d);
        d.gridx = 1; d.weightx = 1;
        mainClassField = new JTextField(config.getMainClass(), 28);
        Theme.styleInput(mainClassField);
        projCard.add(mainClassField, d);
        r2++;

        d.gridx = 0; d.gridy = r2; d.weightx = 0;
        JLabel outLabel = Theme.label("输出目录：");
        outLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        projCard.add(outLabel, d);
        d.gridx = 1; d.weightx = 1;
        JPanel outRow = new JPanel(new BorderLayout(6, 0));
        outRow.setBackground(Theme.BG_SECONDARY);
        outputDirField = new JTextField(config.getOutputDir(), 22);
        Theme.styleInput(outputDirField);
        outRow.add(outputDirField, BorderLayout.CENTER);
        JButton browseBtn = new JButton("📁");
        Theme.styleButton(browseBtn);
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择输出目录");
            int ret = fc.showOpenDialog(this);
            if (ret == JFileChooser.APPROVE_OPTION) {
                outputDirField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        outRow.add(browseBtn, BorderLayout.EAST);
        projCard.add(outRow, d);

        // 提示
        d.gridx = 0; d.gridy = r2 + 1; d.gridwidth = 2;
        JLabel hint = Theme.hintLabel("（包名 = GroupId.ArtifactId；主类名建议使用英文大写开头的驼峰式）");
        projCard.add(hint, d);

        g.gridx = 1; g.gridy = 0; g.weightx = 1; g.weighty = 0; g.fill = GridBagConstraints.HORIZONTAL;
        content.add(projCard, g);

        add(content, BorderLayout.CENTER);

        // 底部统计
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 4));
        stats.setBackground(Theme.BG_PRIMARY);
        stats.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));
        JLabel stat1 = Theme.hintLabel("📦 物品: " + config.getItems().size());
        JLabel stat2 = Theme.hintLabel("⚡ 事件: " + config.getEvents().size());
        JLabel stat3 = Theme.hintLabel("💬 指令: " + config.getCommands().size());
        JLabel stat4 = Theme.hintLabel("🏔 秘境: " + config.getSecretRealms().size());
        stats.add(stat1); stats.add(stat2); stats.add(stat3); stats.add(stat4);
        add(stats, BorderLayout.SOUTH);
    }

    /** 将当前 UI 内容写回到 config 对象。 */
    public void applyToConfig() {
        config.setPluginName(nameField.getText());
        config.setVersion(versionField.getText());
        config.setAuthor(authorField.getText());
        config.setDescription(descArea.getText());
        config.setGroupId(groupIdField.getText());
        config.setArtifactId(artifactIdField.getText());
        config.setMainClass(mainClassField.getText());
        config.setOutputDir(outputDirField.getText());
    }

    /** 从 config 重新刷新界面（例如从文件载入）。 */
    public void refreshFromConfig() {
        nameField.setText(config.getPluginName());
        versionField.setText(config.getVersion());
        authorField.setText(config.getAuthor());
        descArea.setText(config.getDescription());
        groupIdField.setText(config.getGroupId());
        artifactIdField.setText(config.getArtifactId());
        mainClassField.setText(config.getMainClass());
        outputDirField.setText(config.getOutputDir());
    }
}
