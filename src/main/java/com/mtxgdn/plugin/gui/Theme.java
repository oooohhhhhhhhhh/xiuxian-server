package com.mtxgdn.plugin.gui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主题系统 —— 现代化简洁风格。
 * <p>
 * 配色：浅灰背景 + 深灰文字（高对比度），
 * 蓝色为主色，琥珀色为主按钮强调色。
 */
public final class Theme {

    // ==================== 颜色定义 ====================

    public static final Color BG_PRIMARY   = new Color(246, 248, 252);
    public static final Color BG_SECONDARY = new Color(255, 255, 255);
    public static final Color BG_INPUT     = new Color(252, 252, 254);
    public static final Color BG_CODE    = new Color(28, 32, 40);

    public static final Color FG_TEXT      = new Color(32, 34, 42);
    public static final Color FG_MUTED     = new Color(118, 126, 140);
    public static final Color FG_HEADLINE  = new Color(20, 24, 34);

    public static final Color ACCENT_BLUE  = new Color(56, 106, 224);
    public static final Color ACCENT_AMBER = new Color(212, 140, 23);
    public static final Color ACCENT_GREEN = new Color(40, 140, 90);
    public static final Color ACCENT_RED   = new Color(196, 60, 60);

    public static final Color BORDER_COLOR = new Color(218, 222, 230);
    public static final Color DIVIDER      = new Color(228, 232, 240);

    // ==================== 字体（中文字体检测，避免出现乱码/方框）====================

    /** 缓存检测到的中文字体名——无需每次都扫描 */
    private static Font cachedCjkFont;
    @SuppressWarnings("unused")
    private static String cachedCjkFamily;

    /**
     * 最可靠的中文字体检测：
     * 1) 先从 GraphicsEnvironment 列出所有可用字体
     * 2) 对每个候选字体实际测试 canDisplayUpTo("中文测试")
     * 3) 若返回 -1 表示全部中文字符都能正常显示，才被选中
     * 4) 最后兜底使用系统 Dialog 字体
     */
    private static Font detectCjkFont() {
        if (cachedCjkFont != null) return cachedCjkFont;
        String testText = "中文字体测试测试测试测试";
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            // 第 1 步：优先检测知名字体（按名称，速度最快
            String[] preferredNames = {
                    "Microsoft YaHei UI",
                    "Microsoft YaHei",
                    "微软雅黑",
                    "微软雅黑 UI",
                    "PingFang SC",
                    "Source Han Sans CN",
                    "Source Han Sans SC",
                    "Noto Sans CJK SC",
                    "Noto Sans SC",
                    "SimSun",
                    "SimHei",
                    "宋体",
                    "黑体"
            };
            for (String name : preferredNames) {
                Font f = new Font(name, Font.PLAIN, 12);
                if (f != null && f.canDisplayUpTo(testText) == -1) {
                    cachedCjkFont = f;
                    cachedCjkFamily = f.getFamily();
                    return f;
                }
            }
            // 第 2 步：遍历系统所有字体，找第一个能完整显示中文的字体
            Font[] allFonts = ge.getAllFonts();
            for (Font f : allFonts) {
                if (f.canDisplayUpTo(testText) == -1) {
                    cachedCjkFont = f;
                    cachedCjkFamily = f.getFamily();
                    return f;
                }
            }
        } catch (Throwable ignore) { }
        // 最后兜底
        cachedCjkFont = new Font(Font.DIALOG, Font.PLAIN, 12);
        cachedCjkFamily = cachedCjkFont.getFamily();
        return cachedCjkFont;
    }

    private static Font regularBase() {
        return detectCjkFont().deriveFont(Font.PLAIN, 12);
    }

    public static Font fontRegular(float pt) { return regularBase().deriveFont(pt); }
    public static Font fontBold(float pt)    { return regularBase().deriveFont(Font.BOLD, pt); }

    public static Font fontMono(float pt) {
        String testText = "中文测试";
        try {
            String[] preferred = { "Consolas", "JetBrains Mono", "Menlo", "Monaco", "Courier New" };
            for (String name : preferred) {
                Font f = new Font(name, Font.PLAIN, Math.round(pt));
                if (f.canDisplayUpTo(testText) == -1) {
                    return f;
                }
            }
            // 等宽字体没有能显示中文的，回退到能显示中文的字体
            return detectCjkFont().deriveFont(Font.PLAIN, pt);
        } catch (Throwable ignore) { }
        return detectCjkFont().deriveFont(Font.PLAIN, pt);
    }

    // ==================== 边框 ====================

    public static Border cardBorder(String title) {
        Border outer = BorderFactory.createLineBorder(BORDER_COLOR, 1, true);
        Border inner = BorderFactory.createEmptyBorder(14, 16, 14, 16);
        Border compound = BorderFactory.createCompoundBorder(outer, inner);
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(compound, "  " + title + "  ");
        tb.setTitleFont(fontBold(13f));
        tb.setTitleColor(FG_HEADLINE);
        tb.setTitleJustification(javax.swing.border.TitledBorder.LEFT);
        tb.setTitlePosition(javax.swing.border.TitledBorder.TOP);
        return tb;
    }

    public static Border cardBorder() {
        Border outer = BorderFactory.createLineBorder(BORDER_COLOR, 1, true);
        Border inner = BorderFactory.createEmptyBorder(14, 16, 14, 16);
        return BorderFactory.createCompoundBorder(outer, inner);
    }

    public static Border paddingBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    // ==================== 文本组件 ====================

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(fontRegular(12.5f));
        l.setForeground(FG_TEXT);
        return l;
    }

    public static JLabel titleLabel(String text, float pt) {
        JLabel l = new JLabel(text);
        l.setFont(fontBold(pt));
        l.setForeground(FG_HEADLINE);
        return l;
    }

    public static JLabel hintLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(fontRegular(11.5f));
        l.setForeground(FG_MUTED);
        return l;
    }

    // ==================== 输入框 / 组件 ====================

    public static void styleInput(JTextComponent c) {
        c.setFont(fontRegular(12.5f));
        c.setBackground(BG_INPUT);
        c.setForeground(FG_TEXT);
        c.setCaretColor(FG_TEXT);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        c.setOpaque(true);
    }

    public static void styleComboBox(JComboBox<?> c) {
        c.setFont(fontRegular(12.5f));
        c.setBackground(BG_INPUT);
        c.setForeground(FG_TEXT);
        c.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        c.setOpaque(true);
    }

    public static void styleCheckBox(JCheckBox c) {
        c.setFont(fontRegular(12.5f));
        c.setBackground(BG_SECONDARY);
        c.setForeground(FG_TEXT);
        c.setFocusPainted(false);
    }

    // ==================== 按钮 ====================

    public static void styleButton(JButton b) {
        b.setFont(fontRegular(12.5f));
        b.setBackground(new Color(240, 242, 246));
        b.setForeground(FG_TEXT);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(new Color(230, 234, 242)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { b.setBackground(new Color(240, 242, 246)); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(new Color(218, 224, 236)); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){ b.setBackground(new Color(230, 234, 242)); }
        });
    }

    public static void stylePrimaryButton(JButton b) {
        b.setFont(fontBold(12.5f));
        b.setBackground(ACCENT_AMBER);
        b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 115, 18), 1, true),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(new Color(225, 155, 40)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { b.setBackground(ACCENT_AMBER); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(new Color(190, 125, 20)); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){ b.setBackground(new Color(225, 155, 40)); }
        });
    }

    public static void styleDangerButton(JButton b) {
        b.setFont(fontRegular(12.5f));
        b.setBackground(new Color(250, 235, 235));
        b.setForeground(ACCENT_RED);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230, 200, 200), 1, true),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(new Color(244, 220, 220)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { b.setBackground(new Color(250, 235, 235)); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(new Color(234, 204, 204)); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){ b.setBackground(new Color(244, 220, 220)); }
        });
    }

    // ==================== 表格 ====================

    public static void styleTable(JTable t) {
        t.setFont(fontRegular(12.5f));
        t.setForeground(FG_TEXT);
        t.setBackground(BG_INPUT);
        t.setGridColor(DIVIDER);
        t.setRowHeight(30);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setShowGrid(false);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setSelectionBackground(new Color(245, 230, 200));
        t.setSelectionForeground(FG_TEXT);
        t.setFocusable(false);

        JTableHeader h = t.getTableHeader();
        h.setFont(fontBold(12.5f));
        h.setBackground(new Color(240, 242, 246));
        h.setForeground(FG_HEADLINE);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        h.setReorderingAllowed(false);
        h.setResizingAllowed(true);

        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? BG_INPUT : new Color(248, 250, 254));
                }
                ((JComponent) c).setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return c;
            }
        });
    }

    public static void styleTabbedPane(JTabbedPane tabs) {
        tabs.setFont(fontBold(12.5f));
        tabs.setBackground(BG_PRIMARY);
        tabs.setForeground(FG_TEXT);
        tabs.setOpaque(true);
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
    }

    // ==================== Nimbus L&F 初始化 ====================

    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignore) {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Throwable ignore2) { }
        }

        FontUIResource regular = new FontUIResource(regularBase().deriveFont(12.5f));
        FontUIResource bold    = new FontUIResource(regularBase().deriveFont(Font.BOLD, 12.5f));
        Map<String, FontUIResource> fontMap = new LinkedHashMap<>();
        fontMap.put("Button.font", bold);
        fontMap.put("ToggleButton.font", regular);
        fontMap.put("RadioButton.font", regular);
        fontMap.put("CheckBox.font", regular);
        fontMap.put("ComboBox.font", regular);
        fontMap.put("Label.font", regular);
        fontMap.put("List.font", regular);
        fontMap.put("MenuBar.font", regular);
        fontMap.put("MenuItem.font", regular);
        fontMap.put("Menu.font", regular);
        fontMap.put("PopupMenu.font", regular);
        fontMap.put("OptionPane.font", regular);
        fontMap.put("Panel.font", regular);
        fontMap.put("ProgressBar.font", regular);
        fontMap.put("ScrollPane.font", regular);
        fontMap.put("Viewport.font", regular);
        fontMap.put("TabbedPane.font", bold);
        fontMap.put("Table.font", regular);
        fontMap.put("TableHeader.font", bold);
        fontMap.put("TextField.font", regular);
        fontMap.put("PasswordField.font", regular);
        fontMap.put("TextArea.font", regular);
        fontMap.put("TextPane.font", regular);
        fontMap.put("EditorPane.font", regular);
        fontMap.put("TitledBorder.font", bold);
        fontMap.put("ToolBar.font", regular);
        fontMap.put("ToolTip.font", regular);
        fontMap.put("Tree.font", regular);
        for (Map.Entry<String, FontUIResource> e : fontMap.entrySet()) {
            UIManager.put(e.getKey(), e.getValue());
        }

        UIManager.put("TextField.background",   new ColorUIResource(BG_INPUT));
        UIManager.put("TextField.foreground",   new ColorUIResource(FG_TEXT));
        UIManager.put("TextField.caretForeground", new ColorUIResource(ACCENT_BLUE));
        UIManager.put("TextArea.background",    new ColorUIResource(BG_INPUT));
        UIManager.put("TextArea.foreground",    new ColorUIResource(FG_TEXT));
        UIManager.put("Panel.background",       new ColorUIResource(BG_PRIMARY));
        UIManager.put("OptionPane.background",  new ColorUIResource(BG_PRIMARY));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(FG_TEXT));
        UIManager.put("Table.background",       new ColorUIResource(BG_INPUT));
        UIManager.put("Table.foreground",       new ColorUIResource(FG_TEXT));
        UIManager.put("TableHeader.background", new ColorUIResource(new Color(240, 242, 246)));
        UIManager.put("TableHeader.foreground", new ColorUIResource(FG_HEADLINE));
        UIManager.put("ScrollBar.background",   new ColorUIResource(BG_PRIMARY));
        UIManager.put("Viewport.background",    new ColorUIResource(BG_PRIMARY));
        UIManager.put("ToolTip.background",     new ColorUIResource(BG_CODE));
        UIManager.put("ToolTip.foreground",     new ColorUIResource(new Color(220, 224, 232)));
    }
}
