package com.cortex.hook;

import java.util.List;

/**
 * 条件求值（F11-F15）：匹配器在加载期一次构造、运行期复用；
 * 求值在事件 emit 时实时进行，字段路径缺失按空串处理（F13）。
 */
final class ConditionEvaluator {

    private ConditionEvaluator() {}

    /** 条件求值：null 条件 = 无条件触发（F11）。 */
    static boolean evaluate(Condition c, Payload p) {
        if (c == null) {
            return true;
        }
        List<AtomCondition> atoms = c.atoms();
        if (atoms.isEmpty()) {
            return true;
        }
        boolean all = c.mode() == CombineMode.ALL_OF;
        for (AtomCondition atom : atoms) {
            String value = p.getByPath(atom.field());
            boolean hit = atom.matcher().match(value);
            if (all && !hit) {
                return false;
            }
            if (!all && hit) {
                return true;
            }
        }
        return all;
    }
}
