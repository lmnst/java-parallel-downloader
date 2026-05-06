package com.example.downloader;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a single virtual thread that drains a queue of ProgressEvents and
 * invokes the listener serially. Producers (chunk workers and main thread)
 * call emit(); the dispatcher takes care of ordering and exception isolation.
 *
 * The first listener exception logs once to System.err with the event class
 * name; subsequent exceptions are silently dropped.
 *
 * When the configured listener is the no-op, the dispatcher short-circuits
 * with no thread or queue allocation.
 */
final class ProgressDispatcher implements AutoCloseable {

    private final ProgressListener listener;
    private final boolean enabled;
    private final LinkedBlockingQueue<ProgressEvent> queue;
    private final Thread thread;
    private final AtomicBoolean errorLogged = new AtomicBoolean();
    private volatile boolean closed = false;

    ProgressDispatcher(ProgressListener listener) {
        this.listener = listener;
        this.enabled = listener != null && listener != ProgressListener.NO_OP;
        if (enabled) {
            this.queue = new LinkedBlockingQueue<>();
            this.thread = Thread.ofVirtual().name("downloader-progress").start(this::run);
        } else {
            this.queue = null;
            this.thread = null;
        }
    }

    void emit(ProgressEvent event) {
        if (!enabled) return;
        queue.offer(event);
    }

    @Override
    public void close() {
        if (!enabled) return;
        closed = true;
        thread.interrupt();
        try {
            thread.join(5_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            while (!closed) {
                deliver(queue.take());
            }
        } catch (InterruptedException ignored) {
            // Shutdown signal — fall through to drain.
        }
        ProgressEvent remaining;
        while ((remaining = queue.poll()) != null) {
            deliver(remaining);
        }
    }

    private void deliver(ProgressEvent event) {
        try {
            listener.onProgress(event);
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.err.println("ProgressListener threw on "
                        + event.getClass().getSimpleName() + ": " + t);
            }
        }
    }
}
