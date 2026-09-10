/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package in.co.gorest.grblcontroller.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.listeners.SerialCommunicationHandler;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.util.GrblUtils;
import in.co.gorest.grblcontroller.util.TelnetProtocolDecoder;

/** Network transport for the Telnet server built into FluidNC. */
public class GrblTelnetService extends GrblSerialService {

    public static final String KEY_HOST = "KEY_TELNET_HOST";
    public static final String KEY_PORT = "KEY_TELNET_PORT";

    public static final int STATE_NONE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private static final String TAG = GrblTelnetService.class.getSimpleName();
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final IBinder binder = new TelnetBinder();
    private volatile int state = STATE_NONE;
    private volatile TelnetSession activeSession;
    private Handler messageHandler;
    private HandlerThread socketWriterThread;
    private Handler socketWriterHandler;
    private String endpointName;

    @Override
    public void onCreate() {
        super.onCreate();
        socketWriterThread = new HandlerThread("fluidnc-telnet-writer");
        socketWriterThread.start();
        socketWriterHandler = new Handler(socketWriterThread.getLooper());
        serialCommunicationHandler = new SerialCommunicationHandler(this);
        EventBus.getDefault().register(this);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            startForeground(Constants.TELNET_SERVICE_NOTIFICATION_ID,
                    getServiceNotification(getString(R.string.text_telnet_service),
                            getString(R.string.text_telnet_service_foreground_message)));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String host = intent.getStringExtra(KEY_HOST);
            int port = intent.getIntExtra(KEY_PORT, 23);
            if (host != null && !host.trim().isEmpty() && port > 0 && port <= 65535) {
                connect(host.trim(), port);
            }
        }
        return Service.START_NOT_STICKY;
    }

    public class TelnetBinder extends Binder {
        public GrblTelnetService getService() {
            return GrblTelnetService.this;
        }
    }

    public void setMessageHandler(Handler handler) {
        messageHandler = handler;
    }

    public int getState() {
        return state;
    }

    public String getEndpointName() {
        return endpointName;
    }

    private synchronized void connect(String host, int port) {
        if (state == STATE_CONNECTING || state == STATE_CONNECTED) return;

        endpointName = host + ":" + port;
        TelnetSession session = new TelnetSession(host, port);
        activeSession = session;
        state = STATE_CONNECTING;
        notifyStateChanged();
        session.start();
    }

    public void disconnectService() {
        closeTransport(true);
    }

    private void closeTransport(boolean stopServiceAfterClose) {
        TelnetSession session;
        synchronized (this) {
            session = activeSession;
            activeSession = null;
            state = STATE_NONE;
        }
        if (serialCommunicationHandler != null) {
            serialCommunicationHandler.stopGrblStatusUpdateService();
        }
        if (session != null) session.cancel();
        setGrblFound(false);
        notifyStateChanged();
        if (stopServiceAfterClose) stopSelf();
    }

    @Override
    public void onDestroy() {
        closeTransport(false);
        if (serialCommunicationHandler != null) serialCommunicationHandler.shutdown();
        if (socketWriterHandler != null) socketWriterHandler.removeCallbacksAndMessages(null);
        if (socketWriterThread != null) socketWriterThread.quitSafely();
        socketWriterHandler = null;
        socketWriterThread = null;
        EventBus.getDefault().unregister(this);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) stopForeground(true);
        super.onDestroy();
    }

    @Override
    protected void serialWriteBytes(byte[] data) {
        TelnetSession session = activeSession;
        enqueueWrite(session, data);
    }

    /**
     * SocketOutputStream non deve mai essere usato dal main thread (Android 7
     * lancia NetworkOnMainThreadException). Un solo writer mantiene inoltre
     * nello stesso ordine payload, newline, realtime e risposte Telnet.
     */
    private void enqueueWrite(TelnetSession session, byte[] data) {
        Handler writer = socketWriterHandler;
        if (state != STATE_CONNECTED || session == null || writer == null || data == null) return;
        byte[] copy = Arrays.copyOf(data, data.length);
        writer.post(() -> {
            if (session == activeSession && state == STATE_CONNECTED) {
                session.writeNow(copy);
            }
        });
    }

    private void onConnected(TelnetSession session) {
        synchronized (this) {
            if (session != activeSession) {
                session.cancel();
                return;
            }
            state = STATE_CONNECTED;
        }
        notifyDeviceName();
        notifyStateChanged();
        // Produce un banner certo anche quando la sessione Telnet viene aperta
        // dopo che FluidNC ha già completato il proprio avvio.
        serialWriteByte(GrblUtils.GRBL_RESET_COMMAND);
    }

    private void onSessionEnded(TelnetSession session, boolean connectedBeforeFailure) {
        synchronized (this) {
            if (session != activeSession) return;
            activeSession = null;
            state = STATE_NONE;
        }
        if (serialCommunicationHandler != null) {
            serialCommunicationHandler.stopGrblStatusUpdateService();
        }
        setGrblFound(false);
        notifyToast(connectedBeforeFailure
                ? getString(R.string.text_telnet_connection_lost)
                : getString(R.string.text_telnet_connection_failed,
                        session.host, session.port));
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        Handler handler = messageHandler;
        if (handler != null) {
            handler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
        }
    }

    private void notifyDeviceName() {
        Handler handler = messageHandler;
        if (handler == null) return;
        Message message = handler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle data = new Bundle();
        data.putString(Constants.DEVICE_NAME, endpointName);
        message.setData(data);
        handler.sendMessage(message);
    }

    private void notifyToast(String text) {
        Handler handler = messageHandler;
        if (handler == null) return;
        Message message = handler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle data = new Bundle();
        data.putString(Constants.TOAST, text);
        message.setData(data);
        handler.sendMessage(message);
    }

    private final class TelnetSession extends Thread implements TelnetProtocolDecoder.Listener {
        private final String host;
        private final int port;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private volatile boolean cancelled;
        private Socket socket;
        private OutputStream output;
        private boolean connected;

        private TelnetSession(String host, int port) {
            super("fluidnc-telnet");
            this.host = host;
            this.port = port;
        }

        @Override
        public void run() {
            try {
                Socket newSocket = new Socket();
                socket = newSocket;
                newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                newSocket.setTcpNoDelay(true);
                newSocket.setKeepAlive(true);
                output = newSocket.getOutputStream();
                connected = true;
                onConnected(this);

                TelnetProtocolDecoder decoder = new TelnetProtocolDecoder(this);
                InputStream input = newSocket.getInputStream();
                int value;
                while (!cancelled && (value = input.read()) != -1) {
                    decoder.accept(value);
                }
                if (!cancelled) throw new IOException("Telnet stream closed");
            } catch (IOException | RuntimeException e) {
                if (!cancelled) {
                    Log.e(TAG, "FluidNC Telnet connection ended", e);
                    onSessionEnded(this, connected);
                }
            } finally {
                closeSocket();
            }
        }

        @Override
        public void onDataByte(int value) {
            if (value == '\n') {
                String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
                lineBuffer.reset();
                if (!line.isEmpty()) {
                    serialCommunicationHandler.obtainMessage(
                            Constants.MESSAGE_READ, line.length(), -1, line).sendToTarget();
                }
            } else if (value != '\r') {
                lineBuffer.write(value);
            }
        }

        @Override
        public void onReply(byte[] reply) {
            enqueueWrite(this, reply);
        }

        private synchronized void writeNow(byte[] data) {
            if (cancelled || output == null) return;
            try {
                output.write(data);
                output.flush();
            } catch (IOException e) {
                Log.e(TAG, "FluidNC Telnet write failed", e);
                onSessionEnded(this, connected);
                cancel();
            }
        }

        private void cancel() {
            cancelled = true;
            closeSocket();
        }

        private synchronized void closeSocket() {
            if (socket == null) return;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
            output = null;
        }
    }
}
