package com.cortex.team;

/** in-process 队员尝试再 spawn 队员（F25-2/AC8）——禁止嵌套派队。 */
public class InProcessTeammateNoSpawnException extends TeamException {

    public InProcessTeammateNoSpawnException(String member) {
        super("in-process 队员 " + member + " 不能再 spawn 队员（只有 Lead 可以往团队加人）");
    }
}
