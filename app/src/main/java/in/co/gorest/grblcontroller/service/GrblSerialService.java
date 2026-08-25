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
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.events.GrblRealTimeCommandEvent;
import in.co.gorest.grblcontroller.helpers.NotificationHelper;
import in.co.gorest.grblcontroller.listeners.SerialCommunicationHandler;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.model.GcodeCommand;
import in.co.gorest.grblcontroller.util.GrblUtils;

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

    private static final byte[] BYTE_NEWLINE = { 0x0A };

    /**
     * Un cancel jog viene ripetuto quattro volte senza consultare lo stato
     * macchina. I realtime command vengono interpretati fuori dal normale
     * flusso G-code; la breve spaziatura copre la finestra in cui il $J puo'
     * essere stato ricevuto ma non ancora essere entrato in stato JOG.
     */
    private static final int JOG_CANCEL_BURST_COUNT = 4;
    private static final long JOG_CANCEL_BURST_INTERVAL_MS = 25L;

    protected SerialCommunicationHandler serialCommunicationHandler;
    private long statusUpdatePoolInterval = Constants.GRBL_STATUS_UPDATE_INTERVAL;
    private final Handler jogCancelHandler = new Handler(Looper.getMainLooper());
    private boolean jogCancelBurstActive = false;

    /** true dal primo banner GRBL valido fino alla disconnessione. */
    private volatile boolean grblFound = false;

    public boolean isGrblFound() { return grblFound; }

    public void setGrblFound(boolean found) { grblFound = found; }

    /**
     * Scrive i byte grezzi sul canale fisico (socket Bluetooth o porta USB).
     * Deve essere un no-op se il canale non e' connesso.
     */
    protected abstract void serialWriteBytes(byte[] b);

    public void serialWriteByte(byte b) {
        if (b != GrblUtils.GRBL_JOG_CANCEL_COMMAND) {
            serialWriteBytes(new byte[]{ b });
            return;
        }

        // Ogni richiesta 0x85 genera una sola raffica. Se durante la raffica
        // arriva un altro 0x85, non ne avviamo una seconda sovrapposta.
        synchronized (this) {
            if (jogCancelBurstActive) return;
            jogCancelBurstActive = true;
        }

        // Primo cancel immediato.
        serialWriteBytes(new byte[]{ GrblUtils.GRBL_JOG_CANCEL_COMMAND });

        // Altri tre cancel sempre inviati, indipendentemente da Idle/Jog/Hold.
        for (int i = 1; i < JOG_CANCEL_BURST_COUNT; i++) {
            final int attempt = i;
            jogCancelHandler.postDelayed(() -> {
                serialWriteBytes(new byte[]{ GrblUtils.GRBL_JOG_CANCEL_COMMAND });
                if (attempt == JOG_CANCEL_BURST_COUNT - 1) {
                    synchronized (GrblSerialService.this) {
                        jogCancelBurstActive = false;
                    }
                }
            }, JOG_CANCEL_BURST_INTERVAL_MS * i);
        }
    }

    public void serialWriteString(String s) {
        serialWriteBytes(s.getBytes());
        serialWriteBytes(BYTE_NEWLINE);
        serialCommunicationHandler.obtainMessage(Constants.MESSAGE_WRITE, s.length(), -1, s).sendToTarget();
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
