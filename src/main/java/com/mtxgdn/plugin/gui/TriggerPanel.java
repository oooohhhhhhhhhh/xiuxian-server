package com.mtxgdn.plugin.gui;

import com.mtxgdn.plugin.event.PluginEvent;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * 触发器管理面板 —— 表格展示所有触发器、增删改查操作。
 * <p>
 * 功能：
 * <ul>
 *   <li>表格列出所有触发器（启用状态、事件类型、条件、动作、描述）</li>
 *   <li>新增 / 编辑 / 删除 / 上移 / 下移按钮</li>
 *   <li>双击某行或点"编辑"打开编辑对话框</li>
 * </ul>
 */
final class TriggerPanel extends JPanel {

    private final PluginConfig config;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel = new JLabel(" 共 0 条触发器 ");

    private static final String[] COLUMNS = {"启用", "事件类型", "条件", "动作", "描述"};

    TriggerPanel(PluginConfig config) {
        this.config = config;
        setLayout(new BorderLayout(10, 5));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 表格
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                if (column == 0) return Boolean.class;
                return String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // 只有"启用"列可直接在表格中切换
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(250);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateStatus());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });

        // 按钮栏
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        buttonPanel.add(buildButton("➕ 新增", e -> addNew()));
        buttonPanel.add(buildButton("✏️ 编辑", e -> editSelected()));
        buttonPanel.add(buildButton("🗑️ 删除", e -> deleteSelected()));
        buttonPanel.add(Box.createHorizontalStrut(15));
        buttonPanel.add(buildButton("⬆️ 上移", e -> moveUp()));
        buttonPanel.add(buildButton("⬇️ 下移", e -> moveDown()));

        // 顶部说明
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel("配置事件触发器：当指定事件触发时执行动作（发消息/给物品/执行代码等）"), BorderLayout.CENTER);

        // 组装
        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        add(statusLabel, BorderLayout.EAST);

        refreshTable();
    }

    /** 将表格中修改（启用状态）同步到 config。 */
    void applyToConfig() {
        int row = table.getSelectedRow();
        // 提交单元格编辑（避免最后一次编辑丢失）
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        // 同步启用状态
        for (int i = 0; i < tableModel.getRowCount() && i < config.getTriggers().size(); i++) {
            config.getTriggers().get(i).setEnabled((Boolean) tableModel.getValueAt(i, 0));
        }
        if (row >= 0 && row < table.getRowCount()) table.setRowSelectionInterval(row, row);
    }

    void refreshTable() {
        int select = table.getSelectedRow();
        tableModel.setRowCount(0);
        for (TriggerConfig t : config.getTriggers()) {
            Object[] row = {
                    t.isEnabled(),
                    t.getEventType() == PluginEvent.Type.CUSTOM
                            ? "自定义[" + t.getCustomKey() + "]"
                            : t.getEventType().name(),
                    t.getCondition(),
                    t.getAction().label + (t.getActionParam().isEmpty() ? "" : ": " + truncate(t.getActionParam(), 24)),
                    t.getDescription()
            };
            tableModel.addRow(row);
        }
        if (select >= 0 && select < tableModel.getRowCount()) table.setRowSelectionInterval(select, select);
        updateStatus();
    }

    // ==================== 操作方法 ====================

    private void addNew() {
        TriggerConfig newTrigger = new TriggerConfig();
        newTrigger.setDescription("新建触发器 #" + (config.getTriggers().size() + 1));
        if (showEditor(newTrigger, "新增触发器")) {
            config.getTriggers().add(newTrigger);
            refreshTable();
            table.setRowSelectionInterval(config.getTriggers().size() - 1, config.getTriggers().size() - 1);
        }
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "请先选择一行", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        TriggerConfig t = config.getTriggers().get(row);
        if (showEditor(t, "编辑触发器 · " + t.getDescription())) {
            refreshTable();
            table.setRowSelectionInterval(row, row);
        }
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        TriggerConfig t = config.getTriggers().get(row);
        int choice = JOptionPane.showConfirmDialog(this,
                "确定要删除触发器\n  " + t.getDescription() + "\n？",
                "删除确认", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            config.getTriggers().remove(row);
            refreshTable();
        }
    }

    private void moveUp() {
        int row = table.getSelectedRow();
        if (row <= 0) return;
        java.util.List<TriggerConfig> list = config.getTriggers();
        TriggerConfig tmp = list.get(row - 1);
        list.set(row - 1, list.get(row));
        list.set(row, tmp);
        refreshTable();
        table.setRowSelectionInterval(row - 1, row - 1);
    }

    private void moveDown() {
        int row = table.getSelectedRow();
        java.util.List<TriggerConfig> list = config.getTriggers();
        if (row < 0 || row >= list.size() - 1) return;
        TriggerConfig tmp = list.get(row + 1);
        list.set(row + 1, list.get(row));
        list.set(row, tmp);
        refreshTable();
        table.setRowSelectionInterval(row + 1, row + 1);
    }

    private void updateStatus() {
        int active = 0;
        for (TriggerConfig t : config.getTriggers()) if (t.isEnabled()) active++;
        statusLabel.setText(" 共 " + config.getTriggers().size() + " 条（启用 " + active + "） ");
    }

    // ==================== 编辑器对话框 ====================

    private boolean showEditor(TriggerConfig trigger, String title) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), title, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 表单内容
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        // 描述
        JTextField descField = new JTextField(trigger.getDescription(), 30);
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("描述:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(descField, c);
        c.fill = GridBagConstraints.NONE;

        // 事件类型
        JComboBox<PluginEvent.Type> typeCombo = new JComboBox<>(PluginEvent.Type.values());
        typeCombo.setSelectedItem(trigger.getEventType());
        c.gridx = 0; c.gridy = 1; c.weightx = 0; form.add(new JLabel("事件类型:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(typeCombo, c);
        c.fill = GridBagConstraints.NONE;

        // 自定义 key（当选择 CUSTOM 时启用）
        JTextField customKeyField = new JTextField(trigger.getCustomKey(), 25);
        customKeyField.setEnabled(trigger.getEventType() == PluginEvent.Type.CUSTOM);
        c.gridx = 0; c.gridy = 2; form.add(new JLabel("自定义 key:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(customKeyField, c);
        c.fill = GridBagConstraints.NONE;
        typeCombo.addActionListener(e -> {
            PluginEvent.Type t = (PluginEvent.Type) typeCombo.getSelectedItem();
            customKeyField.setEnabled(t == PluginEvent.Type.CUSTOM);
        });

        // 条件
        JTextField condField = new JTextField(trigger.getCondition(), 30);
        condField.setToolTipText("例如: command=/你好 或 playerId=123, 多条件逗号分隔");
        c.gridx = 0; c.gridy = 3; form.add(new JLabel("触发条件:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(condField, c);
        c.fill = GridBagConstraints.NONE;

        // 动作
        JComboBox<TriggerConfig.Action> actionCombo = new JComboBox<>(TriggerConfig.Action.values());
        actionCombo.setSelectedItem(trigger.getAction());
        c.gridx = 0; c.gridy = 4; form.add(new JLabel("响应动作:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(actionCombo, c);
        c.fill = GridBagConstraints.NONE;

        // 动作参数
        JTextField actionParamField = new JTextField(trigger.getActionParam(), 30);
        actionParamField.setToolTipText("消息内容 / 灵石数量 / 物品 key");
        c.gridx = 0; c.gridy = 5; form.add(new JLabel("动作参数:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(actionParamField, c);
        c.fill = GridBagConstraints.NONE;

        // Java 代码（多行，当动作 = RUN_JAVA 时启用）
        JTextArea javaArea = new JTextArea(trigger.getJavaCode(), 6, 40);
        javaArea.setEnabled(trigger.getAction() == TriggerConfig.Action.RUN_JAVA);
        javaArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        c.gridx = 0; c.gridy = 6; c.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Java 代码:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.BOTH; c.anchor = GridBagConstraints.WEST;
        JScrollPane codeScroll = new JScrollPane(javaArea);
        form.add(codeScroll, c);
        c.fill = GridBagConstraints.NONE;
        actionCombo.addActionListener(e -> {
            TriggerConfig.Action a = (TriggerConfig.Action) actionCombo.getSelectedItem();
            javaArea.setEnabled(a == TriggerConfig.Action.RUN_JAVA);
            if (a == TriggerConfig.Action.RUN_JAVA && javaArea.getText().trim().isEmpty()) {
                javaArea.setText("// 在此编写自定义代码\n// event 变量和 context 均可用\ncontext.getLogger().info(\"自定义代码被执行\");");
            }
        });

        // 启用状态
        JCheckBox enabledBox = new JCheckBox("触发器启用", trigger.isEnabled());
        c.gridx = 0; c.gridy = 7; c.gridwidth = 2;
        form.add(enabledBox, c);

        panel.add(form, BorderLayout.CENTER);

        // 按钮行
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        final boolean[] result = {false};
        JButton ok = new JButton("确定");
        JButton cancel = new JButton("取消");
        ok.addActionListener(e -> {
            String desc = descField.getText().trim();
            if (desc.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "描述不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            trigger.setDescription(desc);
            trigger.setEventType((PluginEvent.Type) typeCombo.getSelectedItem());
            trigger.setCustomKey(customKeyField.getText().trim());
            trigger.setCondition(condField.getText().trim());
            trigger.setAction((TriggerConfig.Action) actionCombo.getSelectedItem());
            trigger.setActionParam(actionParamField.getText().trim());
            trigger.setJavaCode(javaArea.getText());
            trigger.setEnabled(enabledBox.isSelected());
            result[0] = true;
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(ok);
        buttons.add(cancel);
        panel.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return result[0];
    }

    // ==================== 辅助 ====================

    private static JButton buildButton(String text, ActionListener a) {
        JButton b = new JButton(text);
        b.addActionListener(a);
        return b;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
