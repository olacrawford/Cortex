package com.cortex.prompt;

/**
 * 补充消息构造（F6）：带 `<system-reminder>` 标签的运行中注入指令。
 * 此类消息每轮动态构造、不写入持久历史——不污染缓存、不破坏角色交替（N3），
 * 标签语义让模型理解这是系统补充上下文而非用户提问。
 */
public final class Reminder {

    private Reminder() {}

    /** /do 注入的用户消息：指示模型按上文已确认的计划开始执行。 */
    public static final String EXECUTE_DIRECTIVE = "请按上面的计划开始执行。";

    /** 规划模式完整提醒（首轮与间隔轮注入）。 */
    public static final String PLAN_REMINDER_FULL =
            "You are currently in PLAN MODE. You may use ONLY the read-only tools "
                    + "(read_file, glob, grep) to investigate the codebase. You must NOT write files, "
                    + "edit files, or run shell commands. Produce a clear, step-by-step plan for the task, "
                    + "then stop and wait for the user to approve it with /do before doing any work.";

    /** 规划模式精简提醒（其余轮次注入）。 */
    public static final String PLAN_REMINDER_CONCISE =
            "Plan mode is still active: use read-only tools only and keep producing your "
                    + "step-by-step plan. Do not modify anything; wait for /do.";

    /** 用标签包裹补充指令正文。 */
    public static String systemReminder(String body) {
        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    /** 本轮规划模式提醒：full=完整版，否则精简版。 */
    public static String plan(boolean full) {
        return systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE);
    }
}
