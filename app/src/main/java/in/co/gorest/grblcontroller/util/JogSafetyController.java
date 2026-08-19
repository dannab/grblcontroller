package in.co.gorest.grblcontroller.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global safety gate used while a manual continuous jog is active.
 *
 * While locked, normal G-code is rejected application-wide. Only jog commands
 * and a small set of realtime safety/status commands are allowed to pass.
 * The lock is intentionally kept until the jogging UI confirms that GRBL has
 * left JOG state after a cancel request.
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

    /**
     * During the jog lock, only actual jog commands are allowed on the normal
     * G-code channel. Everything else is dropped before reaching the activity.
     */
    public static boolean allowGcode(String command) {
        if (!LOCKED.get()) return true;
        if (command == null) return false;
        return command.trim().toUpperCase().startsWith("$J=");
    }

    /**
     * Realtime commands that remain valid while jog is locked:
     *  ?    status query
     *  !    feed hold / emergency deceleration path
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
