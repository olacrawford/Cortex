/**
 * Agent Team（阶段14）：Lead 与多个队员（Teammate）组成的长期小组。
 * 队员间通过共享任务列表（tasks.json）与邮箱（mailbox/&lt;agentId&gt;.json）直接协作；
 * 三种执行后端 in-process / tmux / iterm2；跨后端状态经 Team config.json（原子写 + 跨进程 reload）。
 */
package com.cortex.team;
