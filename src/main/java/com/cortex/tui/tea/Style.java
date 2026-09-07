package com.cortex.tui.tea;

/**
 * 组合式 ANSI 样式：前景/背景 256 色 + 加粗/斜体/下划线。
 * 通过流式方法链构建，如 {@code Style.none().fg(ANSI256Color.BRIGHT_CYAN).bold()}。
 */
public class Style {

    private final ANSI256Color foreground;
    private final ANSI256Color background;
    private final boolean bold;
    private final boolean italic;
    private final boolean underline;

    private Style(ANSI256Color foreground, ANSI256Color background, boolean bold, boolean italic, boolean underline) {
        this.foreground = foreground;
        this.background = background;
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
    }

    /** 空样式（使用终端默认色）。 */
    public static Style none() {
        return new Style(ANSI256Color.DEFAULT_COLOR, ANSI256Color.DEFAULT_COLOR, false, false, false);
    }

    public Style fg(ANSI256Color color) {
        return new Style(color, background, bold, italic, underline);
    }

    public Style bg(ANSI256Color color) {
        return new Style(foreground, color, bold, italic, underline);
    }

    public Style bold() {
        return new Style(foreground, background, true, italic, underline);
    }

    public Style italic() {
        return new Style(foreground, background, bold, true, underline);
    }

    public Style underline() {
        return new Style(foreground, background, bold, italic, true);
    }

    /** 用本样式包裹文本，返回带 ANSI 转义的字符串。 */
    public String apply(String text) {
        StringBuilder attrs = new StringBuilder();
        if (bold) {
            attrs.append("1;");
        }
        if (italic) {
            attrs.append("3;");
        }
        if (underline) {
            attrs.append("4;");
        }
        if (foreground != null && foreground.getCode() >= 0) {
            attrs.append(foreground.toForegroundSequence()).append(";");
        }
        if (background != null && background.getCode() >= 0) {
            attrs.append(background.toBackgroundSequence()).append(";");
        }
        String sgr = attrs.toString();
        if (sgr.isEmpty()) {
            return text;
        }
        return "\u001b[" + sgr.substring(0, sgr.length() - 1) + "m" + text + "\u001b[0m";
    }
}
