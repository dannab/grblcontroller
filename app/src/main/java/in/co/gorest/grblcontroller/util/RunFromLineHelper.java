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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Run from line" — ricostruisce lo stato modale del programma GCode leggendo
 * il file dall'inizio fino alla riga immediatamente precedente a quella scelta
 * dall'utente, in modo da sapere in quali condizioni (posizione, unità, piano,
 * sistema di coordinate, modo distanza, feed, mandrino, refrigerante) si trova
 * la macchina prima di ripartire.
 *
 * Con quello stato genera un "preambolo" — la sequenza di comandi GCode da
 * inviare prima di riprendere lo streaming, per riportare il controller nelle
 * condizioni corrette ed (opzionalmente) riposizionare automaticamente la
 * macchina sul punto di ripartenza.
 *
 * La numerazione delle righe è quella reale del file (1-based), identica a
 * quella mostrata nell'editor GCode.
 */
public final class RunFromLineHelper {

    private RunFromLineHelper() {}

    // Token GCode: una lettera seguita da un numero (anche con segno/decimali).
    // Applicato sulla riga già ripulita da commenti e spazi e in maiuscolo.
    private static final Pattern WORD = Pattern.compile("([A-Z])(-?\\d*\\.?\\d+)");

    /**
     * Stato modale del programma calcolato fino (escluso) alla riga di partenza.
     * I campi Double sono null quando il file non li ha mai impostati.
     */
    public static class ProgramState {
        public Double x, y, z;          // posizione di lavoro assoluta a fine riga N-1
        public Double feed;             // ultimo F visto
        public Double spindleSpeed;     // ultimo S visto
        public String units;            // "G20" (inch) o "G21" (mm) o null
        public String plane;            // "G17"/"G18"/"G19" o null
        public String distanceMode;     // "G90" (default) o "G91"
        public String wcs;              // "G54".."G59" (e .1/.2/.3) o null
        public String motionMode;       // "G0"/"G1"/"G2"/"G3" ultimo movimento o null
        public int spindleState;        // 0 spento, 3 = M3 (CW), 4 = M4 (CCW)
        public boolean flood;           // M8 attivo
        public boolean mist;            // M7 attivo

        public int startLine;           // riga (1-based) da cui ripartire
        public int totalLines;          // righe totali nel file
        public boolean lineExists;      // true se il file arriva almeno a startLine

        ProgramState() {
            this.distanceMode = "G90"; // default GRBL: assoluto
            this.spindleState = 0;
        }

        public boolean hasPosition() {
            return x != null || y != null || z != null;
        }
    }

    /**
     * Legge il file fino alla riga {@code startLine - 1} e ne ricava lo stato.
     *
     * @param file      file GCode
     * @param startLine riga (1-based, come nell'editor) da cui si vuole ripartire
     */
    public static ProgramState parseUpToLine(File file, int startLine) throws IOException {
        ProgramState s = new ProgramState();
        s.startLine = startLine;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String raw;
            int lineNo = 0;
            while ((raw = br.readLine()) != null) {
                lineNo++;
                if (lineNo < startLine) {
                    applyLine(raw, s);
                }
            }
            s.totalLines = lineNo;
            s.lineExists = startLine <= lineNo;
        }
        return s;
    }

    /**
     * Applica una singola riga allo stato modale. Due passate per essere
     * indipendenti dall'ordine dei word nella riga: prima i codici modali
     * (G/M, F, S), poi le coordinate X/Y/Z col modo distanza già aggiornato.
     */
    private static void applyLine(String rawLine, ProgramState s) {
        String clean = GcodePreprocessorUtils.removeComment(rawLine);
        clean = GcodePreprocessorUtils.removeWhiteSpace(clean); // toglie spazi e mette MAIUSCOLO
        if (clean.isEmpty()) return;

        boolean machineCoords = false; // G53: coordinate macchina su questa riga -> non tracciare
        Double pendingX = null, pendingY = null, pendingZ = null;

        Matcher m = WORD.matcher(clean);
        while (m.find()) {
            char letter = m.group(1).charAt(0);
            String numStr = m.group(2);
            double val;
            try {
                val = Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                continue;
            }

            switch (letter) {
                case 'G': {
                    // confronto sul valore numerico per gestire G0/G00 ecc.
                    if (val == 20)      s.units = "G20";
                    else if (val == 21) s.units = "G21";
                    else if (val == 17) s.plane = "G17";
                    else if (val == 18) s.plane = "G18";
                    else if (val == 19) s.plane = "G19";
                    else if (val == 90) s.distanceMode = "G90";
                    else if (val == 91) s.distanceMode = "G91";
                    else if (val == 53) machineCoords = true;
                    else if (val == 0)  s.motionMode = "G0";
                    else if (val == 1)  s.motionMode = "G1";
                    else if (val == 2)  s.motionMode = "G2";
                    else if (val == 3)  s.motionMode = "G3";
                    else if (val == 54) s.wcs = "G54";
                    else if (val == 55) s.wcs = "G55";
                    else if (val == 56) s.wcs = "G56";
                    else if (val == 57) s.wcs = "G57";
                    else if (val == 58) s.wcs = "G58";
                    else if (val == 59) s.wcs = "G59";
                    else if (val == 59.1) s.wcs = "G59.1";
                    else if (val == 59.2) s.wcs = "G59.2";
                    else if (val == 59.3) s.wcs = "G59.3";
                    break;
                }
                case 'M': {
                    int mi = (int) val;
                    if (mi == 3)      s.spindleState = 3;
                    else if (mi == 4) s.spindleState = 4;
                    else if (mi == 5) s.spindleState = 0;
                    else if (mi == 7) s.mist = true;
                    else if (mi == 8) s.flood = true;
                    else if (mi == 9) { s.mist = false; s.flood = false; }
                    break;
                }
                case 'F': s.feed = val; break;
                case 'S': s.spindleSpeed = val; break;
                case 'X': pendingX = val; break;
                case 'Y': pendingY = val; break;
                case 'Z': pendingZ = val; break;
                default: break; // I,J,K,P,R,T,L,N,... non influenzano la posizione finale
            }
        }

        // Passata 2: applica le coordinate col modo distanza corrente.
        // Le righe G53 usano coordinate macchina: non aggiornano la posizione
        // di lavoro che stiamo tracciando.
        if (!machineCoords) {
            boolean incremental = "G91".equals(s.distanceMode);
            if (pendingX != null) s.x = incremental ? nz(s.x) + pendingX : pendingX;
            if (pendingY != null) s.y = incremental ? nz(s.y) + pendingY : pendingY;
            if (pendingZ != null) s.z = incremental ? nz(s.z) + pendingZ : pendingZ;
        }
    }

    private static double nz(Double d) { return d == null ? 0.0 : d; }

    /**
     * Costruisce il preambolo: i comandi da inviare prima di riprendere lo
     * streaming dalla riga scelta.
     *
     * @param s                   stato calcolato
     * @param autoMove            true = la macchina si riposiziona da sola
     *                            (safe-Z, rapido XY, discesa Z); false = il
     *                            riposizionamento lo fa l'utente a mano col jog
     * @param safeZ               quota Z di sicurezza (coordinate di lavoro) per
     *                            il sollevamento prima dello spostamento XY
     * @param restoreSpindle      true = riaccende mandrino/refrigerante secondo
     *                            lo stato modale; false = li lascia all'utente
     */
    public static List<String> buildPreamble(ProgramState s, boolean autoMove,
                                             double safeZ, boolean restoreSpindle) {
        List<String> p = new ArrayList<>();

        // 1) Stato modale base (interpretazione corretta delle coordinate)
        if (s.units != null) p.add(s.units);
        if (s.plane != null) p.add(s.plane);
        if (s.wcs != null)   p.add(s.wcs);
        if (s.feed != null)  p.add("F" + fmt(s.feed));

        // 2) Mandrino / refrigerante (prima dello spostamento, così girano
        //    già quando l'utensile rientra nel materiale)
        if (restoreSpindle) {
            if (s.spindleState == 3) p.add("M3 S" + fmt(s.spindleSpeed == null ? 0 : s.spindleSpeed));
            else if (s.spindleState == 4) p.add("M4 S" + fmt(s.spindleSpeed == null ? 0 : s.spindleSpeed));
            if (s.flood) p.add("M8");
            if (s.mist)  p.add("M7");
        }

        // 3) Riposizionamento automatico (sempre in assoluto)
        if (autoMove && s.hasPosition()) {
            p.add("G90");
            p.add("G0 Z" + fmt(safeZ));               // solleva in sicurezza
            StringBuilder xy = new StringBuilder("G0");
            if (s.x != null) xy.append(" X").append(fmt(s.x));
            if (s.y != null) xy.append(" Y").append(fmt(s.y));
            if (xy.length() > 2) p.add(xy.toString()); // rapido su XY
            if (s.z != null) {
                // discesa alla Z target: in lavoro (G1) col feed se disponibile,
                // altrimenti in rapido
                if (s.feed != null) p.add("G1 Z" + fmt(s.z) + " F" + fmt(s.feed));
                else p.add("G0 Z" + fmt(s.z));
            }
        }

        // 4) Ripristina il modo distanza del file (se incrementale) e il motion
        //    mode, così la riga di partenza che eredita lo stato modale funziona.
        if ("G91".equals(s.distanceMode)) p.add("G91");
        if ("G0".equals(s.motionMode) || "G1".equals(s.motionMode)) p.add(s.motionMode);

        return p;
    }

    /** Riepilogo leggibile dello stato, per il dialog di conferma. */
    public static String describe(ProgramState s, String unknownLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("X: ").append(s.x != null ? fmt(s.x) : unknownLabel);
        sb.append("   Y: ").append(s.y != null ? fmt(s.y) : unknownLabel);
        sb.append("   Z: ").append(s.z != null ? fmt(s.z) : unknownLabel).append('\n');
        sb.append(s.units != null ? ("G21".equals(s.units) ? "mm" : "inch") : unknownLabel);
        if (s.wcs != null) sb.append("  ").append(s.wcs);
        if (s.distanceMode != null) sb.append("  ").append("G91".equals(s.distanceMode) ? "incr." : "abs.");
        sb.append('\n');
        sb.append("F: ").append(s.feed != null ? fmt(s.feed) : unknownLabel);
        sb.append("   S: ").append(s.spindleSpeed != null ? fmt(s.spindleSpeed) : unknownLabel);
        String sp = s.spindleState == 3 ? "M3" : s.spindleState == 4 ? "M4" : "off";
        sb.append("   ").append(sp);
        if (s.flood) sb.append(" M8");
        if (s.mist)  sb.append(" M7");
        return sb.toString();
    }

    /** Formatta un numero senza zeri/punto inutili (3 decimali max). */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.format(Locale.US, "%d", (long) v);
        }
        String out = String.format(Locale.US, "%.3f", v);
        // rimuove zeri finali
        out = out.replaceAll("0+$", "").replaceAll("\\.$", "");
        return out;
    }
}
