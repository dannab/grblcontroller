/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package in.co.gorest.grblcontroller.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict reader for the coordinate-only points.txt file. */
public final class PlacementPoints {

    private PlacementPoints() {}

    public static final class Point {
        public final double x;
        public final double y;
        public final double z;

        public Point(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class ParseException extends Exception {
        public final int lineNumber;
        public final String lineText;

        private ParseException(int lineNumber, String lineText) {
            super("Invalid point at line " + lineNumber);
            this.lineNumber = lineNumber;
            this.lineText = lineText;
        }
    }

    public static List<Point> read(File file) throws IOException, ParseException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return parse(lines);
    }

    /** Package-visible for deterministic JVM tests. */
    static List<Point> parse(List<String> lines) throws ParseException {
        List<Point> points = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String original = lines.get(index);
            String trimmed = original == null ? "" : original.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";")
                    || trimmed.startsWith("#") || trimmed.startsWith("(")) continue;

            String[] values = trimmed.replace(',', ' ').trim().split("\\s+");
            if (values.length != 3) throw new ParseException(index + 1, trimmed);
            try {
                double x = Double.parseDouble(values[0]);
                double y = Double.parseDouble(values[1]);
                double z = Double.parseDouble(values[2]);
                if (Double.isNaN(x) || Double.isInfinite(x)
                        || Double.isNaN(y) || Double.isInfinite(y)
                        || Double.isNaN(z) || Double.isInfinite(z)) {
                    throw new NumberFormatException("Non-finite coordinate");
                }
                points.add(new Point(x, y, z));
            } catch (NumberFormatException error) {
                throw new ParseException(index + 1, trimmed);
            }
        }
        return Collections.unmodifiableList(points);
    }
}
