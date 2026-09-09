package com.cortex.memory;

/**
 * 记忆更新请求的系统提示模板（F38/F39）：要求输出结构化 JSON 数组，不调用工具。
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** 记忆更新系统提示（中文，固定文案）。 */
    public static String system() {
        return """
                你是 Cortex 的记忆管理助手。根据最近的对话，提取值得长期记住的信息，\
                输出一个 JSON 数组，每个元素是一个操作对象，字段为：
                - action: create | update | delete
                - level: project | user
                - type: user_preference | correction_feedback | project_knowledge | reference_material
                - title: 简短标题
                - slug: 全小写下划线短名（create 用）
                - filename: 已有文件名（update/delete 用）
                - content: 笔记正文（create/update 用）

                如果无需更新，返回 []。只输出 JSON，不要输出其他文字。""";
    }
}
