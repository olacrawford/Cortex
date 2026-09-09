package com.cortex.compact.support;

import com.cortex.llm.LlmClient;
import com.cortex.llm.Request;
import com.cortex.llm.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * compact 包测试用假 provider：脚本化逐次回放事件，按调用序号取脚本，
 * 耗尽后回放 defaultResponse；记录收到的 Request，并区分摘要请求（tools 为空）。
 */
public class FakeCompactProvider implements LlmClient {

    private final List<List<StreamEvent>> script = new ArrayList<>();
    private List<StreamEvent> defaultResponse = List.of(new StreamEvent.StreamEnd("stop", 0, 0));
    private int index = 0;
    public final List<Request> reqs = new ArrayList<>();
    public int summarizeCalls = 0;

    public void enqueue(List<StreamEvent> events) {
        script.add(events);
    }

    public void setDefault(List<StreamEvent> events) {
        defaultResponse = events;
    }

    public int streamCalls() {
        return index;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(Request req) {
        reqs.add(req);
        if (req.tools().isEmpty()) {
            summarizeCalls++;
        }
        List<StreamEvent> events = index < script.size() ? script.get(index) : defaultResponse;
        index++;
        return new LinkedBlockingQueue<>(events);
    }
}
