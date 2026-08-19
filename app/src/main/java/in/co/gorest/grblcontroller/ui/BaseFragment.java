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
import android.os.IBinder;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import in.co.gorest.grblcontroller.util.JogSafetyController;

public class BaseFragment extends Fragment {

    OnFragmentInteractionListener fragmentInteractionListener;
    private OnFragmentInteractionListener rawFragmentInteractionListener;

    /**
     * Safety proxy shared by every fragment. When a continuous jog owns the
     * machine, normal commands from any tab are rejected before they reach the
     * Activity/serial layer. Safety/status realtime bytes remain available.
     */
    private final OnFragmentInteractionListener safeInteractionListener =
            new OnFragmentInteractionListener() {
                @Override
                public void onGcodeCommandReceived(String command) {
                    if (rawFragmentInteractionListener != null
                            && JogSafetyController.allowGcode(command)) {
                        rawFragmentInteractionListener.onGcodeCommandReceived(command);
                    }
                }

                @Override
                public void onGrblRealTimeCommandReceived(byte command) {
                    if (rawFragmentInteractionListener != null
                            && JogSafetyController.allowRealtime(command)) {
                        rawFragmentInteractionListener.onGrblRealTimeCommandReceived(command);
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
        fragmentInteractionListener = null;
        rawFragmentInteractionListener = null;
        super.onDetach();
    }

    /**
     * Quando il fragment esce dalla pagina visibile (cambio tab nel ViewPager,
     * apertura di un'activity, ecc.) la tastiera virtuale resta su se un EditText
     * aveva il focus, perché il focus non viene mai rimosso. Lo facciamo qui in
     * modo centralizzato — tutte le sottoclassi chiamano super.onPause() quindi
     * questo hook viene applicato uniformemente senza dover toccare ogni fragment.
     */
    @Override
    public void onPause() {
        super.onPause();
        hideSoftKeyboard();
    }

    /**
     * Nasconde la tastiera virtuale e toglie il focus dalla view corrente.
     * Funziona anche se la view col focus appartiene a un altro fragment dentro
     * la stessa activity (ViewPager con offscreen limit > 0).
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
