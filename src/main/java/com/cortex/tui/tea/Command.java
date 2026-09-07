package com.cortex.tui.tea;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * 命令：由 Model.update 返回、由 Program 解释执行。
 */
public sealed interface Command {

    /** 延时后向事件循环投递一条消息。 */
    record Tick(Duration delay, Function<Instant, Message> fn) implements Command {}

    /** 将一段文本写入终端 scrollback（清空当前 view 并重绘）。 */
    record Println(String text) implements Command {}

    /** 请求检测当前窗口尺寸。 */
    record CheckWindowSize() implements Command {}

    /** 退出主循环。 */
    record Quit() implements Command {}

    /** 批量执行若干命令。 */
    record Batch(List<Command> commands) implements Command {}

    static Command tick(Duration delay, Function<Instant, Message> fn) {
        return new Tick(delay, fn);
    }

    static Command println(String text) {
        return new Println(text);
    }

    static Command checkWindowSize() {
        return new CheckWindowSize();
    }

    static Command quit() {
        return new Quit();
    }

    static Command batch(Command... commands) {
        return new Batch(List.of(commands));
    }
}
