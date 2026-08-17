package com.esmile.axis.ai;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具调用事件通知器 — 让 /api/agent/chat 的 SSE 流能携带「工具调用」事件。
 *
 * <p>单用户应用，同一时刻只有一次对话请求：chat 请求开始时 {@link #begin()} 持有 sink，
 * Tool 方法执行时 {@link #emit(String)}，请求结束（含异常/取消）时 {@link #end()}。
 * 无活跃 sink 时 emit 为空操作，不影响 /api/agent/expand、digest 等共用 Tool 的路径。
 */
@Component
public class ToolCallNotifier {

    private final AtomicReference<Sinks.Many<ChatEvent>> current = new AtomicReference<>();

    public Sinks.Many<ChatEvent> begin() {
        Sinks.Many<ChatEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        current.set(sink);
        return sink;
    }

    public void emit(String label) {
        emit(new ChatEvent.Tool(label));
    }

    public void emit(ChatEvent event) {
        Sinks.Many<ChatEvent> sink = current.get();
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    /** 是否有活跃对话流（无流时危险操作确认门直接拒绝，无从确认） */
    public boolean isActive() {
        return current.get() != null;
    }

    public void end() {
        Sinks.Many<ChatEvent> sink = current.getAndSet(null);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}
