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
package in.co.gorest.grblcontroller.util;

import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import in.co.gorest.grblcontroller.model.Constants;

/**
 * Flow-control del buffer RX seriale di GRBL ("character counting protocol"):
 * tiene il conto dei byte in volo verso il controller e blocca l'invio quando
 * il buffer (tipicamente 128 byte, meno 3 di margine) sarebbe superato; ogni
 * "ok"/"error" ricevuto libera lo spazio del comando più vecchio.
 *
 * Era duplicato in FileStreamerIntentService e nei pulsanti custom di
 * JoggingTabFragment; ora entrambi usano questa classe.
 *
 * Threading: {@link #onCommandCompleted()} può arrivare da qualsiasi thread
 * (subscriber EventBus); tutti gli altri metodi vanno chiamati dal solo
 * thread che sta facendo lo streaming, come nel codice originale.
 */
public class SerialRxBufferThrottle {

    /** Margine di sicurezza sottratto alla dimensione del buffer RX. */
    private static final int SAFETY_MARGIN = 3;

    private int maxBuffer;
    private int usedBuffer = 0;
    private final LinkedList<Integer> activeCommandSizes = new LinkedList<>();
    private final BlockingQueue<Integer> completedCommands = new ArrayBlockingQueue<>(Constants.DEFAULT_SERIAL_RX_BUFFER);

    public SerialRxBufferThrottle() {
        setSerialRxBufferSize(Constants.DEFAULT_SERIAL_RX_BUFFER);
    }

    /**
     * Imposta la dimensione del buffer RX del controller (da $I / compile time
     * options). Valori non positivi vengono ignorati e resta il default.
     */
    public void setSerialRxBufferSize(int serialRxBufferSize) {
        if (serialRxBufferSize > 0) this.maxBuffer = serialRxBufferSize - SAFETY_MARGIN;
    }

    /** Da chiamare per ogni "ok" (o errore) ricevuto da GRBL. */
    public void onCommandCompleted() {
        try {
            completedCommands.put(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Blocca finché nel buffer non c'è spazio per {@code commandSize} byte.
     * Non prenota lo spazio: chiamare {@link #commit(int)} dopo aver deciso
     * di inviare davvero il comando.
     *
     * @return false se il thread è stato interrotto durante l'attesa
     */
    public boolean waitForSpace(int commandSize) {
        while (maxBuffer < usedBuffer + commandSize) {
            if (!consumeOneCompletion()) return false;
        }
        return true;
    }

    /** Registra un comando di {@code commandSize} byte come in volo. */
    public void commit(int commandSize) {
        activeCommandSizes.offer(commandSize);
        usedBuffer += commandSize;
    }

    /**
     * Blocca finché tutti i comandi in volo non sono stati confermati
     * (buffer completamente svuotato).
     */
    public void waitUntilDrained() {
        while (usedBuffer > 0) {
            if (!consumeOneCompletion()) return;
        }
    }

    /**
     * Attende una singola conferma da GRBL, per la modalità single-step
     * (un comando alla volta, senza contare i byte).
     *
     * @return false se il thread è stato interrotto durante l'attesa
     */
    public boolean awaitSingleCompletion() {
        try {
            completedCommands.take();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Azzera lo stato (da chiamare a inizio/fine job). */
    public void clear() {
        usedBuffer = 0;
        activeCommandSizes.clear();
        completedCommands.clear();
    }

    /** Consuma una conferma e libera lo spazio del comando più vecchio. */
    private boolean consumeOneCompletion() {
        try {
            completedCommands.take();
            if (!activeCommandSizes.isEmpty()) usedBuffer -= activeCommandSizes.removeFirst();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
