/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * Based on GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
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

package in.co.gorest.grblcontroller.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * Striscia heatmap orizzontale: ogni colonna = 1 segmento del gcode,
 * colore proporzionale alla discesa (|dZ| oltre soglia → giallo → rosso).
 * Tap su un punto → callback con l'indice del segmento, per centrare il grafico principale.
 */
public class ZHeatmapStripView extends View {

    public interface OnSegmentTapListener {
        void onSegmentTap(int segmentIndex);
    }

    private List<Float> dzValues;
    private float threshold = 1.0f;

    private final Paint paintCell    = new Paint();
    private final Paint paintBg      = new Paint();
    private final Paint paintBorder  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintMarker  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnSegmentTapListener tapListener;

    public ZHeatmapStripView(Context context) { super(context); init(); }
    public ZHeatmapStripView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        paintBg.setColor(Color.parseColor("#FAFAFA"));
        paintBg.setStyle(Paint.Style.FILL);

        paintBorder.setColor(Color.parseColor("#BDBDBD"));
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBorder.setStrokeWidth(1f);

        paintMarker.setColor(Color.parseColor("#212121"));
        paintMarker.setStyle(Paint.Style.STROKE);
        paintMarker.setStrokeWidth(1.5f);
    }

    public void setData(List<Float> dzValues, float threshold) {
        this.dzValues = dzValues;
        this.threshold = threshold;
        invalidate();
    }

    public void setOnSegmentTapListener(OnSegmentTapListener l) {
        this.tapListener = l;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP
                && tapListener != null
                && dzValues != null && !dzValues.isEmpty()) {
            float x = event.getX();
            int n = dzValues.size();
            int idx = (int) ((x / getWidth()) * n);
            if (idx < 0) idx = 0;
            if (idx >= n) idx = n - 1;
            tapListener.onSegmentTap(idx);
            return true;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        canvas.drawRect(0, 0, w, h, paintBg);

        if (dzValues == null || dzValues.isEmpty() || threshold <= 0) {
            canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, paintBorder);
            return;
        }

        int n = dzValues.size();
        // Disegniamo aggregando più segmenti per colonna se n > w
        // così rimane fluido anche su file grandi
        int cols = Math.min(n, w);
        if (cols < 1) cols = 1;
        float colW = (float) w / cols;

        for (int c = 0; c < cols; c++) {
            int from = (int) ((long) c * n / cols);
            int to   = (int) ((long) (c + 1) * n / cols);
            if (to <= from) to = from + 1;
            if (to > n) to = n;

            // Prendiamo la discesa massima nel range (valore più negativo)
            float worstDz = 0f;
            for (int i = from; i < to; i++) {
                float dz = dzValues.get(i);
                if (dz < worstDz) worstDz = dz;
            }

            int color = colorForDz(worstDz);
            paintCell.setColor(color);
            float x0 = c * colW;
            float x1 = (c + 1) * colW + 0.5f; // overlap per evitare gap
            canvas.drawRect(x0, 0, x1, h, paintCell);
        }

        // Tacche verticali ogni 10% per riferimento posizione
        for (int i = 1; i < 10; i++) {
            float tx = w * i / 10f;
            canvas.drawLine(tx, h - 6, tx, h, paintMarker);
        }

        // Bordo
        canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, paintBorder);
    }

    /**
     * Mappa la discesa a un colore.
     * dz >= 0                  → bianco (nessuna discesa)
     * 0 > dz > -threshold      → grigio chiaro (sotto soglia)
     * -threshold ≤ dz < -2*th  → giallo → arancio
     * dz ≤ -2*threshold        → rosso scuro (critico)
     */
    private int colorForDz(float dz) {
        if (dz >= 0) return Color.parseColor("#FFFFFF");

        float absDz = -dz;
        if (absDz < threshold) {
            // Discesa normale (sotto soglia) — grigio molto chiaro
            return Color.parseColor("#F0F0F0");
        }

        // Oltre la soglia: gradiente giallo → rosso scuro
        // ratio: 0 alla soglia, 1 al doppio della soglia o oltre
        float ratio = Math.min(1f, (absDz - threshold) / threshold);

        // Giallo (#FFEB3B) → Rosso scuro (#B71C1C)
        int r = (int) lerp(0xFF, 0xB7, ratio);
        int g = (int) lerp(0xEB, 0x1C, ratio);
        int b = (int) lerp(0x3B, 0x1C, ratio);
        return Color.rgb(r, g, b);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
