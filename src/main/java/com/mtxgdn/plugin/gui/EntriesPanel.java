package com.mtxgdn.plugin.gui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.List;

/**
 * 通用注册项编辑面板 —— 可用于物品/事件/秘境/指令。
 * <p>
 * 结构：
 * <pre>
 * ┌───────────────────────────────────────────────┐
 * │  【左侧列表】       【右侧详情】                │
 * │  ┌────────────┐  ┌────────────────────────┐   │
 * │  │ 物品1   ★ │  │ 名称: [____]            │   │
 * │  │ 物品2     │  │ Key : [____]            │   │
 * │  │ ...       │  │ 描述: [____]            │   │
 * │  [+] [-] [↑]│  │ 额外: [____]            │   │
 * │                │ ───────────触发器────────── │   │
 * │                │  时机          动作     [+/-]│  │
 * │                │  使用时  →  发送消息    [编辑] │  │
 * │                │  获得时  →  仅记录日志  [编辑] │  │
 * │                └────────────────────────┘   │
 * └───────────────────────────────────────────────┘
 * </pre>
 */
public class EntriesPanel extends JPanel {

    private final RegistrableEntry.Type entryType;
    private final List<RegistrableEntry> entries;
    private final DefaultListModel<String> listModel;
    private final JList<String> list;
    private final JPanel detailPanel;
    private JTextField nameField, keyField, descField, extraField;
    private DefaultTableModel triggerTableModel;
    private int selectedIndex = -1;

    /** 创建一个注册项编辑面板。 */
    public EntriesPanel(RegistrableEntry.Type type, List<RegistrableEntry> entries) {
        this.entryType = type;
        this.entries = entries;
        this.listModel = new DefaultListModel<>();
        this.list = new JList<>(listModel);
        this.detailPanel = new JPanel();

        setBackground(Theme.BG_PRIMARY);
        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        refreshList();

        // ===== 左侧：列表 + 按钮 =====
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        leftPanel.setBackground(Theme.BG_PRIMARY);

        JLabel titleLabel = Theme.titleLabel("📋 " + type.label + "列表", 14f);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 0));
        leftPanel.add(titleLabel, BorderLayout.NORTH);

        list.setFont(Theme.fontRegular(12.5f));
        list.setBackground(Theme.BG_SECONDARY);
        list.setForeground(Theme.FG_TEXT);
        list.setSelectionBackground(new Color(180, 200, 240));
        list.setSelectionForeground(Theme.FG_HEADLINE);
        list.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = list.getSelectedIndex();
                if (idx >= 0 && idx < entries.size()) {
                    selectEntry(idx);
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        listScroll.getViewport().setBackground(Theme.BG_SECONDARY);
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        listButtons.setBackground(Theme.BG_PRIMARY);

        JButton addBtn = new JButton("＋ 新增");
        Theme.styleButton(addBtn);
        addBtn.addActionListener(e -> addEntry());

        JButton delBtn = new JButton("− 删除");
        Theme.styleButton(delBtn);
        delBtn.addActionListener(e -> removeEntry());

        listButtons.add(addBtn);
        listButtons.add(delBtn);
        leftPanel.add(listButtons, BorderLayout.SOUTH);

        // ===== 右侧：详情 =====
        JScrollPane detailScroll = new JScrollPane(buildDetailPanel());
        detailScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        detailScroll.getViewport().setBackground(Theme.BG_PRIMARY);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.getHorizontalScrollBar().setUnitIncrement(16);

        // ===== 组合：左28% 右72% =====
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detailScroll);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setResizeWeight(0.28);
        split.setDividerSize(3);
        split.setBackground(Theme.BG_PRIMARY);
        add(split, BorderLayout.CENTER);

        // 默认选中第一个
        if (!entries.isEmpty()) {
            list.setSelectedIndex(0);
            selectEntry(0);
        }
    }

    private JPanel buildDetailPanel() {
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setBackground(Theme.BG_PRIMARY);
        detailPanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

        // ===== 基本信息 =====
        JPanel infoCard = new JPanel(new GridBagLayout());
        infoCard.setBackground(Theme.BG_SECONDARY);
        infoCard.setBorder(Theme.cardBorder("⚙️ 基本信息"));

        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(4, 6, 4, 6);
        g.weightx = 0;
        g.gridx = 0; g.gridy = 0;
        infoCard.add(Theme.label("显示名称："), g);
        g.gridx = 1; g.weightx = 1;
        nameField = new JTextField(30);
        Theme.styleInput(nameField);
        nameField.getDocument().addDocumentListener(new SimpleDocListener(() -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                entries.get(selectedIndex).setName(nameField.getText());
                refreshList();
            }
        }));
        infoCard.add(nameField, g);

        g.gridx = 0; g.gridy++; g.weightx = 0;
        infoCard.add(Theme.label("唯一Key："), g);
        g.gridx = 1; g.weightx = 1;
        keyField = new JTextField(30);
        Theme.styleInput(keyField);
        keyField.getDocument().addDocumentListener(new SimpleDocListener(() -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                entries.get(selectedIndex).setKey(keyField.getText());
                refreshList();
            }
        }));
        infoCard.add(keyField, g);

        g.gridx = 0; g.gridy++; g.weightx = 0;
        infoCard.add(Theme.label("描述："), g);
        g.gridx = 1; g.weightx = 1;
        descField = new JTextField(30);
        Theme.styleInput(descField);
        descField.getDocument().addDocumentListener(new SimpleDocListener(() -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                entries.get(selectedIndex).setDescription(descField.getText());
            }
        }));
        infoCard.add(descField, g);

        g.gridx = 0; g.gridy++; g.weightx = 0;
        infoCard.add(Theme.label("额外配置："), g);
        g.gridx = 1; g.weightx = 1;
        extraField = new JTextField(30);
        Theme.styleInput(extraField);
        extraField.getDocument().addDocumentListener(new SimpleDocListener(() -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                entries.get(selectedIndex).setExtraInfo(extraField.getText());
            }
        }));
        infoCard.add(extraField, g);

        // 提示
        g.gridx = 0; g.gridy++; g.gridwidth = 2;
        JLabel hint = Theme.hintLabel("（额外配置为可选字段，根据注册项类型自定义；例如秘境的" +
                "\"最低等级: 1 | 体力消耗: 10\"）");
        infoCard.add(hint, g);

        detailPanel.add(infoCard);
        detailPanel.add(Box.createVerticalStrut(10));

        // ===== 触发器区 =====
        JPanel triggerCard = new JPanel(new BorderLayout(8, 8));
        triggerCard.setBackground(Theme.BG_SECONDARY);
        triggerCard.setBorder(Theme.cardBorder("⚡ 触发器（" + entryType.label + "的触发时机 + 动作）"));

        JPanel triggerTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        triggerTop.setBackground(Theme.BG_SECONDARY);
        JLabel triggerHint = Theme.hintLabel(
                "触发器定义：当该" + entryType.label + "发生某个时机时，系统执行相应动作。");
        triggerTop.add(triggerHint);
        triggerCard.add(triggerTop, BorderLayout.NORTH);

        // 触发器表格
        String[] columns = {"启用", "触发时机", "动作", "参数", "描述"};
        triggerTableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable triggerTable = new JTable(triggerTableModel);
        triggerTable.setFont(Theme.fontRegular(12f));
        triggerTable.setRowHeight(28);
        triggerTable.setBackground(Theme.BG_SECONDARY);
        triggerTable.setForeground(Theme.FG_TEXT);
        triggerTable.getTableHeader().setFont(Theme.fontBold(12f));
        triggerTable.getTableHeader().setBackground(new Color(240, 242, 246));
        triggerTable.getTableHeader().setForeground(Theme.FG_HEADLINE);
        triggerTable.setGridColor(Theme.DIVIDER);
        triggerTable.setShowGrid(false);
        triggerTable.setShowHorizontalLines(true);
        triggerTable.setIntercellSpacing(new Dimension(0, 0));

        JScrollPane tableScroll = new JScrollPane(triggerTable);
        tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        tableScroll.getViewport().setBackground(Theme.BG_SECONDARY);
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        triggerCard.add(tableScroll, BorderLayout.CENTER);

        // 触发器按钮
        JPanel trigBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        trigBtns.setBackground(Theme.BG_SECONDARY);

        JButton addTrigBtn = new JButton("＋ 添加触发器");
        Theme.styleButton(addTrigBtn);
        addTrigBtn.addActionListener(e -> addTrigger());

        JButton editTrigBtn = new JButton("✎ 编辑");
        Theme.styleButton(editTrigBtn);
        editTrigBtn.addActionListener(e -> {
            int row = triggerTable.getSelectedRow();
            if (row < 0 || row >= entries.get(selectedIndex).getTriggers().size()) {
                JOptionPane.showMessageDialog(this, "请先选择要编辑的触发器。");
                return;
            }
            editTrigger(row);
        });

        JButton delTrigBtn = new JButton("− 删除触发器");
        Theme.styleButton(delTrigBtn);
        delTrigBtn.addActionListener(e -> {
            int row = triggerTable.getSelectedRow();
            if (row < 0 || row >= entries.get(selectedIndex).getTriggers().size()) {
                JOptionPane.showMessageDialog(this, "请先选择要删除的触发器。");
                return;
            }
            entries.get(selectedIndex).getTriggers().remove(row);
            refreshTriggerTable();
        });

        JButton toggleBtn = new JButton("☑ 启用/禁用");
        Theme.styleButton(toggleBtn);
        toggleBtn.addActionListener(e -> {
            int row = triggerTable.getSelectedRow();
            if (row < 0 || row >= entries.get(selectedIndex).getTriggers().size()) return;
            Trigger t = entries.get(selectedIndex).getTriggers().get(row);
            t.setEnabled(!t.isEnabled());
            refreshTriggerTable();
        });

        trigBtns.add(addTrigBtn);
        trigBtns.add(editTrigBtn);
        trigBtns.add(delTrigBtn);
        trigBtns.add(toggleBtn);
        triggerCard.add(trigBtns, BorderLayout.SOUTH);

        detailPanel.add(triggerCard);

        setDetailEnabled(false);
        return detailPanel;
    }

    // ==================== 操作方法 ====================

    private void addEntry() {
        RegistrableEntry newEntry;
        switch (entryType) {
            case EVENT: newEntry = RegistrableEntry.newEvent("custom_event_" + (entries.size() + 1), "新事件"); break;
            case COMMAND: newEntry = RegistrableEntry.newCommand("/新指令", "新指令"); break;
            case SECRET_REALM: newEntry = RegistrableEntry.newSecretRealm("realm_" + (entries.size() + 1), "新秘境"); break;
            case ITEM:
            default: newEntry = RegistrableEntry.newItem("item_" + (entries.size() + 1), "新物品"); break;
        }
        entries.add(newEntry);
        refreshList();
        list.setSelectedIndex(entries.size() - 1);
        selectEntry(entries.size() - 1);
    }

    private void removeEntry() {
        int idx = list.getSelectedIndex();
        if (idx < 0 || idx >= entries.size()) return;
        int confirm = JOptionPane.showConfirmDialog(this,
                "确认删除【" + entries.get(idx).getName() + "】吗？",
                "删除确认", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        entries.remove(idx);
        refreshList();
        if (!entries.isEmpty()) {
            int newIdx = Math.min(idx, entries.size() - 1);
            list.setSelectedIndex(newIdx);
            selectEntry(newIdx);
        } else {
            selectEntry(-1);
        }
    }

    private void selectEntry(int idx) {
        this.selectedIndex = idx;
        if (idx < 0 || idx >= entries.size()) {
            setDetailEnabled(false);
            nameField.setText("");
            keyField.setText("");
            descField.setText("");
            extraField.setText("");
            triggerTableModel.setRowCount(0);
            return;
        }
        RegistrableEntry entry = entries.get(idx);
        setDetailEnabled(true);
        nameField.setText(entry.getName());
        keyField.setText(entry.getKey());
        descField.setText(entry.getDescription());
        extraField.setText(entry.getExtraInfo());
        refreshTriggerTable();
    }

    private void addTrigger() {
        if (selectedIndex < 0) return;
        RegistrableEntry entry = entries.get(selectedIndex);
        Trigger t = new Trigger();
        // 按类型给默认时机
        Trigger.When[] avail = entry.availableWhens();
        if (avail.length > 0) t.setTriggerWhen(avail[0]);
        t.setDescription(entry.getType().label + "· " + t.getTriggerWhen().label);
        entry.getTriggers().add(t);
        refreshTriggerTable();
        // 立即编辑
        editTrigger(entry.getTriggers().size() - 1);
    }

    private void editTrigger(int triggerIdx) {
        if (selectedIndex < 0) return;
        Trigger t = entries.get(selectedIndex).getTriggers().get(triggerIdx);
        RegistrableEntry entry = entries.get(selectedIndex);
        new TriggerEditorDialog((Frame) SwingUtilities.getWindowAncestor(this), entry, t, () -> refreshTriggerTable()).setVisible(true);
    }

    private void refreshList() {
        listModel.clear();
        for (int i = 0; i < entries.size(); i++) {
            RegistrableEntry e = entries.get(i);
            String tag = e.isEnabled() ? "●" : "○";
            String line = tag + "  " + (e.getName().isEmpty() ? "(未命名)" : e.getName())
                    + "    [" + (e.getKey().isEmpty() ? "无key" : e.getKey()) + "]"
                    + "    " + e.getTriggers().size() + "个触发器";
            listModel.addElement(line);
        }
    }

    private void refreshTriggerTable() {
        if (triggerTableModel == null) return;
        triggerTableModel.setRowCount(0);
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return;
        for (Trigger t : entries.get(selectedIndex).getTriggers()) {
            Object[] row = {
                    t.isEnabled() ? "☑" : "☐",
                    t.getTriggerWhen().label,
                    t.getAction().label,
                    (t.getAction() == Trigger.Action.RUN_JAVA ? "[Java代码]" : t.getActionParam()),
                    t.getDescription()
            };
            triggerTableModel.addRow(row);
        }
    }

    private void setDetailEnabled(boolean enabled) {
        for (JTextComponent c : new JTextComponent[]{nameField, keyField, descField, extraField}) {
            c.setEnabled(enabled);
            c.setForeground(enabled ? Theme.FG_TEXT : new Color(180, 180, 190));
        }
    }

    /** 空实现的 DocumentListener 辅助类 */
    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable onChange;
        SimpleDocListener(Runnable r) { this.onChange = r; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
    }
}
