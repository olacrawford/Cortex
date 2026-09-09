package com.cortex.tui;

import com.cortex.command.Command;
import com.cortex.command.CommandRegistry;

import java.util.List;

/**
 * 斜杠命令自动补全菜单（F24~F32）：输入首字符为 "/" 时激活，按命令名前缀过滤候选；
 * ↑/↓ 切高亮，回车/Tab 执行高亮命令，ESC 关闭；候选超过 {@link #MAX_ROWS} 行时窗口内滚动。
 * 仅按主名前缀匹配，不参与别名与描述匹配（F25）。
 */
public final class CompletionMenu {

    /** 菜单最大可见行数（N5）。 */
    static final int MAX_ROWS = 8;

    private List<Command> items = List.of();
    private int cursor;
    private boolean active;

    /** 根据当前输入刷新候选；输入为空、含换行或首字符非 "/" 时关闭菜单（F26/F32a）。 */
    public void update(String input, CommandRegistry reg) {
        String text = input == null ? "" : input;
        if (text.isEmpty() || text.charAt(0) != '/' || text.indexOf('\n') >= 0) {
            hide();
            return;
        }
        active = true;
        items = reg.prefixMatch(text);
        clampCursor();
    }

    public void moveUp() {
        if (!items.isEmpty()) {
            cursor = Math.max(0, cursor - 1);
        }
    }

    public void moveDown() {
        if (!items.isEmpty()) {
            cursor = Math.min(items.size() - 1, cursor + 1);
        }
    }

    /** 当前高亮命令；零匹配时返回 null（F32b）。 */
    public Command selected() {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(Math.min(cursor, items.size() - 1));
    }

    public void hide() {
        active = false;
        items = List.of();
        cursor = 0;
    }

    public boolean active() {
        return active;
    }

    /** 渲染菜单行（紧贴输入框下方、状态栏上方，N6）：候选 "命令 描述" 两列对齐，高亮当前项。 */
    public List<String> renderLines() {
        if (!active) {
            return List.of();
        }
        if (items.isEmpty()) {
            return List.of(Styles.MUTED.apply("  （无匹配命令）"));
        }
        int width = 0;
        for (Command c : items) {
            width = Math.max(width, c.name().length() + 2);
        }
        // 可见窗口：候选超过 MAX_ROWS 时跟随光标滚动（N5）
        int from = Math.max(0, Math.min(cursor - MAX_ROWS + 1, items.size() - MAX_ROWS));
        int to = Math.min(items.size(), from + MAX_ROWS);
        List<String> lines = new java.util.ArrayList<>();
        if (from > 0) {
            lines.add(Styles.MUTED.apply("  ↑ " + from + " more"));
        }
        for (int i = from; i < to; i++) {
            Command c = items.get(i);
            String label = String.format("%-" + width + "s", "/" + c.name()) + Styles.MUTED.apply(c.description());
            lines.add(i == cursor
                    ? Styles.SELECT_ACTIVE.apply("▸ " + label)
                    : Styles.MUTED.apply("  " + label));
        }
        if (to < items.size()) {
            lines.add(Styles.MUTED.apply("  ↓ " + (items.size() - to) + " more"));
        }
        return lines;
    }

    private void clampCursor() {
        if (cursor >= items.size()) {
            cursor = Math.max(0, items.size() - 1);
        }
    }
}
