package com.cortex.tui;

/**
 * spinner 动词表：等待时随机/轮换显示，营造“正在思考”的观感。
 */
public final class SpinnerVerbs {

    private static final String[] VERBS = {
            "Imagining", "Thinking", "Processing", "Reasoning", "Reflecting"
    };

    private SpinnerVerbs() {}

    /** 根据时间戳取一个稳定的动词。 */
    public static String pick(long seed) {
        long index = (seed / 1000) % VERBS.length;
        return VERBS[(int) index];
    }

    /** spinner 帧字符（braille 风格），用于流式期间的动画。 */
    public static char[] frames() {
        return new char[]{'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
    }

    public static char frameAt(long tick) {
        char[] f = frames();
        int i = (int) (tick % f.length);
        return f[i];
    }
}
