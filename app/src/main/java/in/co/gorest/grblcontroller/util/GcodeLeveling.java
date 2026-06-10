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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GcodeLeveling {

    public interface LevelingCallback {
        void onSuccess(File leveledFile);
        void onError(String error);
    }

    /**
     * Legge il file G-Code originale, calcola la compensazione basata sui 3 punti
     * salvati in points.txt e scrive un nuovo file con suffisso "_leveled".
     */
    public static void applyAutolevel(final File pointsFile, final File gcodeFile, final LevelingCallback callback) {

        if (pointsFile == null || !pointsFile.exists()) {
            callback.onError("File points.txt non trovato! Esegui prima il probing dei 3 punti.");
            return;
        }
        if (gcodeFile == null || !gcodeFile.exists()) {
            callback.onError("Nessun file G-Code di origine valido selezionato.");
            return;
        }

        new Thread(() -> {
            double[][] points = new double[3][3];
            int lineCount = 0;

            // 1. Lettura dei 3 punti di calibrazione
            try (BufferedReader br = new BufferedReader(new FileReader(pointsFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;

                    if (lineCount >= 3) {
                        lineCount++;
                        break;
                    }

                    trimmed = trimmed.replaceAll(",", " ");
                    String[] parts = trimmed.split("\\s+");

                    if (parts.length >= 3) {
                        points[lineCount][0] = Double.parseDouble(parts[0]); // X
                        points[lineCount][1] = Double.parseDouble(parts[1]); // Y
                        points[lineCount][2] = Double.parseDouble(parts[2]); // Z
                        lineCount++;
                    }
                }
            } catch (Exception e) {
                callback.onError("Errore lettura points.txt: " + e.getMessage());
                return;
            }

            if (lineCount != 3) {
                callback.onError("Il file points.txt contiene " + lineCount + " punti. Ne servono esattamente 3.");
                return;
            }


// 2. Calcolo geometrico del piano inclinato (Equazione: Ax + By + Cz + D = 0)
            double x1 = points[0][0], y1 = points[0][1], z1 = points[0][2];
            double x2 = points[1][0], y2 = points[1][1], z2 = points[1][2];
            double x3 = points[2][0], y3 = points[2][1], z3 = points[2][2];

// Vettori
            double v1x = x2 - x1, v1y = y2 - y1, v1z = z2 - z1;
            double v2x = x3 - x1, v2y = y3 - y1, v2z = z3 - z1;

// Prodotto vettoriale per trovare la normale (A, B, C)
            double A = (v1y * v2z) - (v1z * v2y);
            double B = (v1z * v2x) - (v1x * v2z);
            double C = (v1x * v2y) - (v1y * v2x);

// Calcolo corretto del termine noto D
            double D = -(A * x1 + B * y1 + C * z1);

            if (Math.abs(C) < 0.000001) {
                callback.onError("Errore geometrico: I 3 punti di probing sono allineati!");
                return;
            }

// 3. Elaborazione e Iniezione G-Code
            List<String> outputLines = new ArrayList<>();
            Pattern patternX = Pattern.compile("X\\s*([-\\d.]+)");
            Pattern patternY = Pattern.compile("Y\\s*([-\\d.]+)");
            Pattern patternZ = Pattern.compile("Z\\s*([-\\d.]+)");

            double currentX = 0.0;
            double currentY = 0.0;
            double currentZ = 0.0; // Tiene traccia della Z di lavoro teorica

            try (BufferedReader br = new BufferedReader(new FileReader(gcodeFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String upperLine = line.toUpperCase().trim();

                    // Salta commenti e comandi speciali
                    if (upperLine.startsWith(";") || upperLine.startsWith("(") || upperLine.startsWith("M")) {
                        outputLines.add(line);
                        continue;
                    }

                    // Intercettiamo i blocchi di movimento reali
                    if (upperLine.contains("G0") || upperLine.contains("G1") ||
                            upperLine.contains("G2") || upperLine.contains("G3") ||
                            upperLine.contains("X")  || upperLine.contains("Y")  ||
                            upperLine.contains("Z")) {

                        Matcher mX = patternX.matcher(upperLine);
                        Matcher mY = patternY.matcher(upperLine);
                        Matcher mZ = patternZ.matcher(upperLine);

                        boolean haX = mX.find();
                        boolean haY = mY.find();
                        boolean haZ = mZ.find();

                        // Aggiorna lo stato modale delle coordinate teoriche del file
                        if (haX) currentX = Double.parseDouble(mX.group(1));
                        if (haY) currentY = Double.parseDouble(mY.group(1));
                        if (haZ) currentZ = Double.parseDouble(mZ.group(1));

                        // Calcola la quota del piano inclinato in questo specifico punto (X, Y)
                        double zTargetPlane = -(A * currentX + B * currentY + D) / C;

                        // La Z reale finale è la quota teorica del file + la pendenza del piano inclinato
                        double realZ = currentZ + zTargetPlane;

                        // --- RICOSTRUZIONE DELLA RIGA CON INIEZIONE ---
                        // Puliamo la riga da un'eventuale Z vecchia per non duplicarla
                        String cleanedLine = line.replaceAll("(?i)Z\\s*([-\\d.]+)", "").trim();

                        // Cerchiamo dove inserire la nuova Z. La posizione ideale è dopo la X o dopo la Y.
                        // Se la riga ha X o Y, inseriamo la Z subito dopo di loro.
                        if (cleanedLine.matches(".*[XXYFillGg].*")) {
                            // Troviamo l'ultima coordinata (X o Y) e appendiamo la Z compensata
                            if (cleanedLine.contains("Y") || cleanedLine.contains("y")) {
                                cleanedLine = cleanedLine.replaceAll("(?i)(Y\\s*[-\\d.]+)", "$1 " + String.format(Locale.US, "Z%.3f", realZ));
                            } else if (cleanedLine.contains("X") || cleanedLine.contains("x")) {
                                cleanedLine = cleanedLine.replaceAll("(?i)(X\\s*[-\\d.]+)", "$1 " + String.format(Locale.US, "Z%.3f", realZ));
                            } else {
                                // Se è un comando di movimento (es. G00/G01) senza X e Y ma con cambio Z
                                cleanedLine = cleanedLine + " " + String.format(Locale.US, "Z%.3f", realZ);
                            }
                        } else {
                            // Fallback di sicurezza se la riga è strana
                            cleanedLine = cleanedLine + " " + String.format(Locale.US, "Z%.3f", realZ);
                        }

                        // Rimuove eventuali doppi spazi generati dalla pulizia
                        line = cleanedLine.replaceAll("\\s+", " ");
                    }

                    outputLines.add(line);
                }

                // 4. Generazione del file con il nuovo nome (_leveled)
                String originalPath = gcodeFile.getAbsolutePath();
                String newPath;
                int dotIndex = originalPath.lastIndexOf(".");
                if (dotIndex != -1) {
                    newPath = originalPath.substring(0, dotIndex) + "_leveled" + originalPath.substring(dotIndex);
                } else {
                    newPath = originalPath + "_leveled";
                }
                File leveledFile = new File(newPath);

                // 5. Scrittura del file finale
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(leveledFile))) {
                    for (String l : outputLines) {
                        bw.write(l);
                        bw.newLine();
                    }
                    bw.flush();
                }

                callback.onSuccess(leveledFile);

            } catch (Exception e) {
                callback.onError("Errore durante l'elaborazione del file: " + e.getMessage());
            }
        }).start();
    }
}