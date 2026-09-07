package com.mewcode.tui.tea;

/**
 * update 的返回结果：更新后的模型 + 需要执行的命令。
 */
public record UpdateResult<M extends Model>(M model, Command command) {
}
