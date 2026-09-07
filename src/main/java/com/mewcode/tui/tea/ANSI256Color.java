package com.mewcode.tui.tea;

/**
 * ANSI 256 色（0-255）。code 为 -1 表示使用终端默认色。
 */
public class ANSI256Color {

    public static final int DEFAULT_CODE = -1;

    private final int code;

    public ANSI256Color(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public String toForegroundSequence() {
        return code >= 0 ? "38;5;" + code : "39";
    }

    public String toBackgroundSequence() {
        return code >= 0 ? "48;5;" + code : "49";
    }

    public static final ANSI256Color DEFAULT_COLOR = new ANSI256Color(DEFAULT_CODE);
    public static final ANSI256Color BLACK = new ANSI256Color(0);
    public static final ANSI256Color RED = new ANSI256Color(1);
    public static final ANSI256Color GREEN = new ANSI256Color(2);
    public static final ANSI256Color YELLOW = new ANSI256Color(3);
    public static final ANSI256Color BLUE = new ANSI256Color(4);
    public static final ANSI256Color MAGENTA = new ANSI256Color(5);
    public static final ANSI256Color CYAN = new ANSI256Color(6);
    public static final ANSI256Color WHITE = new ANSI256Color(7);
    public static final ANSI256Color BRIGHT_BLACK = new ANSI256Color(8);
    public static final ANSI256Color BRIGHT_RED = new ANSI256Color(9);
    public static final ANSI256Color BRIGHT_GREEN = new ANSI256Color(10);
    public static final ANSI256Color BRIGHT_YELLOW = new ANSI256Color(11);
    public static final ANSI256Color BRIGHT_BLUE = new ANSI256Color(12);
    public static final ANSI256Color BRIGHT_MAGENTA = new ANSI256Color(13);
    public static final ANSI256Color BRIGHT_CYAN = new ANSI256Color(14);
    public static final ANSI256Color BRIGHT_WHITE = new ANSI256Color(15);
}
