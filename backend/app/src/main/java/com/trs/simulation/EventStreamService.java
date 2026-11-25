package com.trs.simulation;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE transports durable events; Last-Event-ID reconnects never restart computation. @author agent
 */
@Service
public class EventStreamService {
    private final RunService runs;
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor();

    /** Injects the authoritative event store. @author agent */
    public EventStreamService(RunService runs) {
        this.runs = runs;
    }

    /**
     * Subscribes from the last received sequence and cleans up on disconnect or timeout. @author
     * agent
     */
    public SseEmitter open(String id, long after) {
        runs.get(id);
        if (after < 0) throw new IllegalArgumentException("Negative event cursor");
        var emitter = new SseEmitter(300_000L);
        var cursor = new AtomicLong(after);
        var ticks = new AtomicInteger();
        var future = new AtomicReference<ScheduledFuture<?>>();
        Runnable cancel = () -> {
            var f = future.get();
            if (f != null) f.cancel(false);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(e -> cancel.run());
        future.set(polling.scheduleWithFixedDelay(
                () -> {
                    try {
                        for (var event : runs.events(id, cursor.get())) {
                            emitter.send(SseEmitter.event()
                                    .id(Long.toString(event.sequence()))
                                    .name("simulation")
                                    .data(event));
                            cursor.set(event.sequence());
                        }
                        if (ticks.incrementAndGet() % 10 == 0)
                            emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception e) {
                        cancel.run();
                        emitter.complete();
                    }
                },
                100,
                400,
                TimeUnit.MILLISECONDS));
        return emitter;
    }

    /** Releases polling resources on shutdown. @author agent */
    @PreDestroy
    public void close() {
        polling.shutdownNow();
    }
}
