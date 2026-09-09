package com.cortex.session;

import com.cortex.llm.ToolCall;
import com.cortex.llm.ToolResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JSONL 存档中的一行。普通消息填充 role/content/tool_calls/tool_results/ts（可选 model 仅首条携带）；
 * 压缩标记行仅 type=compact + ts。@JsonInclude NON_NULL 使 null 字段不落盘。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entry(
        @JsonProperty("type") String type,
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_results") List<ToolResult> toolResults,
        @JsonProperty("ts") long ts,
        @JsonProperty("model") String model) {

    /** 压缩标记行构造器。 */
    public static Entry compactMarker(long ts) {
        return new Entry("compact", null, null, null, null, ts, null);
    }
}
