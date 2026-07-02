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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.databinding.DataBindingUtil;

import com.joanzapata.iconify.widget.IconButton;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentConsoleTabBinding;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.listeners.ConsoleLoggerListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.GcodeCommand;
import in.co.gorest.grblcontroller.util.GrblUtils;

public class ConsoleTabFragment extends BaseFragment {

    private MachineStatusListener machineStatus;
    private ConsoleLoggerListener consoleLogger;
    private EnhancedSharedPreferences sharedPref;

    public ConsoleTabFragment() {}

    public static ConsoleTabFragment newInstance() {
        return new ConsoleTabFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = EnhancedSharedPreferences.getInstance(getActivity(), getString(R.string.shared_preference_key));
        consoleLogger = ConsoleLoggerListener.getInstance();
        machineStatus = MachineStatusListener.getInstance();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        FragmentConsoleTabBinding binding = DataBindingUtil.inflate(inflater, R.layout.fragment_console_tab, container, false);
        View view = binding.getRoot();
        binding.setConsole(consoleLogger);
        binding.setMachineStatus(machineStatus);

        final TextView consoleLogView = view.findViewById(R.id.console_logger);
        consoleLogView.setMovementMethod(new ScrollingMovementMethod());
        final EditText commandInput = view.findViewById(R.id.command_input);

        consoleLogView.setOnTouchListener((v, event) -> {
            v.getParent().getParent().getParent().getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().getParent().getParent().getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        IconButton sendCommand = view.findViewById(R.id.send_command);
        sendCommand.setOnClickListener(view12 -> {
            String commandText = commandInput.getText().toString();
            if(commandText.length() > 0){
                GcodeCommand gcodeCommand = new GcodeCommand(commandText);
                fragmentInteractionListener.onGcodeCommandReceived(gcodeCommand.getCommandString());
                if(gcodeCommand.getHasRomAccess()){
                    fragmentInteractionListener.onGcodeCommandReceived(GrblUtils.GRBL_VIEW_PARSER_STATE_COMMAND);
                    fragmentInteractionListener.onGcodeCommandReceived(GrblUtils.GRBL_VIEW_GCODE_PARAMETERS_COMMAND);
                }

                if(gcodeCommand.getCommandString().toUpperCase().contains("G43.1Z")){
                    fragmentInteractionListener.onGcodeCommandReceived(GrblUtils.GRBL_VIEW_GCODE_PARAMETERS_COMMAND);
                }

                if(gcodeCommand.getCommandString().equals("$32=1")) machineStatus.setLaserModeEnabled(true);
                if(gcodeCommand.getCommandString().equals("$32=0")) machineStatus.setLaserModeEnabled(false);
                commandInput.setText(null);
            }
        });

        sendCommand.setOnLongClickListener(view1 -> {
            new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.text_clear_console))
                    .setMessage(getString(R.string.text_clear_console_description))
                    .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> consoleLogger.clearMessages())
                    .setNegativeButton(getString(R.string.text_no_confirm), null)
                    .show();

            return true;
        });

        final SwitchCompat consoleVerboseOutput = view.findViewById(R.id.console_verbose_output);
        consoleVerboseOutput.setChecked(sharedPref.getBoolean(getString(R.string.preference_console_verbose_mode), false));
        consoleVerboseOutput.setOnCheckedChangeListener((compoundButton, b) -> {
            MachineStatusListener.getInstance().setVerboseOutput(b);
            sharedPref.edit().putBoolean(getString(R.string.preference_console_verbose_mode), b).apply();
        });

        return view;
    }

}
