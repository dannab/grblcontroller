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
 */

package in.co.gorest.grblcontroller.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Controlla un file GCode alla ricerca di "affondi" sospetti.
 *
 * Per ogni movimento (G0/G1/G2/G3) calcola:
 *   - dz   = variazione di Z (negativa = discesa)
 *   - dxy  = sqrt(dx^2 + dy^2)
 *   - angolo discesa = atan2(|dz|, dxy) in gradi
 *       * solo Z (plunge puro)   -> 90°
 *       * solo XY                -> 0°
 *       * rampa lineare 45°      -> 45°
 *
 * Un movimento è segnalato come sospetto se TUTTE queste condizioni sono vere:
 *   - dz < 0                       (è una discesa)
 *   - |dz| >= depthThresholdMm     (è abbastanza profondo)
 *   - angle >= angleThresholdDeg   (è abbastanza ripido)
 *
 * Nota: G2/G3 sono trattati come se fossero spostamenti rettilinei dal punto
 * corrente al punto finale: per il controllo Z è una semplificazione corretta
 * (Z varia comunque linearmente lungo l'arco).
 */
public class GcodeDropChecker {

    private static final String TAG = GcodeDropChecker.class.getSimpleName();

    public static class DropWarning {
        public final int   lineNumber;     // prima riga della corsa di discesa
        public final int   lineNumberEnd;  // ultima riga (== lineNumber se singolo blocco)
        public final int   segments;       // quanti segmenti compongono la corsa
        public final float zBefore;
        public final float zAfter;
        public final float dz;
        public final float angleDeg;

        public DropWarning(int lineNumber, int lineNumberEnd, int segments,
                           float zBefore, float zAfter,
                           float dz, float angleDeg) {
            this.lineNumber    = lineNumber;
            this.lineNumberEnd = lineNumberEnd;
            this.segments      = segments;
            this.zBefore       = zBefore;
            this.zAfter        = zAfter;
            this.dz            = dz;
            this.angleDeg      = angleDeg;
        }
    }

    /**
     * Stato di una "corsa di discesa": catena di segmenti consecutivi tutti
     * in discesa ripida. Tipico degli affondi in cavità nel parallel finishing,
     * dove il CAM spezza la discesa in tanti segmentini ognuno sotto soglia.
     */
    private static class DescentRun {
        int   startLine = -1;
        int   endLine   = -1;
        int   segments  = 0;
        float zStart    = 0;
        float zEnd      = 0;
        float dxySum    = 0;

        boolean isActive() { return startLine >= 0; }

        void start(int line, float zBefore) {
            startLine = line;
            zStart    = zBefore;
            segments  = 0;
            dxySum    = 0;
        }

        void extend(int line, float zAfter, float dxy) {
            endLine = line;
            zEnd    = zAfter;
            dxySum += dxy;
            segments++;
        }

        void reset() { startLine = -1; }

        /** Se la corsa accumulata supera la profondità di soglia, produce il warning. */
        void flushInto(List<DropWarning> warnings, float depthThresholdMm) {
            if (!isActive()) return;
            float cumDz = zEnd - zStart; // negativo
            if (-cumDz >= depthThresholdMm) {
                float angleDeg = (float) Math.toDegrees(Math.atan2(-cumDz, dxySum));
                warnings.add(new DropWarning(startLine, endLine, segments,
                        zStart, zEnd, cumDz, angleDeg));
            }
            reset();
        }
    }

    /**
     * Esegue il check in modo sincrono (chiamare in background thread).
     *
     * Rilevamento a "corsa di discesa": i segmenti consecutivi in discesa
     * ripida (angolo ≥ soglia) vengono accumulati; quando la corsa si
     * interrompe (Z risale, il movimento si appiattisce, o il file finisce)
     * la profondità CUMULATIVA della corsa viene confrontata con la soglia.
     * Così un affondo da 4 mm spezzato dal CAM in 20 segmenti da 0.2 mm
     * viene rilevato come un'unica discesa da 4 mm.
     *
     * @param file               file gcode da analizzare
     * @param depthThresholdMm   profondità minima della corsa di discesa (mm, > 0)
     * @param angleThresholdDeg  angolo minimo di ogni segmento della corsa (gradi, 0..90)
     * @param ignoreRapids       se true, i movimenti G0 (rapidi) non vengono segnalati
     * @return lista di warning (può essere vuota); mai null
     */
    public static List<DropWarning> check(File file,
                                          float depthThresholdMm,
                                          float angleThresholdDeg,
                                          boolean ignoreRapids) {
        List<DropWarning> warnings = new ArrayList<>();
        if (file == null || !file.exists()) return warnings;

        DescentRun run = new DescentRun();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            float x = 0, y = 0, z = 0;
            boolean isAbsolute = true;
            int modalMotion = 0; // modale attivo: 0=G0, 1=G1, 2=G2, 3=G3
            int lineNo = 0;

            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim().toUpperCase();
                if (line.isEmpty()) continue;

                int ci = line.indexOf('(');
                if (ci >= 0) line = line.substring(0, ci).trim();
                ci = line.indexOf(';');
                if (ci >= 0) line = line.substring(0, ci).trim();
                if (line.isEmpty()) continue;

                if (containsGCode(line, 90)) isAbsolute = true;
                if (containsGCode(line, 91)) isAbsolute = false;

                // Aggiorna il modale di movimento: una riga "Z-5" senza G
                // eredita l'ultimo G0/G1/G2/G3 visto
                if (containsGCode(line, 0)) modalMotion = 0;
                if (containsGCode(line, 1)) modalMotion = 1;
                if (containsGCode(line, 2)) modalMotion = 2;
                if (containsGCode(line, 3)) modalMotion = 3;

                float nx = parseCoord(line, 'X', x);
                float ny = parseCoord(line, 'Y', y);
                float nz = parseCoord(line, 'Z', z);

                if (!isAbsolute) { nx += x; ny += y; nz += z; }

                boolean hasCoords = line.contains("X") || line.contains("Y") || line.contains("Z");
                boolean posChanged = (nx != x || ny != y || nz != z);
                boolean hasMotion  = containsGCode(line, 0) || containsGCode(line, 1)
                                  || containsGCode(line, 2) || containsGCode(line, 3);

                if (hasMotion || (hasCoords && posChanged)) {
                    float dz  = nz - z;
                    float dx  = nx - x;
                    float dy  = ny - y;
                    float dxy = (float) Math.sqrt(dx * dx + dy * dy);

                    boolean isRapid = (modalMotion == 0);

                    // Il segmento appartiene a una corsa di discesa se:
                    // scende, è ripido, e non è un G0 da ignorare
                    boolean steepDescent = false;
                    if (dz < 0 && !(ignoreRapids && isRapid)) {
                        // angolo rispetto al piano XY:
                        // - dxy=0 (plunge puro) -> 90°
                        // - dz piccolo, dxy grande -> ~0°
                        float angleDeg = (float) Math.toDegrees(Math.atan2(-dz, dxy));
                        steepDescent = angleDeg >= angleThresholdDeg;
                    }

                    if (steepDescent) {
                        if (!run.isActive()) run.start(lineNo, z);
                        run.extend(lineNo, nz, dxy);
                    } else {
                        // Corsa interrotta: valuta la profondità cumulativa
                        run.flushInto(warnings, depthThresholdMm);
                    }

                    x = nx; y = ny; z = nz;
                }
            }

            // Corsa eventualmente ancora aperta a fine file
            run.flushInto(warnings, depthThresholdMm);

        } catch (IOException e) {
            Log.e(TAG, "Errore lettura file: " + e.getMessage());
        }

        return warnings;
    }

    // -------------------------------------------------------------------------
    // Parsing helpers — stessa logica di GcodeRenderer / vecchio ZAnalyzer
    // -------------------------------------------------------------------------

    private static boolean containsGCode(String line, int code) {
        String pat1 = "G" + code;
        String pat2 = "G0" + code;
        for (String pat : new String[]{pat1, pat2}) {
            int idx = line.indexOf(pat);
            while (idx >= 0) {
                int after = idx + pat.length();
                if (after >= line.length() || !Character.isDigit(line.charAt(after))) {
                    if (idx == 0 || !Character.isDigit(line.charAt(idx - 1))) {
                        return true;
                    }
                }
                idx = line.indexOf(pat, idx + 1);
            }
        }
        return false;
    }

    private static float parseCoord(String line, char axis, float defaultVal) {
        int idx = line.indexOf(axis);
        if (idx < 0) return defaultVal;
        int start = idx + 1;
        int end = start;
        while (end < line.length() &&
               (Character.isDigit(line.charAt(end)) ||
                line.charAt(end) == '.' ||
                line.charAt(end) == '-')) {
            end++;
        }
        if (start == end) return defaultVal;
        try {
            return Float.parseFloat(line.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
