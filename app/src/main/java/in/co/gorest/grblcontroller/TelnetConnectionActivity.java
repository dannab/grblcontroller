/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package in.co.gorest.grblcontroller;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;

import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;

import in.co.gorest.grblcontroller.events.BluetoothDisconnectEvent;
import in.co.gorest.grblcontroller.events.GrblSettingMessageEvent;
import in.co.gorest.grblcontroller.events.JogCommandEvent;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.service.FileStreamerIntentService;
import in.co.gorest.grblcontroller.service.GrblTelnetService;
import in.co.gorest.grblcontroller.util.GrblUtils;

public class TelnetConnectionActivity extends GrblActivity {

    private GrblTelnetService telnetService;
    private TelnetServiceMessageHandler serviceMessageHandler;
    private boolean bound;
    private String connectedEndpoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceMessageHandler = new TelnetServiceMessageHandler(this);
        bindService(new Intent(getApplicationContext(), GrblTelnetService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        EventBus.getDefault().register(this);

        new Handler().postDelayed(() -> {
            if (telnetService != null
                    && telnetService.getState() == GrblTelnetService.STATE_NONE
                    && sharedPref.getBoolean(getString(R.string.preference_auto_connect), false)) {
                connectToConfiguredEndpoint();
            }
        }, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (telnetService != null) onTelnetStateChange(telnetService.getState());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bound) {
            onGcodeCommandReceived("$10=1");
            telnetService.setMessageHandler(null);
            telnetService.disconnectService();
            unbindService(serviceConnection);
            bound = false;
        }
        EventBus.getDefault().unregister(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem actionConnect = menu.findItem(R.id.action_connect);
        boolean connected = telnetService != null
                && telnetService.getState() == GrblTelnetService.STATE_CONNECTED;
        actionConnect.setIcon(new IconDrawable(this, FontAwesomeIcons.fa_wifi)
                .colorRes(R.color.colorWhite).sizeDp(24));
        actionConnect.setTitle(connected ? R.string.text_disconnect : R.string.text_connect);
        return super.onCreateOptionsMenu(menu);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_connect:
                if (telnetService == null) {
                    EventBus.getDefault().post(new UiToastEvent(
                            getString(R.string.text_telnet_service_not_running), true, true));
                } else if (telnetService.getState() == GrblTelnetService.STATE_CONNECTED) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.text_disconnect)
                            .setMessage(R.string.text_disconnect_confirm)
                            .setPositiveButton(R.string.text_yes_confirm, (dialog, which) -> {
                                onGcodeCommandReceived("$10=1");
                                if (telnetService != null) telnetService.disconnectService();
                            })
                            .setNegativeButton(R.string.text_cancel, null)
                            .show();
                } else if (telnetService.getState() != GrblTelnetService.STATE_CONNECTING) {
                    promptRestoreWorkPositionThenConnect(this::connectToConfiguredEndpoint);
                }
                return true;

            case R.id.action_grbl_reset:
                boolean confirm = sharedPref.getBoolean(
                        getString(R.string.preference_confirm_grbl_soft_reset), true);
                Runnable reset = () -> {
                    if (FileStreamerIntentService.getIsServiceRunning()) {
                        FileStreamerIntentService.setShouldContinue(false);
                        stopService(new Intent(getApplicationContext(),
                                FileStreamerIntentService.class));
                    }
                    onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
                };
                if (confirm) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.text_grbl_soft_reset)
                            .setMessage(R.string.text_grbl_soft_reset_desc)
                            .setPositiveButton(R.string.text_yes_confirm,
                                    (dialog, which) -> reset.run())
                            .setNegativeButton(R.string.text_cancel, null)
                            .show();
                } else {
                    reset.run();
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void connectToConfiguredEndpoint() {
        String host = sharedPref.getString(getString(R.string.preference_telnet_host), "");
        if (host == null || host.trim().isEmpty()) {
            showToastMessage(getString(R.string.text_telnet_host_required), true, true);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(sharedPref.getString(
                    getString(R.string.preference_telnet_port), "23"));
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            showToastMessage(getString(R.string.text_telnet_invalid_port), true, true);
            return;
        }

        Intent intent = new Intent(getApplicationContext(), GrblTelnetService.class);
        intent.putExtra(GrblTelnetService.KEY_HOST, host.trim());
        intent.putExtra(GrblTelnetService.KEY_PORT, port);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            getApplicationContext().startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            telnetService = ((GrblTelnetService.TelnetBinder) binder).getService();
            bound = true;
            telnetService.setMessageHandler(serviceMessageHandler);
            telnetService.setStatusUpdatePoolInterval(Long.parseLong(sharedPref.getString(
                    getString(R.string.preference_update_pool_interval),
                    String.valueOf(Constants.GRBL_STATUS_UPDATE_INTERVAL))));
            connectedEndpoint = telnetService.getEndpointName();
            onTelnetStateChange(telnetService.getState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            telnetService = null;
            onTelnetStateChange(GrblTelnetService.STATE_NONE);
        }
    };

    private void onTelnetStateChange(int currentState) {
        switch (currentState) {
            case GrblTelnetService.STATE_CONNECTED:
                applyConnectedSubtitle(connectedEndpoint == null
                        ? getString(R.string.text_connected) : connectedEndpoint);
                break;
            case GrblTelnetService.STATE_CONNECTING:
                applySubtitle(getString(R.string.text_connecting));
                break;
            case GrblTelnetService.STATE_NONE:
            default:
                MachineStatusListener.getInstance().setState(
                        Constants.MACHINE_STATUS_NOT_CONNECTED);
                applySubtitle(getString(R.string.text_not_connected));
                in.co.gorest.grblcontroller.service.HttpServerManager.getInstance()
                        .setMachineName(null);
                break;
        }
        invalidateOptionsMenu();
    }

    @Override
    public void onGcodeCommandReceived(String command) {
        if (telnetService != null) telnetService.serialWriteString(command);
    }

    @Override
    public void onGrblRealTimeCommandReceived(byte command) {
        if (telnetService != null) telnetService.serialWriteByte(command);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onJogCommandEvent(JogCommandEvent event) {
        if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)
                || machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)) {
            if (machineStatus.getPlannerBuffer() > 5) {
                onGcodeCommandReceived(event.getCommand());
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblSettingMessageEvent(GrblSettingMessageEvent event) {
        if (event.getSetting().equals("$10") && !event.getValue().equals("2")) {
            onGcodeCommandReceived("$10=2");
        }
        if (event.getSetting().equals("$110")
                || event.getSetting().equals("$111")
                || event.getSetting().equals("$112")) {
            double maxFeedRate = Double.parseDouble(event.getValue());
            if (maxFeedRate > sharedPref.getDouble(
                    getString(R.string.preference_jogging_max_feed_rate),
                    machineStatus.getJogging().feed)) {
                sharedPref.edit().putDouble(
                        getString(R.string.preference_jogging_max_feed_rate),
                        maxFeedRate).apply();
            }
        }
        if (event.getSetting().equals("$32")) {
            machineStatus.setLaserModeEnabled(event.getValue().equals("1"));
        }
    }

    private static final class TelnetServiceMessageHandler extends Handler {
        private final WeakReference<TelnetConnectionActivity> activityReference;

        private TelnetServiceMessageHandler(TelnetConnectionActivity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message message) {
            TelnetConnectionActivity activity = activityReference.get();
            if (activity == null) return;
            switch (message.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    activity.onTelnetStateChange(message.arg1);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    activity.connectedEndpoint = message.getData()
                            .getString(Constants.DEVICE_NAME);
                    break;
                case Constants.MESSAGE_TOAST:
                    activity.showToastMessage(message.getData().getString(Constants.TOAST),
                            true, true);
                    EventBus.getDefault().post(new BluetoothDisconnectEvent(
                            activity.getString(R.string.text_connection_lost)));
                    break;
                default:
                    break;
            }
        }
    }
}
