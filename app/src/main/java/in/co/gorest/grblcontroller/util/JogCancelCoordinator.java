/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package in.co.gorest.grblcontroller.util;

import java.util.Objects;

import in.co.gorest.grblcontroller.model.Constants;

/**
 * Fail-closed coordinator for FluidNC/GRBL jog cancellation.
 *
 * <p>FluidNC and GRBL ignore {@code 0x85} until they have actually entered the
 * Jog state. For that reason a cancel is sent immediately and retried. A late
 * response to a previously sent {@code $J=} command, or a later Jog status,
 * restarts the retry window. New jog commands stay blocked while the stop of
 * a known jog session is not confirmed.</p>
 *
 * <p>This class is deliberately free of Android dependencies so that the
 * timing and state transitions can be unit tested.</p>
 */
public final class JogCancelCoordinator {

    public interface Clock {
        long nowMillis();
    }

    public interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    public interface CancelSender {
        void sendJogCancel(long cancelGeneration);
    }

    public interface StatusRequester {
        void requestStatus();
    }

    static final long[] RETRY_DELAYS_MS = {25L, 75L, 150L, 300L};
    /**
     * FluidNC acknowledges a $J after planning it, immediately before its
     * auto-cycle changes Idle to Jog. An Idle sampled just after that ack can
     * therefore still be stale. Querying after the last retry gives the
     * controller time to enter Jog and consume at least one 0x85.
     */
    static final long POST_RESPONSE_CONFIRMATION_DELAY_MS = 350L;
    static final long CANCEL_WITHOUT_JOG_GUARD_MS = 400L;

    private final Clock clock;
    private final Scheduler scheduler;
    private final CancelSender cancelSender;
    private final StatusRequester statusRequester;

    private long generation;
    private long genericCancelDeadline;
    private boolean cancelPending;
    private boolean jogCommandKnown;
    private boolean jogResponseSeen;
    private int outstandingJogResponses;
    private long lastJogResponseAt;
    private boolean jogStateSeen;
    private boolean closed;

    public JogCancelCoordinator(Clock clock,
                                Scheduler scheduler,
                                CancelSender cancelSender,
                                StatusRequester statusRequester) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.cancelSender = Objects.requireNonNull(cancelSender, "cancelSender");
        this.statusRequester = Objects.requireNonNull(statusRequester, "statusRequester");
    }

    /** Records a $J command before its bytes are handed to the transport. */
    public synchronized void onJogCommandSent() {
        if (closed) return;
        if (!jogCommandKnown) {
            jogResponseSeen = false;
            jogStateSeen = false;
            lastJogResponseAt = 0L;
        }
        jogCommandKnown = true;
        outstandingJogResponses++;
    }

    /**
     * Records the FIFO response (ok or error) belonging to the current $J.
     * If release happened first, the response closes the pre-Jog race by
     * immediately starting a fresh cancel burst.
     */
    public void onJogCommandResponse() {
        long burstGeneration = 0L;
        synchronized (this) {
            if (closed || !jogCommandKnown) return;
            if (outstandingJogResponses > 0) outstandingJogResponses--;
            jogResponseSeen = true;
            lastJogResponseAt = clock.nowMillis();
            if (cancelPending) burstGeneration = nextBurstGenerationLocked();
        }
        if (burstGeneration != 0L) startBurst(burstGeneration);
    }

    /** Starts or renews cancellation. No request is ever suppressed. */
    public void requestCancel() {
        long burstGeneration;
        synchronized (this) {
            if (closed) return;
            cancelPending = true;
            genericCancelDeadline = Math.max(
                    genericCancelDeadline,
                    clock.nowMillis() + CANCEL_WITHOUT_JOG_GUARD_MS);
            burstGeneration = nextBurstGenerationLocked();
        }
        startBurst(burstGeneration);
    }

    /** A controller soft-reset stops motion and invalidates every queued jog. */
    public synchronized void onControllerReset() {
        if (closed) return;
        clearSessionLocked();
    }

    /** Consumes a freshly parsed FluidNC/GRBL status report. */
    public void onMachineStateObserved(String state) {
        long burstGeneration = 0L;
        synchronized (this) {
            if (closed) return;

            if (Constants.MACHINE_STATUS_JOG.equals(state)) {
                jogCommandKnown = true;
                jogStateSeen = true;
                if (cancelPending) burstGeneration = nextBurstGenerationLocked();
            } else if (Constants.MACHINE_STATUS_IDLE.equals(state)
                    || Constants.MACHINE_STATUS_DOOR.equals(state)
                    || Constants.MACHINE_STATUS_ALARM.equals(state)) {
                boolean responseWindowElapsed = jogResponseSeen
                        && clock.nowMillis() - lastJogResponseAt
                        >= POST_RESPONSE_CONFIRMATION_DELAY_MS;
                boolean stopConfirmed = outstandingJogResponses == 0
                        && (jogStateSeen || responseWindowElapsed);

                if (cancelPending && jogCommandKnown && stopConfirmed) {
                    clearSessionLocked();
                } else if (!cancelPending && stopConfirmed) {
                    // FluidNC may expose one last Idle sample between the $J ack
                    // and auto-cycle start. Keep the jog armed through that gap so
                    // a release immediately afterwards is still fail-closed.
                    clearJogEvidenceLocked();
                }
            }
        }
        if (burstGeneration != 0L) startBurst(burstGeneration);
    }

    /** True while even a generic cancel retry window is still active. */
    public synchronized boolean isCancelPending() {
        expireGenericCancelIfSafeLocked();
        return cancelPending;
    }

    /**
     * True when a $J was actually handed to the transport and its stop has
     * not yet been confirmed. Normal G-code and resume must remain blocked.
     */
    public synchronized boolean isJogStopUnconfirmed() {
        expireGenericCancelIfSafeLocked();
        return cancelPending && jogCommandKnown;
    }

    /** Ricontrollo atomico usato dal writer prima di emettere un vecchio retry. */
    public synchronized boolean isCancelGenerationActive(long cancelGeneration) {
        expireGenericCancelIfSafeLocked();
        return !closed
                && cancelPending
                && generation == cancelGeneration;
    }

    public synchronized void close() {
        closed = true;
        generation++;
        cancelPending = false;
        clearJogEvidenceLocked();
    }

    private long nextBurstGenerationLocked() {
        return ++generation;
    }

    /** Esegue scheduler e I/O senza trattenere il monitor del coordinatore. */
    private void startBurst(long burstGeneration) {
        // First stop is synchronous and has priority over scheduled retries.
        sendCancelIfCurrent(burstGeneration);

        for (long delay : RETRY_DELAYS_MS) {
            scheduler.schedule(() -> sendRetry(burstGeneration), delay);
        }
        scheduler.schedule(
                () -> requestConfirmationStatus(burstGeneration),
                POST_RESPONSE_CONFIRMATION_DELAY_MS);
    }

    private void sendRetry(long burstGeneration) {
        sendCancelIfCurrent(burstGeneration);
    }

    private void sendCancelIfCurrent(long burstGeneration) {
        synchronized (this) {
            if (closed || !cancelPending || generation != burstGeneration) return;
        }
        cancelSender.sendJogCancel(burstGeneration);
    }

    private void requestConfirmationStatus(long burstGeneration) {
        synchronized (this) {
            if (closed || !cancelPending || generation != burstGeneration) return;
        }
        statusRequester.requestStatus();
    }

    private void expireGenericCancelIfSafeLocked() {
        if (cancelPending && !jogCommandKnown && clock.nowMillis() >= genericCancelDeadline) {
            cancelPending = false;
            generation++;
        }
    }

    private void clearSessionLocked() {
        cancelPending = false;
        clearJogEvidenceLocked();
        genericCancelDeadline = 0L;
        generation++;
    }

    private void clearJogEvidenceLocked() {
        jogCommandKnown = false;
        jogResponseSeen = false;
        outstandingJogResponses = 0;
        lastJogResponseAt = 0L;
        jogStateSeen = false;
    }
}
