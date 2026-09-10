package com.cortex.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** team 工具的参数读取小助手。 */
final class TaskArg {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskArg() {}

    static String stringArg(String argsJson, String key) {
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return node.has(key) && node.get(key).isTextual() ? node.get(key).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean boolArg(String argsJson, String key) {
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return node.has(key) && node.get(key).isBoolean() && node.get(key).asBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
