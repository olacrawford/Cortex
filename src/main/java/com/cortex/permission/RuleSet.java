package com.cortex.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 单层规则集（F3/F4）：持有 allow/deny 两条列表；
 * 匹配时 <b>先 deny 再 allow</b>——同层内 deny 优先于 allow。
 */
public final class RuleSet {

    public static final RuleSet EMPTY = new RuleSet(List.of(), List.of());

    private final List<Rule> allow;
    private final List<Rule> deny;

    public RuleSet(List<Rule> allow, List<Rule> deny) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
    }

    /**
     * 在本层规则集内判定：deny 命中 → DENY；allow 命中 → ALLOW；都未命中 → empty（继续下一层）。
     */
    public Optional<Decision> match(String friendly, String target, boolean isCommand) {
        for (Rule r : deny) {
            if (r.tool().equals(friendly) && Rule.matchPattern(r.pattern(), target, isCommand)) {
                return Optional.of(Decision.DENY);
            }
        }
        for (Rule r : allow) {
            if (r.tool().equals(friendly) && Rule.matchPattern(r.pattern(), target, isCommand)) {
                return Optional.of(Decision.ALLOW);
            }
        }
        return Optional.empty();
    }

    /** 追加一条 allow 规则（人在回路「永久」用；已存在则不重复）。 */
    public RuleSet withAllow(Rule rule) {
        List<Rule> merged = new ArrayList<>(allow);
        if (!merged.contains(rule)) {
            merged.add(rule);
        }
        return new RuleSet(merged, deny);
    }

    public List<Rule> allow() {
        return allow;
    }

    public List<Rule> deny() {
        return deny;
    }
}
