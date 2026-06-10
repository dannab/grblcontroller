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
        public final int   lineNumber;
        public final float zBefore;
        public final float zAfter;
        public final float dz;
        public final float angleDeg;

        public DropWarning(int lineNumber, float zBefore, float zAfter,
                           float dz, float angleDeg) {
            this.lineNumber = lineNumber;
            this.zBefore    = zBefore;
            this.zAfter     = zAfter;
            this.dz         = dz;
            this.angleDeg   = angleDeg;
        }
    }

    /**
     * Esegue il check in modo sincrono (chiamare in background thread).
     *
     * @param file               file gcode da analizzare
     * @param depthThresholdMm   profondità minima della discesa per scatenare warning (mm, > 0)
     * @param angleThresholdDeg  angolo minimo della discesa per scatenare warning (gradi, 0..90)
     * @return lista di warning (può essere vuota); mai null
     */
    public static List<DropWarning> check(File file,
                                          float depthThresholdMm,
                                          float angleThresholdDeg) {
        List<DropWarning> warnings = new ArrayList<>();
        if (file == null || !file.exists()) return warnings;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            float x = 0, y = 0, z = 0;
            boolean isAbsolute = true;
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

                    // Calcola angolo solo se è una discesa
                    if (dz < 0) {
                        float absDz = -dz;
                        // angolo rispetto al piano XY:
                        // - dxy=0 (plunge puro) -> 90°
                        // - dz piccolo, dxy grande -> ~0°
                        float angleDeg = (float) Math.toDegrees(Math.atan2(absDz, dxy));

                        if (absDz >= depthThresholdMm && angleDeg >= angleThresholdDeg) {
                            warnings.add(new DropWarning(lineNo, z, nz, dz, angleDeg));
                        }
                    }

                    x = nx; y = ny; z = nz;
                }
            }
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
