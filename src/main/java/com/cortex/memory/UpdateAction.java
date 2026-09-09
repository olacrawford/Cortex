package com.cortex.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆更新请求返回的一个操作（F39）：create / update / delete，level 为 project / user。
 */
public record UpdateAction(
        @JsonProperty("action") String action,
        @JsonProperty("level") String level,
        @JsonProperty("type") String type,
        @JsonProperty("title") String title,
        @JsonProperty("slug") String slug,
        @JsonProperty("filename") String filename,
        @JsonProperty("content") String content) {}
