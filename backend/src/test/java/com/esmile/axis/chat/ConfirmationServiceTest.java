package com.esmile.axis.chat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationServiceTest {

    private final ToolCallNotifier notifier = new ToolCallNotifier();
    private final ConfirmationService service = new ConfirmationService(notifier, Duration.ofMillis(200));
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    void awaitApproval_noActiveStream_returnsFalseImmediately() {
        long start = System.nanoTime();
        boolean approved = service.awaitApproval("删除 Issue", "标题：xxx");
        assertThat(approved).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
    }

    @Test
    void awaitApproval_approved_returnsTrue() throws Exception {
        AtomicReference<ChatEvent> emitted = new AtomicReference<>();
        CountDownLatch eventSeen = new CountDownLatch(1);
        notifier.begin().asFlux().subscribe(e -> {
            emitted.set(e);
            eventSeen.countDown();
        });

        var waiting = executor.submit(() -> service.awaitApproval("删除 Issue", "标题：xxx"));

        assertThat(eventSeen.await(1, TimeUnit.SECONDS)).isTrue();
        ChatEvent.Confirm confirm = (ChatEvent.Confirm) emitted.get();
        assertThat(confirm.action()).isEqualTo("删除 Issue");
        assertThat(confirm.detail()).isEqualTo("标题：xxx");

        assertThat(service.resolve(confirm.confirmId(), true)).isTrue();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void awaitApproval_rejected_returnsFalse() throws Exception {
        AtomicReference<ChatEvent> emitted = new AtomicReference<>();
        CountDownLatch eventSeen = new CountDownLatch(1);
        notifier.begin().asFlux().subscribe(e -> {
            emitted.set(e);
            eventSeen.countDown();
        });

        var waiting = executor.submit(() -> service.awaitApproval("删除 Issue", "标题：xxx"));

        assertThat(eventSeen.await(1, TimeUnit.SECONDS)).isTrue();
        String confirmId = ((ChatEvent.Confirm) emitted.get()).confirmId();
        assertThat(service.resolve(confirmId, false)).isTrue();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void awaitApproval_timeout_returnsFalse() throws Exception {
        notifier.begin();
        long start = System.nanoTime();
        boolean approved = service.awaitApproval("删除 Issue", "标题：xxx");
        assertThat(approved).isFalse();
        assertThat(System.nanoTime() - start)
                .isGreaterThanOrEqualTo(Duration.ofMillis(150).toNanos());
    }

    @Test
    void resolve_unknownId_returnsFalse() {
        assertThat(service.resolve("no-such-id", true)).isFalse();
    }

    @Test
    void rejectAllPending_pendingConfirmation_releasesAsRejected() throws Exception {
        AtomicReference<ChatEvent> emitted = new AtomicReference<>();
        CountDownLatch eventSeen = new CountDownLatch(1);
        notifier.begin().asFlux().subscribe(e -> {
            emitted.set(e);
            eventSeen.countDown();
        });

        var waiting = executor.submit(() -> service.awaitApproval("删除 Issue", "标题：xxx"));
        assertThat(eventSeen.await(1, TimeUnit.SECONDS)).isTrue();

        service.rejectAllPending();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
        // 已取消的确认 id 再回调视为未知
        String confirmId = ((ChatEvent.Confirm) emitted.get()).confirmId();
        assertThat(service.resolve(confirmId, true)).isFalse();
    }
}
