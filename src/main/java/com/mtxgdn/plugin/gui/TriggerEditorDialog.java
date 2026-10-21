package com.mtxgdn.plugin.gui;

import javax.swing.*;
import java.awt.*;

/**
 * 触发器编辑对话框 —— 用于编辑单个触发器的详细信息。
 * <p>
 * 用户可配置：触发时机、动作类型、动作参数、自定义Java代码、描述、是否启用。
 */
public class TriggerEditorDialog extends JDialog {

    private final Trigger trigger;
    private final RegistrableEntry ownerEntry;
    private final Runnable onOk;

    private JComboBox<Trigger.When> whenCombo;
    private JComboBox<Trigger.Action> actionCombo;
    private JTextField paramField;
    private JTextArea codeArea;
    private JTextField descField;
    private JCheckBox enabledCheck;

    public TriggerEditorDialog(Frame owner, RegistrableEntry ownerEntry, Trigger trigger, Runnable onOk) {
        super(owner, "✎ 编辑触发器", true);
        this.trigger = trigger;
        this.ownerEntry = ownerEntry;
        this.onOk = onOk;

        initUI();
        pack();
        setSize(Math.max(620, getWidth()), Math.max(540, getHeight()));
        setLocationRelativeTo(owner);
        setResizable(true);
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(Theme.BG_PRIMARY);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        // 顶部标题
        JLabel title = Theme.titleLabel("✎ 触发器 · 所属" + ownerEntry.getType().label
                + "【" + (ownerEntry.getName().isEmpty() ? ownerEntry.getKey() : ownerEntry.getName()) + "】", 15f);
        title.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 0));
        root.add(title, BorderLayout.NORTH);

        // 中部表单
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.BG_SECONDARY);
        form.setBorder(Theme.cardBorder("配置"));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(5, 8, 5, 8);
        g.weightx = 0; g.weighty = 0;

        int row = 0;

        // 启用
        g.gridx = 0; g.gridy = row;
        form.add(Theme.label("启用："), g);
        g.gridx = 1; g.weightx = 1;
        enabledCheck = new JCheckBox("启用此触发器", trigger.isEnabled());
        enabledCheck.setFont(Theme.fontRegular(12.5f));
        enabledCheck.setBackground(Theme.BG_SECONDARY);
        enabledCheck.setForeground(Theme.FG_TEXT);
        form.add(enabledCheck, g);
        row++;

        // 触发时机
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(Theme.label("触发时机："), g);
        g.gridx = 1; g.weightx = 1;
        Trigger.When[] avail = ownerEntry.availableWhens();
        whenCombo = new JComboBox<>(avail);
        Theme.styleComboBox(whenCombo);
        whenCombo.setEnabled(avail.length > 0);
        // 选当前值
        for (int i = 0; i < avail.length; i++) if (avail[i] == trigger.getTriggerWhen()) whenCombo.setSelectedIndex(i);
        form.add(whenCombo, g);
        row++;

        // 动作类型
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(Theme.label("动作："), g);
        g.gridx = 1; g.weightx = 1;
        Trigger.Action[] actions = Trigger.Action.values();
        actionCombo = new JComboBox<>(actions);
        actionCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Trigger.Action) lbl.setText(((Trigger.Action) value).label);
                return lbl;
            }
        });
        actionCombo.setSelectedItem(trigger.getAction());
        Theme.styleComboBox(actionCombo);
        actionCombo.addActionListener(e -> updateParamFields());
        form.add(actionCombo, g);
        row++;

        // 参数
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        JLabel paramLabel = Theme.label("参数：");
        form.add(paramLabel, g);
        g.gridx = 1; g.weightx = 1;
        paramField = new JTextField(trigger.getActionParam(), 30);
        Theme.styleInput(paramField);
        form.add(paramField, g);
        row++;

        // 描述
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        form.add(Theme.label("描述："), g);
        g.gridx = 1; g.weightx = 1;
        descField = new JTextField(trigger.getDescription(), 30);
        Theme.styleInput(descField);
        form.add(descField, g);
        row++;

        // Java 代码
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.anchor = GridBagConstraints.NORTHWEST;
        JLabel codeLabel = Theme.label("Java代码：");
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        form.add(codeLabel, g);
        g.gridx = 1; g.weightx = 1; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        codeArea = new JTextArea(trigger.getJavaCode(), 10, 40);
        codeArea.setFont(Theme.fontMono(12f));
        codeArea.setBackground(Theme.BG_CODE);
        codeArea.setForeground(new Color(220, 224, 232));
        codeArea.setCaretColor(Color.WHITE);
        codeArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        codeArea.setLineWrap(false);
        JScrollPane codeScroll = new JScrollPane(codeArea);
        codeScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_COLOR, 1, true));
        codeScroll.getViewport().setBackground(Theme.BG_CODE);
        codeScroll.getVerticalScrollBar().setUnitIncrement(16);
        codeScroll.getHorizontalScrollBar().setUnitIncrement(16);
        form.add(codeScroll, g);
        row++;

        // 代码区提示
        g.gridx = 0; g.gridy = row; g.gridwidth = 2; g.weightx = 1; g.weighty = 0; g.fill = GridBagConstraints.HORIZONTAL;
        JLabel codeHint = Theme.hintLabel("提示：当动作 =「执行代码」时，下方代码会被原样生成到插件源码。"
                + "\n可用变量：" + availableVariables(ownerEntry.getType()));
        form.add(codeHint, g);

        root.add(form, BorderLayout.CENTER);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.setBackground(Theme.BG_PRIMARY);
        JButton cancelBtn = new JButton("取消");
        Theme.styleButton(cancelBtn);
        cancelBtn.addActionListener(e -> dispose());

        JButton okBtn = new JButton("✔ 保存");
        Theme.stylePrimaryButton(okBtn);
        okBtn.addActionListener(e -> {
            applyChanges();
            if (onOk != null) onOk.run();
            dispose();
        });

        bottom.add(cancelBtn);
        bottom.add(okBtn);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        updateParamFields();
    }

    /** 生成代码时可用的上下文变量说明（与 CodeGenerator 实际生成的变量保持一致）。 */
    private static String availableVariables(RegistrableEntry.Type type) {
        switch (type) {
            case ITEM:
                return "playerId, playerService, itemService, context, msg（msg.append 的内容会显示给玩家）";
            case EVENT:
                return "player, playerId, playerService, itemService, random, result, log, context";
            case COMMAND:
                return "ctx, p, playerId, userId, context";
            case SECRET_REALM:
            default:
                return "（该类型暂不支持触发器）";
        }
    }

    private void updateParamFields() {
        Trigger.Action action = (Trigger.Action) actionCombo.getSelectedItem();
        if (action == null) return;
        String hint;
        switch (action) {
            case GIVE_SPIRIT_STONES: hint = "灵石数量（整数），例如 100"; break;
            case GIVE_ITEM:          hint = "物品 key，例如 demo_talisman"; break;
            case SEND_MESSAGE:       hint = "消息内容，例如 道友安好！"; break;
            case RUN_JAVA:           hint = "（Java 代码见下方代码区）"; break;
            case LOG_ONLY:           hint = "（无需参数，仅记录日志）"; break;
            default:                 hint = "";
        }
        paramField.setToolTipText(hint);
        paramField.setEnabled(action != Trigger.Action.RUN_JAVA && action != Trigger.Action.LOG_ONLY);
    }

    private void applyChanges() {
        trigger.setEnabled(enabledCheck.isSelected());
        Object w = whenCombo.getSelectedItem();
        if (w instanceof Trigger.When) trigger.setTriggerWhen((Trigger.When) w);
        Object a = actionCombo.getSelectedItem();
        if (a instanceof Trigger.Action) trigger.setAction((Trigger.Action) a);
        trigger.setActionParam(paramField.getText());
        trigger.setJavaCode(codeArea.getText());
        trigger.setDescription(descField.getText());
    }
}
