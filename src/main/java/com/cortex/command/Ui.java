package com.cortex.command;

import com.cortex.permission.Mode;

import java.util.List;

/**
 * 命令处理函数操作 TUI 的唯一通道（F33/F34）：handler 不持有具体 TUI 类型，
 * 只通过本抽象接口输出消息、查询状态、触发界面动作。
 * 实现方需对未就绪字段做防御（降级合约）：
 * provider 为 null → modelName 返回空串；writer 为 null → sessionPath 返回空串；
 * memoryManager 为 null → memoryFiles 返回空清单；agent 为 null → forceCompact 以 error 兜底。
 */
public interface Ui {
    // ─── 输出 ───
    /** 向用户输出一条系统 notice。 */
    void println(String msg);

    /** 向用户输出一条错误提示。 */
    void error(String msg);

    // ─── 模式 ───
    Mode mode();

    void setMode(Mode m);

    // ─── 对话注入（KindPrompt 命令用）───
    /**
     * 追加一条 user 消息并立即触发 LLM 回合。
     * displayLabel 在 scrollback 中显示；presetPrompt 是实际写入对话历史/会话存档的文本（N3）。
     */
    void injectAndSend(String displayLabel, String presetPrompt);

    // ─── 只读查询（/status /memory /session 用）───
    long usageIn();

    long usageOut();

    String modelName();

    String cwd();

    int toolCount();

    /** 项目层 + 用户层已加载的记忆文件名清单（只列文件名，F20）。 */
    List<String> memoryFiles();

    String sessionPath();

    String sessionId();

    /** 已安装技能名清单（skillCatalog 未加载时返回空清单，阶段10）。 */
    List<String> skillNames();

    /** 已加载 hook 行（按 event 分组、每条一条），阶段11 /hooks 用。 */
    List<String> hookLines();

    /** hook 加载来源文件清单。 */
    List<String> hookSources();

    /** Worktree 管理能力（阶段13 /worktree 用）；未启用（非 git 仓库等）返回 null。 */
    WorktreeAccessor worktreeAccessor();

    /** Team 管理能力（阶段14 /team 用）；未装配返回 null。 */
    TeamAccessor teamAccessor();

    // ─── 影响界面动作 ───
    void quit();

    void forceCompact();

    void openResumeMenu();

    void clearAndNewSession();

    // ─── 状态机查询 ───
    /** 当前是否空闲（非流式、非列表态）。UI/PROMPT 命令仅在 idle 可执行（N3a）。 */
    boolean idle();

    /** 吞掉所有调用、查询返回零值的测试桩。 */
    final class NopUi implements Ui {
        public static final NopUi INSTANCE = new NopUi();

        private NopUi() {}

        @Override
        public void println(String msg) {}

        @Override
        public void error(String msg) {}

        @Override
        public Mode mode() {
            return Mode.DEFAULT;
        }

        @Override
        public void setMode(Mode m) {}

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {}

        @Override
        public long usageIn() {
            return 0;
        }

        @Override
        public long usageOut() {
            return 0;
        }

        @Override
        public String modelName() {
            return "";
        }

        @Override
        public String cwd() {
            return "";
        }

        @Override
        public int toolCount() {
            return 0;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of();
        }

        @Override
        public String sessionPath() {
            return "";
        }

        @Override
        public String sessionId() {
            return "";
        }

        @Override
        public List<String> skillNames() {
            return List.of();
        }

        @Override
        public List<String> hookLines() {
            return List.of();
        }

        @Override
        public List<String> hookSources() {
            return List.of();
        }

        @Override
        public WorktreeAccessor worktreeAccessor() {
            return null;
        }

        @Override
        public TeamAccessor teamAccessor() {
            return null;
        }

        @Override
        public void quit() {}

        @Override
        public void forceCompact() {}

        @Override
        public void openResumeMenu() {}

        @Override
        public void clearAndNewSession() {}

        @Override
        public boolean idle() {
            return true;
        }
    }
}
