package com.mewcode.tui;

import com.mewcode.tui.tea.ANSI256Color;
import com.mewcode.tui.tea.Style;

/**
 * 全局 UI 样式常量：banner、对话角色前缀、spinner、状态栏、错误等。
 */
public final class Styles {

    private Styles() {}

    public static final Style BANNER =
            Style.none().fg(ANSI256Color.BRIGHT_CYAN).bold();

    public static final Style MUTED =
            Style.none().fg(ANSI256Color.BRIGHT_BLACK);

    public static final Style USER_PREFIX =
            Style.none().fg(ANSI256Color.BRIGHT_GREEN).bold();

    public static final Style ASSISTANT_PREFIX =
            Style.none().fg(ANSI256Color.BRIGHT_BLUE).bold();

    public static final Style SPINNER =
            Style.none().fg(ANSI256Color.BRIGHT_MAGENTA);

    public static final Style ERROR =
            Style.none().fg(ANSI256Color.BRIGHT_RED);

    public static final Style INPUT_PROMPT =
            Style.none().fg(ANSI256Color.BRIGHT_CYAN).bold();

    public static final Style STATUS_PROVIDER =
            Style.none().fg(ANSI256Color.BRIGHT_MAGENTA);

    public static final Style STATUS_MODEL =
            Style.none().fg(ANSI256Color.BRIGHT_BLACK);

    public static final Style SELECT_ACTIVE =
            Style.none().fg(ANSI256Color.BRIGHT_GREEN).bold();

    public static final Style SELECT_IDLE =
            Style.none().fg(ANSI256Color.BRIGHT_BLACK);
}
