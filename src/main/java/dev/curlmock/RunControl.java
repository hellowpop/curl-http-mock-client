package dev.curlmock;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/** Coordinates cancellation and lets the JVM shutdown hook wait for durable results. */
final class RunControl {
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean cancelled;
    private Process activeProcess;

    synchronized Process start(ProcessBuilder builder) throws IOException {
        if (cancelled) return null;
        activeProcess = builder.start();
        return activeProcess;
    }

    synchronized void cancel() {
        cancelled = true;
        if (activeProcess != null && activeProcess.isAlive()) activeProcess.destroyForcibly();
    }

    synchronized void processFinished(Process process) {
        if (activeProcess == process) activeProcess = null;
    }

    boolean isCancelled() { return cancelled; }
    void complete() { finished.countDown(); }

    void awaitCompletion() {
        boolean interrupted = false;
        while (true) {
            try { finished.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
