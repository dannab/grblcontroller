package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import java.util.Comparator;
import java.util.PriorityQueue;

import in.co.gorest.grblcontroller.model.Constants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JogCancelCoordinatorTest {

    @Test
    public void cancelIsImmediateAndRetriedAtEverySafetyOffset() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.requestCancel();
        assertEquals(1, runtime.cancelCount);

        runtime.advanceTo(24);
        assertEquals(1, runtime.cancelCount);
        runtime.advanceTo(25);
        assertEquals(2, runtime.cancelCount);
        runtime.advanceTo(75);
        assertEquals(3, runtime.cancelCount);
        runtime.advanceTo(150);
        assertEquals(4, runtime.cancelCount);
        runtime.advanceTo(300);
        assertEquals(5, runtime.cancelCount);
    }

    @Test
    public void overlappingCancelRenewsBurstInsteadOfBeingDiscarded() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.requestCancel();
        runtime.advanceTo(60); // immediate + retry at 25 ms
        assertEquals(2, runtime.cancelCount);

        coordinator.requestCancel();
        assertEquals(3, runtime.cancelCount);

        runtime.advanceTo(75); // stale retry from the first generation: ignored
        assertEquals(3, runtime.cancelCount);
        runtime.advanceTo(85); // first retry of the renewed generation
        assertEquals(4, runtime.cancelCount);
    }

    @Test
    public void lateJogResponseAfterReleaseStartsFreshCancelBurst() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        runtime.advanceTo(300);
        assertEquals(5, runtime.cancelCount);
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(500);
        coordinator.onJogCommandResponse();
        assertEquals(6, runtime.cancelCount);

        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(850);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
        assertFalse(coordinator.isCancelPending());

        runtime.advanceTo(1000);
        assertEquals(10, runtime.cancelCount);
    }

    @Test
    public void staleIdleBeforeJogResponseDoesNotUnlock() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);

        assertTrue(coordinator.isJogStopUnconfirmed());

        coordinator.onJogCommandResponse();
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(JogCancelCoordinator.POST_RESPONSE_CONFIRMATION_DELAY_MS);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void fluidNcIdleImmediatelyAfterAckIsNotAcceptedAsStop() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        runtime.advanceTo(10);
        coordinator.onJogCommandResponse();

        // FluidNC can acknowledge just before auto-cycle changes Idle to Jog.
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(359);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(360);
        assertEquals(1, runtime.statusRequestCount);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void fluidNcEarlyIdleKeepsJogArmedEvenBeforeRelease() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.onJogCommandResponse();
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);

        coordinator.requestCancel();
        assertTrue(coordinator.isJogStopUnconfirmed());

        runtime.advanceTo(JogCancelCoordinator.POST_RESPONSE_CONFIRMATION_DELAY_MS);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void allQueuedJogResponsesMustArriveBeforeIdleCanConfirmStop() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        coordinator.onJogCommandResponse();
        runtime.advanceTo(1000);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        coordinator.onJogCommandResponse();
        runtime.advanceTo(1350);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void jogStatusWhileStoppingRenewsCancelAndWaitsForOutstandingAck() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        assertEquals(1, runtime.cancelCount);

        runtime.advanceTo(10);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_JOG);
        assertEquals(2, runtime.cancelCount);
        assertTrue(coordinator.isJogStopUnconfirmed());

        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertTrue(coordinator.isJogStopUnconfirmed());

        coordinator.onJogCommandResponse();
        runtime.advanceTo(360);
        coordinator.onMachineStateObserved(Constants.MACHINE_STATUS_IDLE);
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void genericVolumeStopExpiresButKnownJogFailsClosed() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.requestCancel();
        runtime.advanceTo(JogCancelCoordinator.CANCEL_WITHOUT_JOG_GUARD_MS - 1);
        assertTrue(coordinator.isCancelPending());
        runtime.advanceTo(JogCancelCoordinator.CANCEL_WITHOUT_JOG_GUARD_MS);
        assertFalse(coordinator.isCancelPending());

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        runtime.advanceTo(5000);
        assertTrue(coordinator.isJogStopUnconfirmed());
    }

    @Test
    public void controllerResetConfirmsStopAndInvalidatesRetries() {
        FakeRuntime runtime = new FakeRuntime();
        JogCancelCoordinator coordinator = runtime.createCoordinator();

        coordinator.onJogCommandSent();
        coordinator.requestCancel();
        assertEquals(1, runtime.cancelCount);

        coordinator.onControllerReset();
        runtime.advanceTo(1000);

        assertEquals(1, runtime.cancelCount);
        assertFalse(coordinator.isCancelPending());
        assertFalse(coordinator.isJogStopUnconfirmed());
    }

    private static final class FakeRuntime implements
            JogCancelCoordinator.Clock,
            JogCancelCoordinator.Scheduler,
            JogCancelCoordinator.CancelSender,
            JogCancelCoordinator.StatusRequester {

        private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>(
                Comparator.comparingLong((ScheduledTask task) -> task.when)
                        .thenComparingLong(task -> task.sequence));
        private long now;
        private long sequence;
        private int cancelCount;
        private int statusRequestCount;

        JogCancelCoordinator createCoordinator() {
            return new JogCancelCoordinator(this, this, this, this);
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(new ScheduledTask(now + delayMillis, sequence++, task));
        }

        @Override
        public void sendJogCancel(long cancelGeneration) {
            cancelCount++;
        }

        @Override
        public void requestStatus() {
            statusRequestCount++;
        }

        void advanceTo(long targetTime) {
            while (!tasks.isEmpty() && tasks.peek().when <= targetTime) {
                ScheduledTask task = tasks.remove();
                now = task.when;
                task.runnable.run();
            }
            now = targetTime;
        }
    }

    private static final class ScheduledTask {
        private final long when;
        private final long sequence;
        private final Runnable runnable;

        private ScheduledTask(long when, long sequence, Runnable runnable) {
            this.when = when;
            this.sequence = sequence;
            this.runnable = runnable;
        }
    }
}
