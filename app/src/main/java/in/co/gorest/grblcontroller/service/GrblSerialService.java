/*
 * Copyright (C) 2024-2026 Daniele Cicchinelli
 *
 * Based on GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 * <http://www.gnu.org/licenses/>
 */
package in.co.gorest.grblcontroller.service;

import android.app.Notification;
import android.app.Service;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.ThreadMode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.events.GrblRealTimeCommandEvent;
import in.co.gorest.grblcontroller.events.JogCommandEvent;
import in.co.gorest.grblcontroller.events.JogStopRequestedEvent;
import in.co.gorest.grblcontroller.helpers.NotificationHelper;
import in.co.gorest.grblcontroller.listeners.SerialCommunicationHandler;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.model.GcodeCommand;
import in.co.gorest.grblcontroller.util.GrblUtils;
import in.co.gorest.grblcontroller.util.JogCancelCoordinator;

/**
 * Base comune dei due service seriali (Bluetooth e USB OTG).
 *
 * Contiene tutto ciò che prima era duplicato nei due service: scrittura
 * stringa/byte con newline e echo in console, intervallo di polling dello
 * stato, subscriber EventBus per comandi gcode e real-time, notifica di
 * foreground e flag "GRBL riconosciuto". Alle sottoclassi resta solo la
 * gestione del canale fisico ({@link #serialWriteBytes(byte[])}).
 */
public abstract class GrblSerialService extends Service {

    private static final long SAFE_DISCONNECT_FIRST_CHECK_MS = 425L;
    private static final long SAFE_DISCONNECT_RECHECK_MS = 250L;
    private static final int SHUTDOWN_OPEN = 0;
    private static final int SHUTDOWN_STOPPING = 1;
    private static final int SHUTDOWN_CLOSING = 2;
    private static final int SHUTDOWN_CLOSED = 3;

    protected SerialCommunicationHandler serialCommunicationHandler;
    private long statusUpdatePoolInterval = Constants.GRBL_STATUS_UPDATE_INTERVAL;
    private HandlerThread jogCancelHandlerThread;
    private Handler jogCancelHandler;
    private JogCancelCoordinator jogCancelCoordinator;
    private final Object transportWriteLock = new Object();
    private final Object commandGateLock = new Object();
    private final AtomicBoolean transportFailureScheduled = new AtomicBoolean();
    /** Tutti i gesti con token minore o uguale sono stati fermati. */
    private volatile long invalidJogGestureToken = JogCommandEvent.currentGestureToken();
    /** Vero solo dopo banner/reset della connessione corrente. */
    private boolean controllerSynchronized;
    /** Distingue link assente da link presente ma non ancora sincronizzato. */
    private boolean transportConnected;
    /** Stato terminale osservato dopo la richiesta di disconnessione. */
    private boolean safeMachineStateObserved;
    private Runnable safeDisconnectAction;
    private int shutdownState = SHUTDOWN_OPEN;
    private long safeDisconnectGeneration;
    private boolean safeDisconnectCheckScheduled;

    /**
     * FIFO delle risposte ai comandi testuali. Serve a riconoscere l'ok/error
     * di un $J arrivato dopo il rilascio senza confonderlo con altri comandi.
     */
    private final ConcurrentLinkedQueue<Boolean> pendingCommandResponses =
            new ConcurrentLinkedQueue<>();

    /** true dal primo banner GRBL valido fino alla disconnessione. */
    private volatile boolean grblFound = false;

    public boolean isGrblFound() { return grblFound; }

    public void setGrblFound(boolean found) { grblFound = found; }

    @Override
    public void onCreate() {
        super.onCreate();
        jogCancelHandlerThread = new HandlerThread("grbl-jog-cancel");
        jogCancelHandlerThread.start();
        jogCancelHandler = new Handler(jogCancelHandlerThread.getLooper());
        jogCancelCoordinator = new JogCancelCoordinator(
                SystemClock::elapsedRealtime,
                (task, delayMillis) -> jogCancelHandler.postDelayed(task, delayMillis),
                this::writeJogCancelIfCurrent,
                () -> writeTransportFrame(new byte[]{GrblUtils.GRBL_STATUS_COMMAND}));
    }

    /**
     * Scrive i byte grezzi sul canale fisico (socket Bluetooth o porta USB).
     * Deve essere un no-op se il canale non e' connesso.
     */
    protected abstract boolean serialWriteBytes(byte[] b);

    /** Serializza ogni frame per evitare interleaving tra UI, parser e retry. */
    private boolean writeTransportFrame(byte[] bytes) {
        boolean written;
        synchronized (transportWriteLock) {
            written = serialWriteBytes(bytes);
        }
        if (!written) scheduleTransportWriteFailure();
        return written;
    }

    private void scheduleTransportWriteFailure() {
        Handler handler = jogCancelHandler;
        if (handler == null || !transportFailureScheduled.compareAndSet(false, true)) return;
        handler.post(() -> {
            transportFailureScheduled.set(false);
            onTransportDisconnected();
            onTransportWriteFailure();
        });
    }

    /** Hook delle sottoclassi per aggiornare/riavviare il proprio trasporto. */
    protected void onTransportWriteFailure() {
    }

    /**
     * Check della generazione e write condividono il gate dei nuovi jog: un
     * retry gia' svegliato non puo' scivolare dopo una nuova pressione.
     */
    private void writeJogCancelIfCurrent(long cancelGeneration) {
        synchronized (commandGateLock) {
            JogCancelCoordinator coordinator = jogCancelCoordinator;
            if (coordinator != null
                    && coordinator.isCancelGenerationActive(cancelGeneration)) {
                writeTransportFrame(new byte[]{GrblUtils.GRBL_JOG_CANCEL_COMMAND});
            }
        }
    }

    public void serialWriteByte(byte b) {
        synchronized (commandGateLock) {
            JogCancelCoordinator coordinator = jogCancelCoordinator;

            if (shutdownState >= SHUTDOWN_STOPPING
                    && b != GrblUtils.GRBL_JOG_CANCEL_COMMAND
                    && b != GrblUtils.GRBL_STATUS_COMMAND
                    && b != GrblUtils.GRBL_RESET_COMMAND) {
                return;
            }

            if (b == GrblUtils.GRBL_JOG_CANCEL_COMMAND) {
                // Qualunque sorgente dello stop (UI, volume, HTTP o lifecycle)
                // invalida nel service anche i callback STEP locali ancora in
                // attesa. L'evento MAIN disarma poi immediatamente la UI, ma il
                // token rende sicuro lo stop anche se quel thread e' bloccato.
                invalidateCurrentJogGestures();
                if (coordinator != null) {
                    coordinator.requestCancel();
                } else {
                    writeTransportFrame(new byte[]{b});
                }
                return;
            }

            if (b == GrblUtils.GRBL_RESET_COMMAND) {
                // La sessione viene azzerata solo quando il nuovo banner del
                // controller torna dalla seriale. Una write locale riuscita
                // non dimostra che FluidNC abbia davvero ricevuto il reset.
                controllerSynchronized = false;
                safeMachineStateObserved = false;
                setGrblFound(false);
                invalidateCurrentJogGestures();
            }

            // Non consentire un resume mentre l'arresto di un jog noto non e'
            // ancora confermato. Status, feed-hold, door e reset restano ammessi.
            if (b == GrblUtils.GRBL_RESUME_COMMAND
                    && (!controllerSynchronized
                    || (coordinator != null && coordinator.isJogStopUnconfirmed()))) {
                return;
            }

            writeTransportFrame(new byte[]{b});
        }
    }

    /**
     * Gateway riservato ai jog della UI. Il token resta uguale per tutta la
     * pressione: dopo qualunque STOP, nessun Runnable gia' accodato puo'
     * riaprire il movimento, nemmeno quando la finestra di retry e' terminata.
     */
    public boolean serialWriteJogCommand(JogCommandEvent event) {
        synchronized (commandGateLock) {
            if (event == null
                    || event.getGestureToken() <= 0
                    || event.getGestureToken() <= invalidJogGestureToken) {
                return false;
            }
            return serialWriteStringLocked(event.getCommand());
        }
    }

    public boolean serialWriteString(String s) {
        synchronized (commandGateLock) {
            return serialWriteStringLocked(s);
        }
    }

    private boolean serialWriteStringLocked(String s) {
        if (s == null) return false;

        // Dopo link-loss o durante una disconnessione volontaria il controller
        // deve prima rispondere a reset/banner; nessun G-code puo' attraversare
        // una connessione di cui non conosciamo lo stato di moto.
        if (!controllerSynchronized || shutdownState != SHUTDOWN_OPEN) return false;

        JogCancelCoordinator coordinator = jogCancelCoordinator;
        String normalized = s.trim().toUpperCase();
        boolean isJogCommand = normalized.startsWith("$J=");

        // I vecchi retry non devono colpire un nuovo jog. Durante uno stop
        // non confermato nessun comando testuale puo' superare il gateway.
        if (coordinator != null
                && ((isJogCommand && coordinator.isCancelPending())
                || coordinator.isJogStopUnconfirmed())) {
            return false;
        }

        byte[] payload = s.getBytes(StandardCharsets.UTF_8);
        byte[] frame = Arrays.copyOf(payload, payload.length + 1);
        frame[frame.length - 1] = 0x0A;
        if (!writeTransportFrame(frame)) return false;

        if (isJogCommand && coordinator != null) {
            coordinator.onJogCommandSent();
        }
        pendingCommandResponses.offer(isJogCommand);
        serialCommunicationHandler.obtainMessage(Constants.MESSAGE_WRITE, s.length(), -1, s).sendToTarget();
        return true;
    }

    private void invalidateCurrentJogGestures() {
        invalidJogGestureToken = Math.max(
                invalidJogGestureToken,
                JogCommandEvent.currentGestureToken());
        EventBus.getDefault().post(new JogStopRequestedEvent());
    }

    /** Chiamato dal parser seriale, che conserva l'ordine FIFO delle risposte. */
    public void onCommandResponseReceived() {
        synchronized (commandGateLock) {
            Boolean wasJogCommand = pendingCommandResponses.poll();
            if (Boolean.TRUE.equals(wasJogCommand) && jogCancelCoordinator != null) {
                jogCancelCoordinator.onJogCommandResponse();
            }
        }
    }

    /** Il banner ricevuto e' la conferma che il soft-reset ha raggiunto FluidNC. */
    public void onControllerResetObserved() {
        synchronized (commandGateLock) {
            pendingCommandResponses.clear();
            controllerSynchronized = true;
            safeMachineStateObserved = false;
            if (jogCancelCoordinator != null) jogCancelCoordinator.onControllerReset();
        }
        scheduleSafeDisconnectCheck(0L);
    }

    /** Chiamato dopo ogni status FluidNC/GRBL appena analizzato. */
    public void onMachineStateObserved(String state) {
        if (jogCancelCoordinator != null) {
            jogCancelCoordinator.onMachineStateObserved(state);
        }
        synchronized (commandGateLock) {
            if (Constants.MACHINE_STATUS_JOG.equals(state)) {
                safeMachineStateObserved = false;
            } else if (Constants.MACHINE_STATUS_IDLE.equals(state)) {
                // Door puo' ancora essere in retract/restore su FluidNC.
                // Per chiudere volontariamente il link accettiamo solo Idle.
                safeMachineStateObserved = true;
            }
        }
        scheduleSafeDisconnectCheck(0L);
    }

    /**
     * La chiusura volontaria resta service-owned: mantiene vivo il canale fino
     * a stop confermato. Se la conferma non arriva, il collegamento non viene
     * deliberatamente tagliato sotto un possibile jog da 9999.
     */
    protected final void requestSafeTransportShutdown(Runnable disconnectAction) {
        Runnable immediateAction = null;
        synchronized (commandGateLock) {
            if (disconnectAction == null || shutdownState != SHUTDOWN_OPEN) return;

            safeDisconnectAction = disconnectAction;
            shutdownState = SHUTDOWN_STOPPING;
            safeDisconnectGeneration++;
            safeMachineStateObserved = false;
            invalidateCurrentJogGestures();

            if (!transportConnected) {
                immediateAction = takeSafeDisconnectActionLocked();
            } else {
                if (jogCancelCoordinator != null) {
                    jogCancelCoordinator.requestCancel();
                } else {
                    writeTransportFrame(new byte[]{GrblUtils.GRBL_JOG_CANCEL_COMMAND});
                }

                // Link presente ma stato ignoto: il cancel potrebbe essere
                // arrivato prima di Jog. Il reset deve tornare con un banner.
                if (!controllerSynchronized) {
                    serialWriteByte(GrblUtils.GRBL_RESET_COMMAND);
                }
            }
        }

        if (immediateAction != null) {
            immediateAction.run();
        } else {
            scheduleSafeDisconnectCheck(SAFE_DISCONNECT_FIRST_CHECK_MS);
        }
    }

    /**
     * Segnala una perdita fisica del link. Il moto diventa sconosciuto e resta
     * bloccato fino al banner di un reset sulla nuova connessione.
     */
    protected final void onTransportDisconnected() {
        Runnable disconnectAction;
        boolean notifyUi;
        synchronized (commandGateLock) {
            notifyUi = controllerSynchronized
                    || JogCommandEvent.currentGestureToken() > invalidJogGestureToken;
            transportConnected = false;
            controllerSynchronized = false;
            safeMachineStateObserved = false;
            pendingCommandResponses.clear();
            invalidJogGestureToken = Math.max(
                    invalidJogGestureToken,
                    JogCommandEvent.currentGestureToken());
            setGrblFound(false);
            if (shutdownState == SHUTDOWN_STOPPING) {
                disconnectAction = takeSafeDisconnectActionLocked();
            } else {
                disconnectAction = null;
                if (shutdownState == SHUTDOWN_CLOSING) {
                    shutdownState = SHUTDOWN_CLOSED;
                }
            }
        }
        if (notifyUi) EventBus.getDefault().post(new JogStopRequestedEvent());
        if (disconnectAction != null) disconnectAction.run();
    }

    /** Chiamato quando il canale fisico e' pronto, prima del reset iniziale. */
    protected final void onTransportConnected() {
        synchronized (commandGateLock) {
            transportConnected = true;
            controllerSynchronized = false;
            safeMachineStateObserved = false;
            pendingCommandResponses.clear();
            setGrblFound(false);
            invalidJogGestureToken = Math.max(
                    invalidJogGestureToken,
                    JogCommandEvent.currentGestureToken());
        }
        EventBus.getDefault().post(new JogStopRequestedEvent());
    }

    private void scheduleSafeDisconnectCheck(long delayMillis) {
        final Handler handler = jogCancelHandler;
        final long generation;
        synchronized (commandGateLock) {
            if (handler == null
                    || shutdownState != SHUTDOWN_STOPPING
                    || safeDisconnectCheckScheduled) return;
            safeDisconnectCheckScheduled = true;
            generation = safeDisconnectGeneration;
        }
        handler.postDelayed(() -> runSafeDisconnectCheck(generation), delayMillis);
    }

    private void runSafeDisconnectCheck(long generation) {
        Runnable disconnectAction = null;
        boolean requestStatus = false;
        synchronized (commandGateLock) {
            if (shutdownState != SHUTDOWN_STOPPING
                    || safeDisconnectGeneration != generation) return;
            safeDisconnectCheckScheduled = false;

            if (!transportConnected) {
                disconnectAction = takeSafeDisconnectActionLocked();
            } else {
                boolean cancelComplete = jogCancelCoordinator == null
                        || !jogCancelCoordinator.isCancelPending();
                if (cancelComplete && safeMachineStateObserved) {
                    disconnectAction = takeSafeDisconnectActionLocked();
                } else {
                    requestStatus = true;
                }
            }
        }

        if (disconnectAction != null) {
            disconnectAction.run();
        } else if (requestStatus) {
            serialWriteByte(GrblUtils.GRBL_STATUS_COMMAND);
            scheduleSafeDisconnectCheck(SAFE_DISCONNECT_RECHECK_MS);
        }
    }

    private Runnable takeSafeDisconnectActionLocked() {
        Runnable action = safeDisconnectAction;
        safeDisconnectAction = null;
        shutdownState = SHUTDOWN_CLOSING;
        safeDisconnectGeneration++;
        safeDisconnectCheckScheduled = false;
        return action;
    }

    public boolean isJogCancelPending() {
        return jogCancelCoordinator != null && jogCancelCoordinator.isCancelPending();
    }

    @Override
    public void onDestroy() {
        synchronized (commandGateLock) {
            safeDisconnectAction = null;
            shutdownState = SHUTDOWN_CLOSED;
            safeDisconnectGeneration++;
            safeDisconnectCheckScheduled = false;
            transportConnected = false;
            controllerSynchronized = false;
        }
        if (jogCancelCoordinator != null) jogCancelCoordinator.close();
        if (jogCancelHandler != null) jogCancelHandler.removeCallbacksAndMessages(null);
        if (jogCancelHandlerThread != null) jogCancelHandlerThread.quitSafely();
        pendingCommandResponses.clear();
        super.onDestroy();
    }

    public long getStatusUpdatePoolInterval() {
        return this.statusUpdatePoolInterval;
    }

    public void setStatusUpdatePoolInterval(long poolInterval) {
        this.statusUpdatePoolInterval = poolInterval;
    }

    /** Notifica di foreground sul canale servizio, identica nei due service. */
    protected Notification getServiceNotification(String title, String message) {
        return new NotificationCompat.Builder(getApplicationContext(), NotificationHelper.CHANNEL_SERVICE_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setAutoCancel(true).build();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGrblGcodeSendEvent(GcodeCommand event) {
        serialWriteString(event.getCommandString());
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGrblRealTimeCommandEvent(GrblRealTimeCommandEvent grblRealTimeCommandEvent) {
        serialWriteByte(grblRealTimeCommandEvent.getCommand());
    }
}
