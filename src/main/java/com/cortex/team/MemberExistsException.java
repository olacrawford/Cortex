package com.cortex.team;

/** 队员重名（F8）。 */
public class MemberExistsException extends TeamException {

    public MemberExistsException(String team, String member) {
        super("团队 " + team + " 中已存在成员: " + member);
    }
}
