package com.cortex.team;

/** 团队仍有活跃成员，拒绝删除（F7/AC4）。 */
public class TeamHasActiveMembersException extends TeamException {

    public TeamHasActiveMembersException(String name) {
        super("团队 " + name + " 仍有活跃成员，拒绝删除（可加 force 强制删除）");
    }
}
