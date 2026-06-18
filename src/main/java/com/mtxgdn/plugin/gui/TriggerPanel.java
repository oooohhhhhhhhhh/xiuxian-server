package com.mtxgdn.plugin.gui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.util.List;

/**
 * 触发器管理面板（新版）—— 直接操作 List&lt;Trigger&gt;。
 * <p>
 * 用于某个注册项（物品/事件/指令/秘境）的触发器列表管理。
 * 上方是操作按钮栏 + 表格（带交替行底色），双击或点击"编辑"打开编辑器对话框。
 */
final class TriggerPanel extends JPanel {

    private final List<Trigger> triggers;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    private static final String[] COLUMNS = {"启用", "时机", "动作", "描述"};

    TriggerPanel(List<Trigger> triggers) {
        this.triggers = triggers;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(Theme.BG_SECONDARY);

        // ====== 顶部：按钮栏 ======
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topBar.setBackground(Theme.BG_SECONDARY);

        JButton addBtn = new JButton("＋ 添加触发器");
        Theme.styleButton(addBtn);
        addBtn.addActionListener(e -> addNew());

        JButton editBtn = new JButton("✎ 编辑");
        Theme.styleButton(editBtn);
        editBtn.addActionListener(e -> editSelected());

        JButton delBtn = new JButton("− 删除");
        Theme.styleButton(delBtn);
        delBtn.addActionListener(e -> deleteSelected());

        JButton toggleBtn = new JButton("☑ 启用/禁用");
        Theme.styleButton(toggleBtn);
        toggleBtn.addActionListener(e -> toggleEnabled());

        topBar.add(addBtn);
        topBar.add(editBtn);
        topBar.add(delBtn);
        topBar.add(toggleBtn);

        add(topBar, BorderLayout.NORTH);

        // ====== 表格 ======
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setFont(Theme.fontRegular(12f));
        table.setRowHeight(28);
        table.setBackground(Theme.BG_SECONDARY);
        table.setForeground(Theme.FG_TEXT);
        table.setGridColor(Theme.DIVIDER);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(new Color(180, 200, 240));
        table.setSelectionForeground(Theme.FG_HEADLINE);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);

        JTableHeader header = table.getTableHeader();
        header.setFont(Theme.fontBold(12f));
        header.setBackground(new Color(240, 242, 246));
        header.setForeground(Theme.FG_HEADLINE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_COLOR));

        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        scroll.getViewport().setBackground(Theme.BG_SECONDARY);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ====== 底部：状态 ======
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bottom.setBackground(Theme.BG_SECONDARY);
        statusLabel = Theme.hintLabel("");
        bottom.add(statusLabel);
        add(bottom, BorderLayout.SOUTH);

        refreshTable();
    }

    void refreshTable() {
        tableModel.setRowCount(0);
        for (Trigger t : triggers) {
            Object[] row = {
                    t.isEnabled() ? "☑" : "☐",
                    t.getTriggerWhen().label,
                    t.getAction().label,
                    t.getDescription() == null || t.getDescription().isEmpty()
                            ? "(未命名)" : t.getDescription()
            };
            tableModel.addRow(row);
        }
        updateStatus();
    }

    private void updateStatus() {
        int active = 0;
        for (Trigger t : triggers) if (t.isEnabled()) active++;
        statusLabel.setText("共 " + triggers.size() + " 条触发器  ·  已启用 " + active + " 条");
    }

    // ==================== 操作 ====================

    private void addNew() {
        Trigger newTrigger = new Trigger();
        newTrigger.setDescription("触发器 #" + (triggers.size() + 1));
        if (showEditor(newTrigger, "✨ 新增触发器")) {
            triggers.add(newTrigger);
            refreshTable();
            int idx = triggers.size() - 1;
            if (idx >= 0) table.setRowSelectionInterval(idx, idx);
        }
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "请先选择一行触发器", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Trigger t = triggers.get(row);
        if (showEditor(t, "✎ 编辑触发器 · " + t.getDescription())) {
            refreshTable();
            table.setRowSelectionInterval(row, row);
        }
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        Trigger t = triggers.get(row);
        int choice = JOptionPane.showConfirmDialog(this,
                "确定要删除触发器：\n  " + t.getDescription(),
                "⚠ 删除确认",
                JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            triggers.remove(row);
            refreshTable();
        }
    }

    private void toggleEnabled() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= triggers.size()) return;
        Trigger t = triggers.get(row);
        t.setEnabled(!t.isEnabled());
        refreshTable();
        table.setRowSelectionInterval(row, row);
    }

    private boolean showEditor(Trigger trigger, String title) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), title, true);
        dialog.setLayout(new BorderLayout(12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.BG_PRIMARY);
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(4, 6, 4, 6);
        g.weightx = 0; g.weighty = 0;

        // 启用状态
        g.gridx = 0; g.gridy = 0;
        form.add(Theme.label("启用："), g);
        g.gridx = 1; g.weightx = 1;
        JCheckBox enabledBox = new JCheckBox("此触发器生效", trigger.isEnabled());
        enabledBox.setFont(Theme.fontRegular(12.5f));
        enabledBox.setBackground(Theme.BG_PRIMARY);
        enabledBox.setForeground(Theme.FG_TEXT);
        form.add(enabledBox, g);

        // 触发时机
        g.gridx = 0; g.gridy++; g.weightx = 0;
        form.add(Theme.label("触发时机："), g);
        g.gridx = 1; g.weightx = 1;
        JComboBox<Trigger.When> whenCombo = new JComboBox<>(Trigger.When.values());
        whenCombo.setSelectedItem(trigger.getTriggerWhen());
        Theme.styleComboBox(whenCombo);
        form.add(whenCombo, g);

        // 动作
        g.gridx = 0; g.gridy++; g.weightx = 0;
        form.add(Theme.label("动作："), g);
        g.gridx = 1; g.weightx = 1;
        JComboBox<Trigger.Action> actionCombo = new JComboBox<>(Trigger.Action.values());
        actionCombo.setSelectedItem(trigger.getAction());
        Theme.styleComboBox(actionCombo);
        form.add(actionCombo, g);

        // 参数
        g.gridx = 0; g.gridy++; g.weightx = 0;
        form.add(Theme.label("参数："), g);
        g.gridx = 1; g.weightx = 1;
        JTextField paramField = new JTextField(trigger.getActionParam(), 24);
        Theme.styleInput(paramField);
        form.add(paramField, g);

        // 描述
        g.gridx = 0; g.gridy++; g.weightx = 0;
        form.add(Theme.label("描述："), g);
        g.gridx = 1; g.weightx = 1;
        JTextField descField = new JTextField(trigger.getDescription(), 24);
        Theme.styleInput(descField);
        form.add(descField, g);

        // Java 代码
        g.gridx = 0; g.gridy++; g.weightx = 0; g.anchor = GridBagConstraints.NORTHWEST;
        JLabel codeLabel = Theme.label("自定义代码：");
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        form.add(codeLabel, g);
        g.gridx = 1; g.weightx = 1; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        JTextArea codeArea = new JTextArea(trigger.getJavaCode(), 8, 34);
        codeArea.setFont(Theme.fontMono(12f));
        codeArea.setBackground(Theme.BG_CODE);
        codeArea.setForeground(new Color(220, 224, 232));
        codeArea.setCaretColor(Color.WHITE);
        codeArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane codeScroll = new JScrollPane(codeArea);
        codeScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        codeScroll.getViewport().setBackground(Theme.BG_CODE);
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);
        form.add(codeScroll, g);

        // 提示
        g.gridx = 0; g.gridy++; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1; g.weighty = 0; g.anchor = GridBagConstraints.WEST;
        JLabel hint = Theme.hintLabel(
                "提示：参数在动作=发送消息/给予灵石/给予物品时生效。动作=执行代码时，下方代码会被原样生成到插件源码。");
        form.add(hint, g);

        dialog.add(form, BorderLayout.CENTER);

        // 按钮
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.setBackground(Theme.BG_PRIMARY);
        JButton cancel = new JButton("取消");
        Theme.styleButton(cancel);
        JButton ok = new JButton("✔ 保存");
        Theme.stylePrimaryButton(ok);

        final boolean[] result = {false};
        ok.addActionListener(e -> {
            trigger.setEnabled(enabledBox.isSelected());
            Object w = whenCombo.getSelectedItem();
            if (w instanceof Trigger.When) trigger.setTriggerWhen((Trigger.When) w);
            Object a = actionCombo.getSelectedItem();
            if (a instanceof Trigger.Action) trigger.setAction((Trigger.Action) a);
            trigger.setActionParam(paramField.getText());
            trigger.setJavaCode(codeArea.getText());
            trigger.setDescription(descField.getText());
            result[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());

        buttons.add(cancel);
        buttons.add(ok);
        dialog.add(buttons, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setSize(Math.max(560, dialog.getWidth()), Math.max(520, dialog.getHeight()));
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        dialog.setResizable(true);
        dialog.setVisible(true);
        return result[0];
    }
}
