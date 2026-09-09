package com.cortex.hook;

import com.cortex.permission.Matcher;

import java.util.List;
import java.util.Map;

/** 条件表达式（F11）：null 表示无条件触发；atoms 非空且至少一条。 */
public record Condition(CombineMode mode, List<AtomCondition> atoms) {

    public Condition {
        atoms = List.copyOf(atoms);
    }

    public static Condition allOf(List<AtomCondition> atoms) {
        return new Condition(CombineMode.ALL_OF, atoms);
    }

    public static Condition anyOf(List<AtomCondition> atoms) {
        return new Condition(CombineMode.ANY_OF, atoms);
    }

    /** 从 yaml map 顶层探测组合键；两个同时出现视为非法（返回 null 由调用方报错）。 */
    static Condition fromMap(Map<String, Object> map,
                             java.util.function.Function<Object, Matcher> matcherCompiler) {
        Object all = map.get("all_of");
        Object any = map.get("any_of");
        if (all != null && any != null) {
            return null;
        }
        Object list = all != null ? all : any;
        if (!(list instanceof List<?> atoms) || atoms.isEmpty()) {
            return null;
        }
        CombineMode mode = all != null ? CombineMode.ALL_OF : CombineMode.ANY_OF;
        List<AtomCondition> compiled = new java.util.ArrayList<>();
        for (Object o : atoms) {
            if (!(o instanceof Map<?, ?> atom)) {
                return null;
            }
            String field = String.valueOf(atom.get("field"));
            Matcher m = matcherCompiler.apply(atom.get("match"));
            if (field.isBlank() || m == null) {
                return null;
            }
            compiled.add(new AtomCondition(field.strip(), m));
        }
        return new Condition(mode, compiled);
    }
}
