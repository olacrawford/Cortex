package com.cortex.team;

/** Team 模块异常基类（RuntimeException：工具层统一转 error 结果）。 */
public class TeamException extends RuntimeException {

    public TeamException(String message) {
        super(message);
    }
}
