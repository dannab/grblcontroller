/*
 *  /**
 *  * Copyright (C) 2017  Grbl Controller Contributors
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation; either version 2 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *  * <http://www.gnu.org/licenses/>
 *
 */

package in.co.gorest.grblcontroller.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.util.GrblUtils;
import in.co.gorest.grblcontroller.util.JogSafetyController;

public class BaseFragment extends Fragment {

    OnFragmentInteractionListener fragmentInteractionListener;
    private OnFragmentInteractionListener rawFragmentInteractionListener;

    private static final long JOG_WATCHDOG_INTERVAL_MS = 35L;
    private static final long JOG_CANCEL_RETRY_MS = 60L;
    private static final long JOG_RELEASE_UNLOCK_TIMEOUT_MS = 1200L;

    private final Handler jogSafetyHandler = new Handler(Looper.getMainLooper());
    private View activeContinuousJogButton;
    private boolean jogStateSeen;
    private long jogReleaseStartedAt;

    /**
     * Runs independently from ACTION_UP. Continuous jog is allowed only while
     * the actual Android jog button remains physically pressed. Any loss of the
     * pressed state causes an immediate realtime 0x85 cancel.
     */
    private final Runnable jogSafetyWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!JogSafetyController.isLocked()) return;

            MachineStatusListener status = MachineStatusListener.getInstance();
            boolean grblJog = Constants.MACHINE_STATUS_JOG.equals(status.getState());
            if (grblJog) jogStateSeen = true;

            boolean stillPressed = activeContinuousJogButton != null
                    && activeContinuousJogButton.isShown()
                    && activeContinuousJogButton.isEnabled()
                    && activeContinuousJogButton.isPressed();

            if (!stillPressed && !JogSafetyController.isCancelPending()) {
                requestPriorityJogCancel();
            }

            if (JogSafetyController.isCancelPending()) {
                // Keep the global command lock until GRBL has actually left JOG.
                // If JOG was never observed because press/release was faster than
                // a status report, retain the lock briefly then fail safe open.
                boolean safelyStopped = jogStateSeen && !grblJog;
                boolean statusNeverCaughtJog = !jogStateSeen
                        && jogReleaseStartedAt > 0
                        && SystemClock.uptimeMillis() - jogReleaseStartedAt
                        >= JOG_RELEASE_UNLOCK_TIMEOUT_MS;

                if (safelyStopped || statusNeverCaughtJog) {
                    finishJogSafetyLock();
                    return;
                }
            }

            jogSafetyHandler.postDelayed(this, JOG_WATCHDOG_INTERVAL_MS);
        }
    };

    /**
     * Safety proxy shared by every fragment. While continuous jog owns the
     * machine, commands from all tabs are filtered globally.
     */
    private final OnFragmentInteractionListener safeInteractionListener =
            new OnFragmentInteractionListener() {
                @Override
                public void onGcodeCommandReceived(String command) {
                    if (rawFragmentInteractionListener == null) return;

                    if (isContinuousJogCommand(command)) {
                        if (!beginJogSafetyLock()) return;
                        rawFragmentInteractionListener.onGcodeCommandReceived(command);
                        return;
                    }

                    if (JogSafetyController.allowGcode(command)) {
                        rawFragmentInteractionListener.onGcodeCommandReceived(command);
                    }
                }

                @Override
                public void onGrblRealTimeCommandReceived(byte command) {
                    if (rawFragmentInteractionListener == null) return;
                    if (!JogSafetyController.allowRealtime(command)) return;

                    rawFragmentInteractionListener.onGrblRealTimeCommandReceived(command);

                    if (command == GrblUtils.GRBL_JOG_CANCEL_COMMAND
                            && JogSafetyController.isLocked()) {
                        JogSafetyController.markCancelPending();
                        if (jogReleaseStartedAt == 0) {
                            jogReleaseStartedAt = SystemClock.uptimeMillis();
                        }
                        ensureJogWatchdogRunning();
                    }
                }
            };

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnFragmentInteractionListener) {
            rawFragmentInteractionListener = (OnFragmentInteractionListener) context;
            fragmentInteractionListener = safeInteractionListener;
        } else {
            throw new RuntimeException(context + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        if (JogSafetyController.isLocked()) requestPriorityJogCancel();
        jogSafetyHandler.removeCallbacks(jogSafetyWatchdog);
        fragmentInteractionListener = null;
        rawFragmentInteractionListener = null;
        super.onDetach();
    }

    @Override
    public void onPause() {
        // Leaving the jogging screen while a continuous jog is active is always
        // interpreted as a lost operator hold: cancel before doing anything else.
        if (JogSafetyController.isLocked()) requestPriorityJogCancel();
        super.onPause();
        hideSoftKeyboard();
    }

    private boolean isContinuousJogCommand(String command) {
        if (!(this instanceof JoggingTabFragment) || command == null) return false;
        String normalized = command.replace(" ", "").toUpperCase();
        // Current JoggingTabFragment uses 9999.0 exclusively for continuous jog.
        return normalized.startsWith("$J=")
                && (normalized.contains("9999.0") || normalized.contains("9999"));
    }

    private boolean beginJogSafetyLock() {
        if (!JogSafetyController.tryLock()) return false;

        activeContinuousJogButton = findPressedJogButton();
        jogStateSeen = false;
        jogReleaseStartedAt = 0;

        // isPressed() may be updated by Android immediately after ACTION_DOWN,
        // therefore the first independent verification is delayed one cycle.
        jogSafetyHandler.removeCallbacks(jogSafetyWatchdog);
        jogSafetyHandler.postDelayed(jogSafetyWatchdog, JOG_WATCHDOG_INTERVAL_MS);
        return true;
    }

    private void ensureJogWatchdogRunning() {
        jogSafetyHandler.removeCallbacks(jogSafetyWatchdog);
        jogSafetyHandler.post(jogSafetyWatchdog);
    }

    private View findPressedJogButton() {
        View root = getView();
        if (root == null) return null;

        int[] ids = {
                R.id.jog_y_positive, R.id.jog_x_positive, R.id.jog_z_positive,
                R.id.jog_xy_top_left, R.id.jog_xy_top_right,
                R.id.jog_xy_bottom_left, R.id.jog_xy_bottom_right,
                R.id.jog_y_negative, R.id.jog_x_negative, R.id.jog_z_negative,
                R.id.jog_a_positive, R.id.jog_a_negative
        };
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null && v.isPressed()) return v;
        }
        return null;
    }

    private void requestPriorityJogCancel() {
        if (!JogSafetyController.isLocked()) return;

        JogSafetyController.markCancelPending();
        if (jogReleaseStartedAt == 0) {
            jogReleaseStartedAt = SystemClock.uptimeMillis();
        }

        // Bypass the normal command path intentionally: stop has priority over
        // every other activity in the application.
        if (rawFragmentInteractionListener != null) {
            rawFragmentInteractionListener.onGrblRealTimeCommandReceived(
                    GrblUtils.GRBL_JOG_CANCEL_COMMAND);

            jogSafetyHandler.postDelayed(() -> {
                if (JogSafetyController.isLocked()
                        && Constants.MACHINE_STATUS_JOG.equals(
                                MachineStatusListener.getInstance().getState())
                        && rawFragmentInteractionListener != null) {
                    rawFragmentInteractionListener.onGrblRealTimeCommandReceived(
                            GrblUtils.GRBL_JOG_CANCEL_COMMAND);
                }
            }, JOG_CANCEL_RETRY_MS);
        }
        ensureJogWatchdogRunning();
    }

    private void finishJogSafetyLock() {
        jogSafetyHandler.removeCallbacks(jogSafetyWatchdog);
        activeContinuousJogButton = null;
        jogStateSeen = false;
        jogReleaseStartedAt = 0;
        JogSafetyController.unlock();
    }

    /**
     * Nasconde la tastiera virtuale e toglie il focus dalla view corrente.
     */
    protected void hideSoftKeyboard() {
        Activity activity = getActivity();
        if (activity == null) return;

        View focused = activity.getCurrentFocus();
        if (focused == null) focused = getView();
        if (focused == null) return;

        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;

        IBinder token = focused.getWindowToken();
        if (token != null) {
            imm.hideSoftInputFromWindow(token, 0);
        }
        focused.clearFocus();
    }

    public interface OnFragmentInteractionListener {
        void onGcodeCommandReceived(String command);
        void onGrblRealTimeCommandReceived(byte command);
    }
}
