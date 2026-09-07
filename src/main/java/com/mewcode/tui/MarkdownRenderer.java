package com.mewcode.tui;

import com.github.ajalt.mordant.markdown.Markdown;
import com.github.ajalt.mordant.rendering.AnsiLevel;
import com.github.ajalt.mordant.rendering.Theme;
import com.github.ajalt.mordant.terminal.Terminal;
import com.github.ajalt.mordant.terminal.TerminalRecorder;

/**
 * 用 Mordant 将 markdown 渲染为带 ANSI 富文本（代码块、列表、强调等）的纯字符串，
 * 供定型后的助手回复展示。宽度按终端列数自适应，避免窄屏错版。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    /** 渲染 markdown，返回可直接输出的 ANSI 字符串（含颜色转义）。 */
    public static String render(String markdown, int width) {
        int w = Math.max(width - 2, 20);
        TerminalRecorder recorder = new TerminalRecorder(AnsiLevel.TRUECOLOR, w, 24, false, false, false, false);
        Terminal terminal = new Terminal(AnsiLevel.TRUECOLOR, Theme.Companion.getDefault(), w, 24, null, null, false, 0, false, recorder);
        terminal.print(new Markdown(markdown, false, null), false);
        return recorder.stdout();
    }
}
