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

import in.co.gorest.grblcontroller.GrblController;
import in.co.gorest.grblcontroller.R;

public class GcodeLeveling {

    /** Scorciatoia per le stringhe localizzate in una utility senza Context. */
    private static String str(int resId, Object... args) {
        return GrblController.getInstance().getString(resId, args);
    }

    public interface LevelingCallback {
        void onSuccess(File leveledFile);
        void onError(String error);
    }

    /**
     * Legge il file G-Code originale, calcola la compensazione basata sui 3 punti
     * salvati in points.txt e scrive un nuovo file con suffisso "_leveled".
     *
     * Convenzione: nel file G-Code la superficie del pezzo deve essere a Z0
     * (standard CAM: Z0 = piano di lavoro). I 3 punti di probing possono invece
     * trovarsi a QUALSIASI quota di lavoro, positiva o negativa: la compensazione
     * riporta la Z0 del file esattamente sul piano reale rilevato (quota + inclinazione).
     * Probing e lavorazione devono avvenire nello stesso sistema di coordinate,
     * senza azzerare di nuovo la Z tra le due fasi.
     */
    public static void applyAutolevel(final File pointsFile, final File gcodeFile, final LevelingCallback callback) {

        if (pointsFile == null || !pointsFile.exists()) {
            callback.onError(str(R.string.text_leveling_no_points_file));
            return;
        }
        if (gcodeFile == null || !gcodeFile.exists()) {
            callback.onError(str(R.string.text_leveling_no_source));
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
                callback.onError(str(R.string.text_leveling_points_read_error, e.getMessage()));
                return;
            }

            if (lineCount != 3) {
                callback.onError(str(R.string.text_leveling_wrong_point_count, lineCount));
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
                callback.onError(str(R.string.text_leveling_collinear));
                return;
            }

// 3. Elaborazione e Iniezione G-Code
            List<String> outputLines = new ArrayList<>();

            // Parsing a parole (lettera + numero): evita i falsi positivi del
            // vecchio match a sottostringa ("G17" conteneva "G1", "G21" conteneva
            // "G2", ecc.) che iniettavano Z anche nel preambolo del file.
            Pattern wordPattern = Pattern.compile("(?i)([A-Z])\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+))");
            Pattern zWordPattern = Pattern.compile("(?i)Z\\s*[+-]?(?:\\d+\\.?\\d*|\\.\\d+)");
            Pattern parenCommentPattern = Pattern.compile("\\([^)]*\\)");

            double currentX = 0.0;
            double currentY = 0.0;
            double currentZ = 0.0;       // Z di lavoro teorica del file
            boolean zKnown = false;      // true dopo la prima Z esplicita in G90
            boolean absoluteMode = true; // G90 (default GRBL) / G91

            try (BufferedReader br = new BufferedReader(new FileReader(gcodeFile))) {
                String line;
                outputLines.add("(Livellamento 3 punti applicato - Z0 del file riportata sul piano rilevato)");

                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();

                    // Righe vuote, commenti puri, '%' e comandi M passano invariati
                    if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("(")
                            || trimmed.startsWith("%") || trimmed.toUpperCase().startsWith("M")) {
                        outputLines.add(line);
                        continue;
                    }

                    // Separa il codice dai commenti inline (';' e parentesi)
                    String code = line;
                    String semicolonComment = "";
                    int semiIdx = code.indexOf(';');
                    if (semiIdx >= 0) {
                        semicolonComment = code.substring(semiIdx);
                        code = code.substring(0, semiIdx);
                    }
                    StringBuilder parenComments = new StringBuilder();
                    Matcher pc = parenCommentPattern.matcher(code);
                    while (pc.find()) parenComments.append(" ").append(pc.group());
                    code = pc.replaceAll(" ");

                    // Analizza le parole della riga
                    boolean isProtected = false; // G10/G28/G30/G38.x/G43.1/G53/G92: mai toccare
                    boolean hasX = false, hasY = false, hasZ = false;
                    double wordX = 0, wordY = 0, wordZ = 0;
                    Matcher wm = wordPattern.matcher(code);
                    while (wm.find()) {
                        char letter = Character.toUpperCase(wm.group(1).charAt(0));
                        double value = Double.parseDouble(wm.group(2));
                        if (letter == 'G') {
                            if (value == 90.0) absoluteMode = true;
                            else if (value == 91.0) absoluteMode = false;
                            else if (value == 10.0 || value == 28.0 || value == 30.0
                                    || value == 53.0 || value == 43.1
                                    || (value >= 38.0 && value < 39.0)
                                    || (value >= 92.0 && value < 93.0)) {
                                isProtected = true;
                            }
                        } else if (letter == 'X') { hasX = true; wordX = value; }
                        else if (letter == 'Y') { hasY = true; wordY = value; }
                        else if (letter == 'Z') { hasZ = true; wordZ = value; }
                    }

                    // Comandi di homing, coordinate macchina, probing e offset:
                    // passano invariati. Dopo di essi la Z di lavoro non è più
                    // affidabile: la compensazione delle righe solo-X/Y riprende
                    // alla prossima Z esplicita del file.
                    if (isProtected) {
                        zKnown = false;
                        outputLines.add(line);
                        continue;
                    }

                    // Modalità incrementale (G91): gli incrementi non dipendono
                    // dalla quota del piano, quindi la riga passa invariata.
                    // Aggiorniamo comunque la posizione teorica.
                    if (!absoluteMode) {
                        if (hasX) currentX += wordX;
                        if (hasY) currentY += wordY;
                        if (hasZ) currentZ += wordZ;
                        outputLines.add(line);
                        continue;
                    }

                    // Modalità assoluta: aggiorna lo stato modale
                    if (hasX) currentX = wordX;
                    if (hasY) currentY = wordY;
                    if (hasZ) { currentZ = wordZ; zKnown = true; }

                    // Compensa solo le righe che muovono davvero gli assi:
                    // - righe con Z esplicita: sempre
                    // - righe solo X/Y: solo se la Z teorica è nota
                    // Righe senza parole asse ("G17 G21 G90", "G1 F100", "G4 P500",
                    // "T1", "S12000") passano invariate.
                    if (!hasZ && !(zKnown && (hasX || hasY))) {
                        outputLines.add(line);
                        continue;
                    }

                    // Quota del piano rilevato in questo punto (X, Y)
                    double zTargetPlane = -(A * currentX + B * currentY + D) / C;

                    // La Z reale è la Z teorica del file (riferita a Z0 = superficie)
                    // più la quota assoluta del piano in quel punto
                    double realZ = currentZ + zTargetPlane;
                    String zWord = String.format(Locale.US, "Z%.3f", realZ);

                    // Sostituisce la Z esistente al suo posto, oppure la appende
                    String newCode;
                    Matcher zm = zWordPattern.matcher(code);
                    if (zm.find()) {
                        newCode = code.substring(0, zm.start()) + zWord + code.substring(zm.end());
                    } else {
                        newCode = code.trim() + " " + zWord;
                    }

                    line = (newCode + parenComments + (semicolonComment.isEmpty() ? "" : " " + semicolonComment))
                            .replaceAll("\\s+", " ").trim();
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
                callback.onError(str(R.string.text_leveling_processing_error, e.getMessage()));
            }
        }).start();
    }
}