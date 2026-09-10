package com.cortex.team;

/** 团队不存在（F7）。 */
public class TeamNotFoundException extends TeamException {

    public TeamNotFoundException(String name) {
        super("未找到团队: " + name);
    }
}
