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

import android.util.Pair;
import java.util.Locale;
import in.co.gorest.grblcontroller.model.Constants;

public class SimpleGcodeMaker {

    private double xf, yf, xt, yt;
    private double xcenter, ycenter, xcirc, ycirc;
    private double zfrom, zstep, zdeep, ztraversal;
    private double plungeFeedrate, cutFeedrate;
    private double ray;
    private boolean Zaprox_pass;
    private double width, height;

    public SimpleGcodeMaker(
            double xf,
            double yf,
            double xt,
            double yt,
            double zfrom,
            double zstep,
            double zdeep,
            double ztraversal,
            double plungeFeedrate,
            double cutFeedrate,
            boolean Zaprox_pass) {

        this.xcenter = xf;
        this.ycenter = yf;
        this.xcirc = xt;
        this.ycirc = yt;

        this.zfrom = zfrom;
        this.zstep = Math.abs(zstep);   // FIX: sempre positivo
        this.zdeep = Math.abs(zdeep);   // FIX: sempre positivo (coordinata relativa)
        this.ztraversal = Math.abs(ztraversal);

        this.plungeFeedrate = plungeFeedrate;
        this.cutFeedrate = cutFeedrate;
        this.Zaprox_pass = Zaprox_pass;

        double X = Math.max(xf, xt);
        double Y = Math.max(yf, yt);
        double x = Math.min(xf, xt);
        double y = Math.min(yf, yt);

        this.xf = x;
        this.yf = Y;
        this.xt = X;
        this.yt = y;

        this.width  = Math.abs(this.xt - this.xf);
        this.height = Math.abs(this.yf - this.yt);

        this.ray = Math.sqrt(
                Math.pow(this.xcirc - this.xcenter, 2) +
                        Math.pow(this.ycirc - this.ycenter, 2));
    }

    private String f(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /**
     * Calcola numero di passate e step effettivo.
     * FIX: se step <= 0 restituisce 1 passata con step = tot_len
     * per evitare divisione per zero (es. circleCut con offset=0).
     */
    private Pair<Integer, Double> pass(double tot_len, double step, boolean aprox_pass) {
        tot_len = Math.abs(tot_len);

        // FIX punto 3: offset=0 → una sola passata al raggio pieno
        if (step <= 0) {
            return new Pair<>(1, tot_len);
        }

        int npass = (int) Math.ceil(tot_len / step);
        if (npass <= 0) npass = 1;

        if (aprox_pass) {
            step = tot_len / npass;
        }

        return new Pair<>(npass, step);
    }

    private String ZLoop(String cutPath, double startX, double startY, boolean retractEachPass) {

        Pair<Integer, Double> pass = this.pass(this.zdeep, this.zstep, this.Zaprox_pass);

        StringBuilder sb = new StringBuilder();
        double safeZ = this.zfrom + this.ztraversal;

        sb.append(Constants.CAM_GCODE_HEAD);
        sb.append("G00 Z").append(f(safeZ)).append("\n");

        for (int i = 1; i <= pass.first; i++) {
            double targetZ = this.zfrom - (pass.second * i);

            sb.append("G00 X").append(f(startX))
                    .append(" Y").append(f(startY)).append("\n");

            sb.append("G01 Z").append(f(targetZ))
                    .append(" F").append(f(plungeFeedrate)).append("\n");

            sb.append("F").append(f(cutFeedrate)).append("\n");

            sb.append(cutPath);

            if (retractEachPass) {
                sb.append("G00 Z").append(f(safeZ)).append("\n");
            }
        }

        sb.append("G00 Z").append(f(safeZ)).append("\n");
        sb.append(Constants.CAM_GCODE_END);

        return sb.toString();
    }

    public String cutRectangleContour(boolean aprox_pass) {
        StringBuilder path = new StringBuilder();
        path.append("G01 X").append(f(this.xt)).append("\n");
        path.append("G01 Y").append(f(this.yt)).append("\n");
        path.append("G01 X").append(f(this.xf)).append("\n");
        path.append("G01 Y").append(f(this.yf)).append("\n");
        // L'incisione segue un contorno chiuso: al termine di ogni giro
        // l'utensile e' gia' sul punto iniziale. La passata successiva puo'
        // quindi scendere direttamente, senza una retrazione intermedia.
        return ZLoop(path.toString(), this.xf, this.yf, false);
    }

    public String cutRectangle(double offset, boolean aprox_pass, boolean invert_direction) {

        double minSide   = Math.min(this.width, this.height) / 2.0;
        double minOffset = 0.0001;

        if (offset <= 0 || minSide <= 0) return "";

        Pair<Integer, Double> p = pass(minSide, offset, aprox_pass);

        StringBuilder path = new StringBuilder();

        for (int i = 0; i < p.first; i++) {
            int index = invert_direction ? (p.first - 1 - i) : i;
            double o = p.second * index;
            if (o >= minSide - minOffset) continue;

            double x1 = this.xf + o;
            double x2 = this.xt - o;
            double y1 = this.yf - o;
            double y2 = this.yt + o;

            path.append("G01 X").append(f(x2)).append("\n");
            path.append("G01 Y").append(f(y2)).append("\n");
            path.append("G01 X").append(f(x1)).append("\n");
            path.append("G01 Y").append(f(y1)).append("\n");
        }

        double startOffset = !invert_direction ? 0 :
                Math.min(p.second * (p.first - 1), minSide - minOffset);

        return ZLoop(path.toString(),
                this.xf + startOffset,
                this.yf - startOffset,
                true);
    }

    /**
     * FIX punto 4: aggiunta ultima passata che riporta il tool
     * alla coordinata Y finale per completare la copertura dell'area.
     */
    public String snakeX(double dist, boolean aprox_pass) {
        Pair<Integer, Double> p = pass(this.height, dist, aprox_pass);
        StringBuilder path = new StringBuilder();
        boolean sw = true;

        for (int i = 1; i <= p.first; i++) {
            double stepY = this.yf - (p.second * i);
            // Clamp: non andare oltre yt
            if (stepY < this.yt) stepY = this.yt;

            path.append("G01 X").append(f(sw ? this.xt : this.xf)).append("\n");
            path.append("G01 Y").append(f(stepY)).append("\n");
            sw = !sw;
        }

        // FIX: ultima passata orizzontale per coprire il bordo finale
        path.append("G01 X").append(f(sw ? this.xt : this.xf)).append("\n");

        return ZLoop(path.toString(), this.xf, this.yf, true);
    }

    /**
     * FIX punto 4: aggiunta ultima passata che riporta il tool
     * alla coordinata X finale per completare la copertura dell'area.
     */
    public String snakeY(double dist, boolean aprox_pass) {
        Pair<Integer, Double> p = pass(this.width, dist, aprox_pass);
        StringBuilder path = new StringBuilder();
        boolean sw = true;

        for (int i = 1; i <= p.first; i++) {
            double stepX = this.xf + (p.second * i);
            // Clamp: non andare oltre xt
            if (stepX > this.xt) stepX = this.xt;

            path.append("G01 Y").append(f(sw ? this.yt : this.yf)).append("\n");
            path.append("G01 X").append(f(stepX)).append("\n");
            sw = !sw;
        }

        // FIX: ultima passata verticale per coprire il bordo finale
        path.append("G01 Y").append(f(sw ? this.yt : this.yf)).append("\n");

        return ZLoop(path.toString(), this.xf, this.yf, true);
    }

    public String circleCutContour(boolean aprox_pass) {
        StringBuilder path = new StringBuilder();
        double r     = this.ray;
        double quadR = this.xcenter + r;
        double quadL = this.xcenter - r;

        path.append("G01 X").append(f(quadR))
                .append(" Y").append(f(this.ycenter)).append("\n");
        path.append("G02 X").append(f(quadL))
                .append(" I").append(f(-r)).append(" J0.000\n");
        path.append("G02 X").append(f(quadR))
                .append(" I").append(f(r)).append(" J0.000\n");

        return ZLoop(path.toString(), this.xcenter + r, this.ycenter, true);
    }

    /**
     * FIX punto 3: offset=0 ora gestito correttamente da pass()
     * senza divisione per zero.
     */
    public String circleCut(double offset, boolean aprox_pass, boolean invert_direction) {
        Pair<Integer, Double> p = pass(this.ray, offset, aprox_pass);
        StringBuilder path = new StringBuilder();
        double minRadius = 0.0001;

        for (int i = 0; i < p.first; i++) {
            int index = invert_direction ? (p.first - 1 - i) : i;
            double o = p.second * index;
            if (o > this.ray) o = this.ray;

            double currentR = this.ray - o;
            if (currentR <= minRadius) continue;

            double quadR = this.xcenter + currentR;
            double quadL = this.xcenter - currentR;

            path.append("G01 X").append(f(quadR))
                    .append(" Y").append(f(this.ycenter)).append("\n");
            path.append("G02 X").append(f(quadL))
                    .append(" I").append(f(-currentR)).append(" J0.000\n");
            path.append("G02 X").append(f(quadR))
                    .append(" I").append(f(currentR)).append(" J0.000\n");
        }

        double startRadius;
        if (!invert_direction) {
            startRadius = this.ray;
        } else {
            double lastOffset = p.second * (p.first - 1);
            if (lastOffset > this.ray) lastOffset = this.ray;
            startRadius = this.ray - lastOffset;
            if (startRadius <= minRadius) startRadius = p.second;
        }

        return ZLoop(path.toString(), this.xcenter + startRadius, this.ycenter, false);
    }

    /**
     * FIX punto 2: corneringCut con archi geometricamente corretti.
     *
     * Il profilo esce dal rettangolo di toolRadius su ogni lato,
     * poi raccorda gli angoli con archi G02 il cui centro coincide
     * esattamente con il vertice del rettangolo originale.
     *
     * Percorso (partendo da xf, yf+toolRadius, senso orario):
     *   lato sinistro → arco angolo alto-sx → lato alto →
     *   arco angolo alto-dx → lato destro → arco angolo basso-dx →
     *   lato basso → arco angolo basso-sx → ritorno al punto di partenza
     */
    public String corneringCut(double toolRadius) {
        double r  = toolRadius;
        double x1 = this.xf;
        double x2 = this.xt;
        double y1 = this.yt;   // y bassa (yt < yf dopo normalizzazione)
        double y2 = this.yf;   // y alta

        StringBuilder path = new StringBuilder();

        // Partenza: (x1, y2+r) — lato sinistro esteso
        // Lato sinistro verso alto
        path.append("G01 Y").append(f(y2)).append("\n");

        // Arco angolo alto-sinistro: centro in (x1, y2), raggio r
        path.append("G02 X").append(f(x1 + r))
                .append(" Y").append(f(y2 + r))
                .append(" I").append(f(r))
                .append(" J0.000\n");

        // Lato alto
        path.append("G01 X").append(f(x2)).append("\n");

        // Arco angolo alto-destro: centro in (x2, y2), raggio r
        path.append("G02 X").append(f(x2 + r))
                .append(" Y").append(f(y2))
                .append(" I0.000 J").append(f(-r)).append("\n");

        // Lato destro verso basso
        path.append("G01 Y").append(f(y1)).append("\n");

        // Arco angolo basso-destro: centro in (x2, y1), raggio r
        path.append("G02 X").append(f(x2))
                .append(" Y").append(f(y1 - r))
                .append(" I").append(f(-r))
                .append(" J0.000\n");

        // Lato basso
        path.append("G01 X").append(f(x1)).append("\n");

        // Arco angolo basso-sinistro: centro in (x1, y1), raggio r
        path.append("G02 X").append(f(x1 - r))
                .append(" Y").append(f(y1))
                .append(" I0.000 J").append(f(r)).append("\n");

        // Lato sinistro verso alto (ritorno al punto di partenza)
        path.append("G01 Y").append(f(y2 + r)).append("\n");

        return ZLoop(path.toString(), x1 - r, y2 + r, false);
    }

    public String lineCut() {
        Pair<Integer, Double> p = pass(this.zdeep, this.zstep, this.Zaprox_pass);
        StringBuilder sb = new StringBuilder();
        boolean sw = true;

        double safeZ = this.zfrom + this.ztraversal;

        sb.append(Constants.CAM_GCODE_HEAD);
        sb.append("G00 Z").append(f(safeZ)).append("\n");
        sb.append("G00 X").append(f(this.xcenter))
                .append(" Y").append(f(this.ycenter)).append("\n");

        for (int i = 1; i <= p.first; i++) {
            double targetZ = this.zfrom - (p.second * i);

            sb.append("G01 Z").append(f(targetZ))
                    .append(" F").append(f(plungeFeedrate)).append("\n");
            sb.append("F").append(f(cutFeedrate)).append("\n");

            if (sw) {
                sb.append("X").append(f(this.xcirc))
                        .append(" Y").append(f(this.ycirc)).append("\n");
            } else {
                sb.append("X").append(f(this.xcenter))
                        .append(" Y").append(f(this.ycenter)).append("\n");
            }
            sw = !sw;
        }

        sb.append("G00 Z").append(f(safeZ)).append("\n");
        sb.append(Constants.CAM_GCODE_END);

        return sb.toString();
    }
}
