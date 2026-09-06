package com.ledgerguard.lab.engine;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Concurrency guard ensuring that at most 1 chaos scenario run executes at any given time.
 * Prevents overlapping runs from contaminating financial state or corrupting concurrent assertions.
 */
public class ConcurrencyGuard {

    private final Semaphore semaphore = new Semaphore(1, true);

    /**
     * Attempts to acquire the exclusive execution lock within the specified timeout.
     *
     * @param timeout duration to wait
     * @param unit time unit
     * @return true if acquired, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return semaphore.tryAcquire(timeout, unit);
    }

    /**
     * Releases the exclusive execution lock.
     */
    public void release() {
        semaphore.release();
    }

    /**
     * Returns true if a scenario is currently executing.
     */
    public boolean isLocked() {
        return semaphore.availablePermits() == 0;
    }
}
