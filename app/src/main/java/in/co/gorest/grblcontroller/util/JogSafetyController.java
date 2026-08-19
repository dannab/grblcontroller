package in.co.gorest.grblcontroller.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global safety gate used while a manual continuous jog is active.
 *
 * The first continuous $J command acquires the lock and is sent directly by
 * BaseFragment. After that, the normal G-code channel is completely closed
 * until GRBL has stopped. Only selected realtime safety/status bytes can pass.
 */
public final class JogSafetyController {

    private static final AtomicBoolean LOCKED = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_PENDING = new AtomicBoolean(false);

    private JogSafetyController() {}

    public static boolean tryLock() {
        boolean acquired = LOCKED.compareAndSet(false, true);
        if (acquired) CANCEL_PENDING.set(false);
        return acquired;
    }

    public static void markCancelPending() {
        if (LOCKED.get()) CANCEL_PENDING.set(true);
    }

    public static void unlock() {
        CANCEL_PENDING.set(false);
        LOCKED.set(false);
    }

    public static boolean isLocked() {
        return LOCKED.get();
    }

    public static boolean isCancelPending() {
        return CANCEL_PENDING.get();
    }

    /** No normal G-code is accepted after a continuous jog owns the machine. */
    public static boolean allowGcode(String command) {
        return !LOCKED.get();
    }

    /**
     * Realtime commands that remain valid while jog is locked:
     *  ?    status query
     *  !    feed hold / safety deceleration path
     *  0x84 safety door
     *  0x85 jog cancel
     *  0x18 soft reset
     * Resume (~) and overrides are deliberately not whitelisted.
     */
    public static boolean allowRealtime(byte command) {
        if (!LOCKED.get()) return true;
        return command == GrblUtils.GRBL_STATUS_COMMAND
                || command == GrblUtils.GRBL_PAUSE_COMMAND
                || command == GrblUtils.GRBL_DOOR_COMMAND
                || command == GrblUtils.GRBL_JOG_CANCEL_COMMAND
                || command == GrblUtils.GRBL_RESET_COMMAND;
    }
}
