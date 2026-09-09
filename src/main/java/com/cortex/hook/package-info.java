/**
 * Hook 生命周期挂钩系统（阶段11）：11 个生命周期事件 + 条件表达式 + 四类动作。
 * 规则由 {@code hooks.yaml} 声明式配置（项目级 + 用户级叠加），启动期一次性加载；
 * 事件 emit 时同步驱动引擎，拦截类事件（PreToolUse / UserPromptSubmit）可阻断主流程，
 * 其余事件只做副作用（shell / prompt 注入 / http / subagent 占位）。
 */
package com.cortex.hook;
