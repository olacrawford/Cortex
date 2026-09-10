package com.cortex.team;

import com.cortex.task.Manager;

import java.io.IOException;

/**
 * 后端工厂（T10）：按类型分发。cortexJar 供 Pane 后端拼子进程命令；
 * in-process 复用 Lead 的 TaskManager。
 */
public final class BackendFactory {

    private final String cortexJar;
    private final Manager taskManager;

    public BackendFactory(String cortexJar, Manager taskManager) {
        this.cortexJar = cortexJar;
        this.taskManager = taskManager;
    }

    public Backend create(BackendType type) throws IOException {
        return switch (type) {
            case TMUX -> new TmuxBackend(cortexJar);
            case ITERM2 -> new Iterm2Backend(cortexJar);
            case IN_PROCESS -> new InProcessBackend(taskManager);
        };
    }
}
