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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import in.co.gorest.grblcontroller.GrblController;
import in.co.gorest.grblcontroller.R;

public class GcodeLeveling {

    private static final String POINTS_CONTEXT_PREFIX = ";GRBLCONTROLLER_LEVELING_V2";
    private static final Pattern POINTS_CONTEXT_PATTERN = Pattern.compile(
            "(?i)^;\\s*GRBLCONTROLLER_LEVELING_V2\\s+WCS=(G(?:5[4-9]|59\\.[123]))\\s+UNITS=(G2[01])\\s*$");
    private static final Pattern WORD_PATTERN = Pattern.compile(
            "(?i)([A-Z])\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final Pattern Z_WORD_PATTERN = Pattern.compile(
            "(?i)Z\\s*[+-]?(?:\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern PAREN_COMMENT_PATTERN = Pattern.compile("\\([^)]*\\)");

    /** Scorciatoia per le stringhe localizzate in una utility senza Context. */
    private static String str(int resId, Object... args) {
        return GrblController.getInstance().getString(resId, args);
    }

    public interface LevelingCallback {
        void onSuccess(File leveledFile);
        void onError(String error);
    }

    /** Informazioni minime usate dalla UI per aggiungere un punto in sicurezza. */
    public static final class PointsFileStatus {
        private final int pointCount;
        private final String coordinateSystem;
        private final String units;

        private PointsFileStatus(int pointCount, String coordinateSystem, String units) {
            this.pointCount = pointCount;
            this.coordinateSystem = coordinateSystem;
            this.units = units;
        }

        public int getPointCount() {
            return pointCount;
        }

        public String getCoordinateSystem() {
            return coordinateSystem;
        }

        public String getUnits() {
            return units;
        }

        public boolean hasContext() {
            return coordinateSystem != null && units != null;
        }
    }

    enum ErrorCode {
        POINT_CONTEXT_MISSING,
        POINT_CONTEXT_INVALID,
        WRONG_POINT_COUNT,
        COLLINEAR_POINTS,
        UNSUPPORTED_UNITS,
        WCS_MISMATCH,
        COORDINATE_OFFSET_UNSUPPORTED,
        ARC_UNSUPPORTED,
        INITIAL_XY_UNKNOWN
    }

    static final class LevelingException extends Exception {
        final ErrorCode code;
        final int lineNumber;
        final String value;

        LevelingException(ErrorCode code) {
            this(code, 0, null);
        }

        LevelingException(ErrorCode code, int lineNumber, String value) {
            super(code.name());
            this.code = code;
            this.lineNumber = lineNumber;
            this.value = value;
        }
    }

    private static final class PointData {
        final double[][] points;
        final String coordinateSystem;
        final String units;

        PointData(double[][] points, String coordinateSystem, String units) {
            this.points = points;
            this.coordinateSystem = coordinateSystem;
            this.units = units;
        }
    }

    /** Crea la riga di contesto che precede i tre punti manuali. */
    public static String createPointsContextHeader(String coordinateSystem, String units) {
        return String.format(Locale.US, "%s WCS=%s UNITS=%s",
                POINTS_CONTEXT_PREFIX,
                coordinateSystem.toUpperCase(Locale.US),
                units.toUpperCase(Locale.US));
    }

    /**
     * Conta i punti e legge il contesto senza modificare il file. Le vecchie liste
     * di sole coordinate risultano intenzionalmente prive di contesto.
     */
    public static PointsFileStatus inspectPointsText(String text) {
        int pointCount = 0;
        String coordinateSystem = null;
        String units = null;

        if (text == null) text = "";
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher contextMatcher = POINTS_CONTEXT_PATTERN.matcher(trimmed);
            if (contextMatcher.matches()) {
                coordinateSystem = contextMatcher.group(1).toUpperCase(Locale.US);
                units = contextMatcher.group(2).toUpperCase(Locale.US);
            } else if (!isComment(trimmed)) {
                pointCount++;
            }
        }
        return new PointsFileStatus(pointCount, coordinateSystem, units);
    }

    /**
     * Legge il file G-Code originale, calcola la compensazione basata sui 3 punti
     * salvati in points.txt e scrive un nuovo file con suffisso "_leveled".
     *
     * Il formato supportato è volutamente ristretto e deterministico: millimetri,
     * stesso WCS dei punti e traiettorie G0/G1. G91 viene compensato usando la
     * variazione del piano; G2/G3 devono essere linearizzati dal CAM.
     */
    public static void applyAutolevel(final File pointsFile, final File gcodeFile,
                                      final LevelingCallback callback) {
        if (pointsFile == null || !pointsFile.exists()) {
            callback.onError(str(R.string.text_leveling_no_points_file));
            return;
        }
        if (gcodeFile == null || !gcodeFile.exists()) {
            callback.onError(str(R.string.text_leveling_no_source));
            return;
        }

        new Thread(() -> {
            try {
                List<String> pointLines = readLines(pointsFile);
                List<String> gcodeLines = readLines(gcodeFile);
                List<String> outputLines = transform(pointLines, gcodeLines);
                File leveledFile = getLeveledFile(gcodeFile);

                try (BufferedWriter bw = new BufferedWriter(new FileWriter(leveledFile))) {
                    for (String line : outputLines) {
                        bw.write(line);
                        bw.newLine();
                    }
                    bw.flush();
                }
                callback.onSuccess(leveledFile);
            } catch (LevelingException e) {
                callback.onError(localizedError(e));
            } catch (Exception e) {
                callback.onError(str(R.string.text_leveling_processing_error, e.getMessage()));
            }
        }, "gcode-leveling").start();
    }

    /** Trasformazione pura, visibile al package per i test JVM. */
    static List<String> transform(List<String> pointLines, List<String> gcodeLines)
            throws LevelingException {
        PointData pointData = parsePoints(pointLines);
        double[][] points = pointData.points;

        double x1 = points[0][0], y1 = points[0][1], z1 = points[0][2];
        double x2 = points[1][0], y2 = points[1][1], z2 = points[1][2];
        double x3 = points[2][0], y3 = points[2][1], z3 = points[2][2];

        double v1x = x2 - x1, v1y = y2 - y1, v1z = z2 - z1;
        double v2x = x3 - x1, v2y = y3 - y1, v2z = z3 - z1;
        double a = (v1y * v2z) - (v1z * v2y);
        double b = (v1z * v2x) - (v1x * v2z);
        double c = (v1x * v2y) - (v1y * v2x);
        double d = -(a * x1 + b * y1 + c * z1);

        if (Math.abs(c) < 0.000001) {
            throw new LevelingException(ErrorCode.COLLINEAR_POINTS);
        }

        final double slopeX = -a / c;
        final double slopeY = -b / c;
        final double intercept = -d / c;

        List<String> outputLines = new ArrayList<>();
        outputLines.add("(Livellamento 3 punti applicato - solo G0/G1, archi linearizzati dal CAM)");
        // Rende il file indipendente dallo stato modale lasciato da una lavorazione precedente.
        outputLines.add("G21 G90 G0 " + pointData.coordinateSystem);

        double currentX = 0.0;
        double currentY = 0.0;
        double currentZ = 0.0;
        boolean xKnown = false;
        boolean yKnown = false;
        boolean zKnown = false;
        boolean absoluteMode = true;
        int motionMode = 0;

        for (int index = 0; index < gcodeLines.size(); index++) {
            String line = gcodeLines.get(index);
            int lineNumber = index + 1;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("(")
                    || trimmed.startsWith("%")) {
                outputLines.add(line);
                continue;
            }

            String code = line;
            String semicolonComment = "";
            int semiIdx = code.indexOf(';');
            if (semiIdx >= 0) {
                semicolonComment = code.substring(semiIdx);
                code = code.substring(0, semiIdx);
            }
            StringBuilder parenComments = new StringBuilder();
            Matcher pc = PAREN_COMMENT_PATTERN.matcher(code);
            while (pc.find()) parenComments.append(" ").append(pc.group());
            code = pc.replaceAll(" ");

            boolean isProtected = false;
            boolean invalidatesAllAxes = false;
            boolean invalidatesZ = false;
            boolean hasX = false, hasY = false, hasZ = false;
            double wordX = 0.0, wordY = 0.0, wordZ = 0.0;

            Matcher wm = WORD_PATTERN.matcher(code);
            while (wm.find()) {
                char letter = Character.toUpperCase(wm.group(1).charAt(0));
                double value = Double.parseDouble(wm.group(2));
                if (letter == 'G') {
                    if (same(value, 0.0)) motionMode = 0;
                    else if (same(value, 1.0)) motionMode = 1;
                    else if (same(value, 2.0) || same(value, 3.0)) {
                        throw new LevelingException(ErrorCode.ARC_UNSUPPORTED, lineNumber, null);
                    } else if (same(value, 20.0)) {
                        throw new LevelingException(ErrorCode.UNSUPPORTED_UNITS, lineNumber, "G20");
                    } else if (same(value, 21.0)) {
                        // G21 è l'unica unità ammessa e viene anche imposta nell'intestazione.
                    } else if (same(value, 90.0)) {
                        absoluteMode = true;
                    } else if (same(value, 91.0)) {
                        absoluteMode = false;
                    } else {
                        String lineWcs = coordinateSystemFor(value);
                        if (lineWcs != null && !pointData.coordinateSystem.equals(lineWcs)) {
                            throw new LevelingException(
                                    ErrorCode.WCS_MISMATCH, lineNumber, lineWcs);
                        }

                        if ((lineWcs == null && value >= 54.0 && value < 60.0)
                                || same(value, 10.0) || same(value, 52.0)
                                || (value >= 92.0 && value < 93.0)) {
                            throw new LevelingException(
                                    ErrorCode.COORDINATE_OFFSET_UNSUPPORTED, lineNumber,
                                    "G" + wm.group(2));
                        }
                        if ((value >= 28.0 && value < 29.0)
                                || (value >= 30.0 && value < 31.0)
                                || same(value, 53.0)
                                || (value >= 38.0 && value < 39.0)) {
                            isProtected = true;
                            invalidatesAllAxes = true;
                        } else if (same(value, 43.1)) {
                            isProtected = true;
                            invalidatesZ = true;
                        }
                    }
                } else if (letter == 'X') {
                    hasX = true;
                    wordX = value;
                } else if (letter == 'Y') {
                    hasY = true;
                    wordY = value;
                } else if (letter == 'Z') {
                    hasZ = true;
                    wordZ = value;
                }
            }

            if (isProtected) {
                if (invalidatesAllAxes) {
                    xKnown = false;
                    yKnown = false;
                    zKnown = false;
                } else if (invalidatesZ) {
                    zKnown = false;
                }
                outputLines.add(line);
                continue;
            }

            boolean isLinearMovement = (motionMode == 0 || motionMode == 1)
                    && (hasX || hasY || hasZ);
            if (!isLinearMovement) {
                outputLines.add(line);
                continue;
            }

            if (!absoluteMode) {
                double deltaPlane = slopeX * (hasX ? wordX : 0.0)
                        + slopeY * (hasY ? wordY : 0.0);

                if (xKnown && hasX) currentX += wordX;
                if (yKnown && hasY) currentY += wordY;
                if (zKnown && hasZ) currentZ += wordZ;

                if (hasX || hasY) {
                    double correctedDeltaZ = (hasZ ? wordZ : 0.0) + deltaPlane;
                    outputLines.add(rebuildLineWithZ(
                            code, parenComments, semicolonComment, correctedDeltaZ));
                } else {
                    outputLines.add(line);
                }
                continue;
            }

            if (hasX) {
                currentX = wordX;
                xKnown = true;
            }
            if (hasY) {
                currentY = wordY;
                yKnown = true;
            }
            if (hasZ) {
                currentZ = wordZ;
                zKnown = true;
            }

            boolean needsCompensation = hasZ || (zKnown && (hasX || hasY));
            if (!needsCompensation) {
                outputLines.add(line);
                continue;
            }
            if (!xKnown || !yKnown) {
                throw new LevelingException(ErrorCode.INITIAL_XY_UNKNOWN, lineNumber, null);
            }

            double planeZ = slopeX * currentX + slopeY * currentY + intercept;
            outputLines.add(rebuildLineWithZ(
                    code, parenComments, semicolonComment, currentZ + planeZ));
        }

        return outputLines;
    }

    private static PointData parsePoints(List<String> pointLines) throws LevelingException {
        double[][] points = new double[3][3];
        int pointCount = 0;
        String coordinateSystem = null;
        String units = null;

        for (String line : pointLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher contextMatcher = POINTS_CONTEXT_PATTERN.matcher(trimmed);
            if (contextMatcher.matches()) {
                if (coordinateSystem != null) {
                    throw new LevelingException(ErrorCode.POINT_CONTEXT_INVALID);
                }
                coordinateSystem = contextMatcher.group(1).toUpperCase(Locale.US);
                units = contextMatcher.group(2).toUpperCase(Locale.US);
                continue;
            }
            if (isComment(trimmed)) continue;

            pointCount++;
            if (pointCount > 3) continue;
            String[] parts = trimmed.replace(',', ' ').split("\\s+");
            if (parts.length < 3) {
                throw new LevelingException(ErrorCode.POINT_CONTEXT_INVALID);
            }
            try {
                points[pointCount - 1][0] = Double.parseDouble(parts[0]);
                points[pointCount - 1][1] = Double.parseDouble(parts[1]);
                points[pointCount - 1][2] = Double.parseDouble(parts[2]);
            } catch (NumberFormatException e) {
                throw new LevelingException(ErrorCode.POINT_CONTEXT_INVALID);
            }
        }

        if (coordinateSystem == null || units == null) {
            throw new LevelingException(ErrorCode.POINT_CONTEXT_MISSING);
        }
        if (!"G21".equals(units)) {
            throw new LevelingException(ErrorCode.UNSUPPORTED_UNITS, 0, units);
        }
        if (pointCount != 3) {
            throw new LevelingException(
                    ErrorCode.WRONG_POINT_COUNT, pointCount, String.valueOf(pointCount));
        }
        return new PointData(points, coordinateSystem, units);
    }

    private static String rebuildLineWithZ(String code, StringBuilder parenComments,
                                           String semicolonComment, double z) {
        String zWord = String.format(Locale.US, "Z%.3f", z);
        Matcher zm = Z_WORD_PATTERN.matcher(code);
        String newCode;
        if (zm.find()) {
            newCode = code.substring(0, zm.start()) + zWord + code.substring(zm.end());
        } else {
            newCode = code.trim() + " " + zWord;
        }
        return (newCode + parenComments + (semicolonComment.isEmpty() ? "" : " " + semicolonComment))
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean isComment(String trimmed) {
        return trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.startsWith("(");
    }

    private static boolean same(double first, double second) {
        return Math.abs(first - second) < 0.000001;
    }

    private static String coordinateSystemFor(double value) {
        for (int wcs = 54; wcs <= 59; wcs++) {
            if (same(value, wcs)) return "G" + wcs;
        }
        if (same(value, 59.1)) return "G59.1";
        if (same(value, 59.2)) return "G59.2";
        if (same(value, 59.3)) return "G59.3";
        return null;
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static File getLeveledFile(File gcodeFile) {
        String originalPath = gcodeFile.getAbsolutePath();
        int dotIndex = originalPath.lastIndexOf('.');
        String newPath = dotIndex != -1
                ? originalPath.substring(0, dotIndex) + "_leveled" + originalPath.substring(dotIndex)
                : originalPath + "_leveled";
        return new File(newPath);
    }

    private static String localizedError(LevelingException error) {
        switch (error.code) {
            case POINT_CONTEXT_MISSING:
                return str(R.string.text_leveling_point_context_missing);
            case POINT_CONTEXT_INVALID:
                return str(R.string.text_leveling_point_context_invalid);
            case WRONG_POINT_COUNT:
                return str(R.string.text_leveling_wrong_point_count, error.lineNumber);
            case COLLINEAR_POINTS:
                return str(R.string.text_leveling_collinear);
            case UNSUPPORTED_UNITS:
                if (error.lineNumber <= 0) {
                    return str(R.string.text_leveling_points_require_mm);
                }
                return str(R.string.text_leveling_units_unsupported,
                        error.lineNumber, error.value == null ? "G20" : error.value);
            case WCS_MISMATCH:
                return str(R.string.text_leveling_wcs_mismatch, error.lineNumber, error.value);
            case COORDINATE_OFFSET_UNSUPPORTED:
                return str(R.string.text_leveling_coordinate_offset_unsupported,
                        error.lineNumber, error.value);
            case ARC_UNSUPPORTED:
                return str(R.string.text_leveling_arc_unsupported, error.lineNumber);
            case INITIAL_XY_UNKNOWN:
                return str(R.string.text_leveling_initial_xy_unknown, error.lineNumber);
            default:
                return str(R.string.text_leveling_processing_error, error.getMessage());
        }
    }
}
