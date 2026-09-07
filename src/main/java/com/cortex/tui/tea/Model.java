package com.cortex.tui.tea;

/**
 * Bubble Tea 风格的 Model 接口。
 */
public interface Model {

    /** 初始化，返回首个命令。 */
    Command init();

    /** 处理一条消息，返回新的模型与命令。 */
    UpdateResult<? extends Model> update(Message msg);

    /** 渲染当前视图（多行字符串）。 */
    String view();

    /** 退出时输出的干净历史（默认空）。 */
    default String dumpHistory() {
        return "";
    }
}
