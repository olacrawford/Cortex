package com.cortex.task;

import com.cortex.agent.Agent;
import com.cortex.agent.AgentEvent;
import com.cortex.agent.ApprovalRequest;
import com.cortex.agent.SubAgentRun;
import com.cortex.agent.TaskManagerPort;
import com.cortex.conversation.ConversationManager;
import com.cortex.permission.Outcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 后台任务管理器（F14-F18）：launch 起虚拟线程跑 {@code runToCompletion}，
 * 跑完写终态并推送任务 ID 到 done 队列（TUI 消费后注入 &lt;task-notification&gt;，F19）。
 * 线程安全：任务表 ConcurrentHashMap，终态字段 volatile。
 * <p>
 * 异常隔离（N3）：runner 捕获 Throwable，任何崩溃转 status=FAILED 回灌错误，主程序不受影响。
 */
public final class Manager implements TaskManagerPort {

    /** done 队列的容量上限；真满时丢弃通知并 stderr 警告（漏一条不致命）。 */
    static final int DONE_BUFFER = 32;

    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    /** name → id 弱引用：同名后启动的覆盖前面的（F1）。 */
    private final Map<String, String> byName = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> doneQueue = new LinkedBlockingQueue<>(DONE_BUFFER);
    private final AtomicLong seq = new AtomicLong();
    /** 审批转发器（CortexModel 注入）：把子 Agent 的 ApprovalRequest 转发到主 TUI（F13）。 */
    private volatile Function<ApprovalRequest, Optional<Outcome>> approvalForwarder;
    /** 阶段14：统一命名注册表（可空兜底本地 byName）；任务完成回调（Team 空闲通知用，T21/T30）。 */
    private volatile com.cortex.team.AgentNameRegistry nameRegistry;
    private final List<java.util.function.Consumer<String>> taskDoneCallbacks = new ArrayList<>();

    public Manager() {
    }

    /** 注入审批转发器（F13）；null 清除。 */
    public void setApprovalForwarder(Function<ApprovalRequest, Optional<Outcome>> forwarder) {
        this.approvalForwarder = forwarder;
    }

    /** 阶段14：注入统一命名注册表（launch 时同步注册 name→id）。 */
    public void setNameRegistry(com.cortex.team.AgentNameRegistry registry) {
        this.nameRegistry = registry;
    }

    /** 阶段14：注册任务完成回调（Team 空闲通知等）；在终态写盘后逐个触发。 */
    public synchronized void onTaskDone(java.util.function.Consumer<String> callback) {
        taskDoneCallbacks.add(callback);
    }

    /** done 队列：TUI 的 consumeTaskDone 虚拟线程阻塞消费（F16/F19）。 */
    public LinkedBlockingQueue<String> doneQueue() {
        return doneQueue;
    }

    @Override
    public String launch(Agent agent, ConversationManager conv, String name, String task) {
        return start(agent, conv, name, task).id();
    }

    @Override
    public SubAgentRun startForeground(Agent agent, ConversationManager conv, String name, String task) {
        return start(agent, conv, name, task);
    }

    /** 注册任务 + 起 runner 虚拟线程（launch 与 startForeground 共用）。 */
    private BackgroundTask start(Agent agent, ConversationManager conv, String name, String task) {
        String id = nextId();
        BackgroundTask bt = new BackgroundTask(id, (name == null || name.isBlank()) ? null : name,
                agent, conv, task);
        tasks.put(id, bt);
        if (bt.name() != null) {
            byName.put(bt.name(), id); // 后启动覆盖前（F1）
            com.cortex.team.AgentNameRegistry reg = nameRegistry;
            if (reg != null) {
                reg.register(bt.name(), id); // 阶段14：统一注册表同步（F37）
            }
        }
        Thread.ofVirtual().name("subagent-" + id).start(() -> runTask(bt, task));
        return bt;
    }

    /**
     * runner（N3）：跑 runToCompletion → 写终态 → 计数闩 → 推送 done。
     * 事件旁路聚合 toolCount / lastActivity / usage。
     */
    private void runTask(BackgroundTask bt, String taskText) {
        LinkedBlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("subagent-" + bt.id() + "-events").start(() -> aggregate(bt, events, bt.done));
        try {
            String text = bt.subAgent.runToCompletion(bt.cancelToken(), bt.conv, taskText, events);
            bt.result = text;
            bt.status = Status.COMPLETED;
        } catch (java.util.concurrent.CancellationException ce) {
            bt.status = Status.CANCELLED;
        } catch (Throwable t) {
            // N3：子 Agent 崩溃不影响主程序，转 FAILED 回灌
            bt.err = t;
            bt.status = Status.FAILED;
        } finally {
            bt.endTime = Instant.now();
            bt.done.countDown();
            if (!doneQueue.offer(bt.id())) {
                System.err.printf("task manager: done 队列已满，丢弃任务通知 %s%n", bt.id());
            }
            for (java.util.function.Consumer<String> cb : taskDoneCallbacks) {
                try {
                    cb.accept(bt.id());
                } catch (Exception e) {
                    System.err.printf("task manager: onTaskDone 回调失败: %s%n", e.getMessage());
                }
            }
        }
    }

    /** 事件聚合：消费 events 直到任务结束且队列排空。 */
    private void aggregate(BackgroundTask bt, LinkedBlockingQueue<AgentEvent> events,
                           java.util.concurrent.CountDownLatch done) {
        try {
            while (true) {
                AgentEvent e = events.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (e != null) {
                    bt.onEvent(e);
                    continue;
                }
                if (done.getCount() == 0 && events.isEmpty()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按名找仍存活的已完成任务并续派新消息（F20/SendMessage）。 */
    public synchronized String sendMessage(String name, String message) {
        String id = byName.get(name);
        if (id == null && nameRegistry != null) {
            id = nameRegistry.resolve(name).orElse(null);
        }
        if (id == null) {
            throw new IllegalStateException("未找到名为 " + name + " 的后台任务");
        }
        BackgroundTask bt = tasks.get(id);
        if (bt == null) {
            throw new IllegalStateException("未找到后台任务 " + id);
        }
        // 阶段14（F46/T31）：FAILED 的队员任务同样可续（如 maxTurns 打断后的继续指派）
        if (bt.status() == Status.RUNNING) {
            throw new IllegalStateException("任务 " + name + " 仍在运行中（当前 " + bt.status().wireName() + "），不能续派");
        }
        bt.conv.addUserMessage(message);
        bt.status = Status.RUNNING;
        bt.result = null;
        bt.err = null;
        // 续派必须在新虚拟线程上跑（runTask 内联跑 runToCompletion）：
        // 若在调用方（SendMessage 工具执行）线程上跑，工具会被拖到 per-tool 超时
        Thread.ofVirtual().name("subagent-" + id + "-resume").start(() -> runTask(bt, ""));
        return id;
    }

    /** 触发取消（F20/TaskStop）：置取消旗标；runner 检查点退出后终态 CANCELLED。 */
    public boolean stop(String id) {
        BackgroundTask bt = tasks.get(id);
        if (bt == null) {
            return false;
        }
        bt.cancelToken().cancel();
        return true;
    }

    public Optional<BackgroundTask> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /** 全部任务，按启动时间升序。 */
    public List<BackgroundTask> list() {
        List<BackgroundTask> all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparing(BackgroundTask::startTime));
        return List.copyOf(all);
    }

    @Override
    public Optional<Outcome> upgradeApproval(ApprovalRequest req) {
        Function<ApprovalRequest, Optional<Outcome>> forwarder = approvalForwarder;
        if (forwarder == null) {
            return Optional.empty(); // 无 TUI（测试/嵌入）：走子 Agent 默认 emit 路径
        }
        return forwarder.apply(req);
    }

    /** 任务数（/status 可选展示；本期仅内部用）。 */
    public int count() {
        return tasks.size();
    }

    private String nextId() {
        long n = seq.incrementAndGet();
        return "task_" + Long.toHexString(System.nanoTime() ^ (n << 32));
    }
}
