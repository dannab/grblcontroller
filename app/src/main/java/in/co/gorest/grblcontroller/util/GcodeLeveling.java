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

            // 2. Calcolo geometrico del piano (AX + BY + CZ + D = 0)
            double x1 = points[0][0], y1 = points[0][1], z1 = points[0][2];
            double x2 = points[1][0], y2 = points[1][1], z2 = points[1][2];
            double x3 = points[2][0], y3 = points[2][1], z3 = points[2][2];

            double v1x = x2 - x1, v1y = y2 - y1, v1z = z2 - z1;
            double v2x = x3 - x1, v2y = y3 - y1, v2z = z3 - z1;

            double A = v1y * v2z - v1z * v2y;
            double B = v1z * v2x - v1x * v2z;
            double C = v1x * v2y - v1y * v2x;
            double D = -(A * x1 + B * y1 + C * z1);

            if (Math.abs(C) < 0.000001) {
                callback.onError("Errore geometrico: I 3 punti inseriti sono allineati!");
                return;
            }

            // 3. Elaborazione delle coordinate G-Code
            List<String> outputLines = new ArrayList<>();
            Pattern patternX = Pattern.compile("X\\s*([-\\d.]+)");
            Pattern patternY = Pattern.compile("Y\\s*([-\\d.]+)");
            Pattern patternZ = Pattern.compile("Z\\s*([-\\d.]+)");

            double currentX = 0.0;
            double currentY = 0.0;

            try (BufferedReader br = new BufferedReader(new FileReader(gcodeFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String upperLine = line.toUpperCase().trim();

                    if (upperLine.startsWith(";") || upperLine.startsWith("(") || upperLine.startsWith("M")) {
                        outputLines.add(line);
                        continue;
                    }

                    if (upperLine.contains("G0") || upperLine.contains("G1") ||
                            upperLine.contains("X")  || upperLine.contains("Y") ||
                            upperLine.contains("Z")  || upperLine.contains("F")) {

                        Matcher mX = patternX.matcher(upperLine);
                        Matcher mY = patternY.matcher(upperLine);
                        Matcher mZ = patternZ.matcher(upperLine);

                        if (mX.find()) currentX = Double.parseDouble(mX.group(1));
                        if (mY.find()) currentY = Double.parseDouble(mY.group(1));

                        if (mZ.find()) {
                            double originalZ = Double.parseDouble(mZ.group(1));

                            // Formula del piano inclinato
                            double zCompensation = -(A * currentX + B * currentY + D) / C;
                            double newZ = originalZ + zCompensation;

                            line = line.replaceAll("(?i)Z\\s*([-\\d.]+)", String.format(Locale.US, "Z%.3f", newZ));
                        }
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