package com.cortex.command;

import java.util.List;

/**
 * /team 命令对 Team 管理能力的窄接口（T27/F59-F62）：command 包不依赖 team 包，
 * 由 tui 适配 {@code TeamManager} 实现；未启用返回 null。
 */
public interface TeamAccessor {

    /** 团队摘要行（/team list）。 */
    List<String> list();

    /** 团队详情（/team info），多行。 */
    List<String> info(String name);

    /** 删除团队。 */
    void delete(String name, boolean force) throws Exception;

    /** 终止队员（backend.kill + removeMember）。 */
    void kill(String teamName, String member) throws Exception;

    /** 按队员名反查其所在团队（/team kill 无团队名时用）。 */
    String teamOfMember(String member);
}
