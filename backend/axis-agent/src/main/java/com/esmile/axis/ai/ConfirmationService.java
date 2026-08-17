package com.esmile.axis.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 危险操作人工确认门 — 删除类 Tool 执行前的「规划-确认-执行」卡点。
 *
 * <p>tool 执行线程调用 {@link #awaitApproval}：经 {@link ToolCallNotifier} 向活跃 SSE 流
 * 发 {@link ChatEvent.Confirm} 帧后阻塞等待；前端回调 {@code POST /api/agent/confirm/{id}}
 * → {@link #resolve} 放行（批准/拒绝）。超时、无活跃流（/chat/sync 无从确认）、
 * 流中断（{@link #rejectAllPending}）一律视为拒绝——fail-fast 不误删。
 */
@Slf4j
@Component
public class ConfirmationService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final ToolCallNotifier toolCallNotifier;
    private final Duration timeout;
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    @Autowired
    public ConfirmationService(ToolCallNotifier toolCallNotifier) {
        this(toolCallNotifier, DEFAULT_TIMEOUT);
    }

    /** 测试专用：注入短超时避免用例空等 5 分钟 */
    ConfirmationService(ToolCallNotifier toolCallNotifier, Duration timeout) {
        this.toolCallNotifier = toolCallNotifier;
        this.timeout = timeout;
    }

    /**
     * 请求人工确认并挂起等待。返回 true=已批准；
     * false=拒绝/超时/无活跃对话流（调用方不得执行危险操作）。
     */
    public boolean awaitApproval(String action, String detail) {
        if (!toolCallNotifier.isActive()) {
            log.info("无活跃对话流，危险操作直接拒绝。action={}", action);
            return false;
        }
        String confirmId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(confirmId, future);
        toolCallNotifier.emit(new ChatEvent.Confirm(confirmId, action, detail));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("危险操作确认被中断，按拒绝处理。action={} confirmId={}", action, confirmId);
            return false;
        } catch (TimeoutException e) {
            log.info("危险操作确认超时（{}s），按拒绝处理。action={} confirmId={}", timeout.toSeconds(), action, confirmId);
            return false;
        } catch (Exception e) {
            log.warn("危险操作确认异常，按拒绝处理。action={} confirmId={} reason={}", action, confirmId, e.toString());
            return false;
        } finally {
            pending.remove(confirmId);
        }
    }

    /** 前端确认回调；确认 id 不存在或已过期返回 false */
    public boolean resolve(String confirmId, boolean approved) {
        CompletableFuture<Boolean> future = pending.remove(confirmId);
        if (future == null) {
            return false;
        }
        future.complete(approved);
        return true;
    }

    /** 对话流结束（含客户端断连）时取消所有挂起确认，按拒绝放行 */
    public void rejectAllPending() {
        pending.values().forEach(f -> f.complete(false));
        pending.clear();
    }
}
