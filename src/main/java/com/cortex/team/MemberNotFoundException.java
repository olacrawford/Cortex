package com.cortex.team;

/** 队员不存在。 */
public class MemberNotFoundException extends TeamException {

    public MemberNotFoundException(String team, String member) {
        super("团队 " + team + " 中不存在成员: " + member);
    }
}
