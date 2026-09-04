package com.ledgerguard.funding.infrastructure;

/**
 * Isolated context capturing the physical attempt history of a single logical createOperation call.
 * Instantiated locally per invocation to guarantee thread safety.
 */
public class CreateAttemptContext {

    private boolean rawHttpAttempted = false;
    private boolean ambiguousAttemptSeen = false;
    private int physicalAttempts = 0;
    private Exception lastException = null;

    public void recordAttempt() {
        this.rawHttpAttempted = true;
        this.physicalAttempts++;
    }

    public void recordAmbiguous(Exception ex) {
        this.ambiguousAttemptSeen = true;
        this.lastException = ex;
    }

    public void recordDefiniteFailure(Exception ex) {
        this.lastException = ex;
    }

    public boolean isRawHttpAttempted() {
        return rawHttpAttempted;
    }

    public boolean isAmbiguousAttemptSeen() {
        return ambiguousAttemptSeen;
    }

    public int getPhysicalAttempts() {
        return physicalAttempts;
    }

    public Exception getLastException() {
        return lastException;
    }
}
