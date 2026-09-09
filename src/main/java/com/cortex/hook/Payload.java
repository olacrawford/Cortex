package com.cortex.hook;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 事件 payload（F10）：通用字段（event/session_id/cwd/mode）+ 事件特化字段。
 * JSON 序列化 key 按字典序（N6），方便用户脚本直接 grep；条件求值经 {@link #getByPath}
 * 支持 {@code .} 分隔的嵌套字段访问（F13），路径不存在按空串处理。
 */
public final class Payload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> data;

    public Payload(Map<String, Object> data) {
        this.data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 按字段路径取值并字符串化：{@code tool_input.path} 递归下钻；缺失/不可达返回空串（F13）。 */
    public String getByPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Object cur = data;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return "";
            }
            cur = m.get(seg);
            if (cur == null) {
                return "";
            }
        }
        return stringify(cur);
    }

    /** 顶层字段访问（模板渲染 / 求值内部用）。 */
    Object raw(String key) {
        return data.get(key);
    }

    Map<String, Object> asMap() {
        return data;
    }

    /** 稳定 JSON：key 按字典序（N6）。 */
    public String toSortedJson() {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(data));
        } catch (Exception e) {
            // TreeMap<String,Object> + 基础值类型不会失败；防御兜底
            return "{}";
        }
    }

    private static String stringify(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Map<?, ?> m) {
            try {
                return MAPPER.writeValueAsString(new TreeMap<>(castMap(m)));
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
