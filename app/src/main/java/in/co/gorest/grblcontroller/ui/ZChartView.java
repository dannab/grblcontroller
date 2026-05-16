/*
 * Copyright (C) 2025 Daniele Cicchinelli
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
 *
 * Original project: GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 * Written by Daniele Cicchinelli, 2025
 */

package in.co.gorest.grblcontroller.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;

/**
 * View custom che disegna:
 *  - Grafico lineare della quota Z (blu)
 *  - Grafico della velocità di discesa Z / derivata (arancione)
 *  - Linea di soglia orizzontale rossa
 *  - Punti critici evidenziati in rosso
 *
 * Supporta pan orizzontale (1 dito) e zoom pinch.
 */
public class ZChartView extends View {

    // -------------------------------------------------------------------------
    // Dati
    // -------------------------------------------------------------------------
    private List<Float> zValues;       // quota Z per ogni segmento
    private List<Float> dzValues;      // variazione Z (derivata discreta)
    private List<Integer> criticalIdx; // indici dei punti critici

    private float threshold = 1.0f;   // soglia discesa mm

    // -------------------------------------------------------------------------
    // Paint
    // -------------------------------------------------------------------------
    private final Paint paintZ       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDz      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintThresh  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCrit    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBg      = new Paint();
    private final Paint paintBgDz    = new Paint();

    // -------------------------------------------------------------------------
    // Viewport (pan + zoom)
    // -------------------------------------------------------------------------
    private float offsetX    = 0f;  // pan orizzontale in pixel
    private float scaleX     = 1f;  // zoom orizzontale
    private float lastPanX   = 0f;

    private static final float PADDING_LEFT   = 56f;
    private static final float PADDING_RIGHT  = 12f;
    private static final float PADDING_TOP    = 16f;
    private static final float PADDING_BOTTOM = 32f;

    // -------------------------------------------------------------------------
    // Gesture
    // -------------------------------------------------------------------------
    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;

    // -------------------------------------------------------------------------
    // Costruttori
    // -------------------------------------------------------------------------
    public ZChartView(Context context) {
        super(context);
        init(context);
    }

    public ZChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Z — blu solido
        paintZ.setColor(Color.parseColor("#2196F3"));
        paintZ.setStrokeWidth(2f);
        paintZ.setStyle(Paint.Style.STROKE);

        // dZ — arancione
        paintDz.setColor(Color.parseColor("#FF9800"));
        paintDz.setStrokeWidth(1.5f);
        paintDz.setStyle(Paint.Style.STROKE);

        // Soglia — rosso tratteggiato
        paintThresh.setColor(Color.parseColor("#F44336"));
        paintThresh.setStrokeWidth(2f);
        paintThresh.setStyle(Paint.Style.STROKE);
        paintThresh.setPathEffect(new android.graphics.DashPathEffect(new float[]{12, 8}, 0));

        // Punti critici — rosso pieno
        paintCrit.setColor(Color.parseColor("#F44336"));
        paintCrit.setStyle(Paint.Style.FILL);

        // Griglia
        paintGrid.setColor(Color.parseColor("#E0E0E0"));
        paintGrid.setStrokeWidth(1f);

        // Label assi
        paintLabel.setColor(Color.parseColor("#555555"));
        paintLabel.setTextSize(26f);

        // Sfondi zone
        paintBg.setColor(Color.parseColor("#F8F8FF"));
        paintBg.setStyle(Paint.Style.FILL);
        paintBgDz.setColor(Color.parseColor("#FFFBF0"));
        paintBgDz.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        scaleX *= d.getScaleFactor();
                        scaleX = Math.max(0.5f, Math.min(50f, scaleX));
                        invalidate();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        offsetX -= distanceX;
                        clampOffset();
                        invalidate();
                        return true;
                    }
                });
    }

    // -------------------------------------------------------------------------
    // API pubblica
    // -------------------------------------------------------------------------

    public void setData(List<Float> zValues, List<Float> dzValues,
                        List<Integer> criticalIdx, float threshold) {
        this.zValues     = zValues;
        this.dzValues    = dzValues;
        this.criticalIdx = criticalIdx;
        this.threshold   = threshold;
        this.offsetX     = 0f;
        this.scaleX      = 1f;
        invalidate();
    }

    public void setThreshold(float threshold) {
        this.threshold = threshold;
        invalidate();
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private void clampOffset() {
        if (zValues == null || zValues.isEmpty()) { offsetX = 0; return; }
        float chartW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        float totalW = zValues.size() * scaleX;
        float minOffset = Math.min(0, chartW - totalW);
        offsetX = Math.max(minOffset, Math.min(0, offsetX));
    }

    // -------------------------------------------------------------------------
    // Disegno
    // -------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        if (zValues == null || zValues.isEmpty()) {
            paintLabel.setTextSize(32f);
            canvas.drawText("Nessun dato — premi Analizza", PADDING_LEFT, h / 2f, paintLabel);
            return;
        }

        // --- Zone: metà superiore = Z, metà inferiore = dZ ---
        float midY    = h / 2f;
        float chartW  = w - PADDING_LEFT - PADDING_RIGHT;

        // Sfondi
        canvas.drawRect(0, 0, w, midY, paintBg);
        canvas.drawRect(0, midY, w, h, paintBgDz);

        // --- Calcola range Z ---
        float zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
        for (float z : zValues) { zMin = Math.min(zMin, z); zMax = Math.max(zMax, z); }
        float zRange = zMax - zMin;
        if (zRange == 0) zRange = 1f;

        // --- Calcola range dZ ---
        float dzMin = Float.MAX_VALUE, dzMax = -Float.MAX_VALUE;
        for (float dz : dzValues) { dzMin = Math.min(dzMin, dz); dzMax = Math.max(dzMax, dz); }
        float dzRange = dzMax - dzMin;
        if (dzRange == 0) dzRange = 1f;

        int n = zValues.size();
        float stepX = (chartW * scaleX) / Math.max(n - 1, 1);

        // --- Griglia orizzontale (5 livelli per zona) ---
        for (int i = 0; i <= 4; i++) {
            float fy = PADDING_TOP + (midY - PADDING_TOP - 8) * i / 4f;
            canvas.drawLine(PADDING_LEFT, fy, w - PADDING_RIGHT, fy, paintGrid);
            float zVal = zMax - (zRange * i / 4f);
            canvas.drawText(String.format("%.1f", zVal), 2, fy + 8, paintLabel);

            float fy2 = midY + 8 + (h - PADDING_BOTTOM - midY - 8) * i / 4f;
            canvas.drawLine(PADDING_LEFT, fy2, w - PADDING_RIGHT, fy2, paintGrid);
            float dzVal = dzMax - (dzRange * i / 4f);
            canvas.drawText(String.format("%.1f", dzVal), 2, fy2 + 8, paintLabel);
        }

        // --- Label zone ---
        paintLabel.setTextSize(28f);
        paintLabel.setColor(Color.parseColor("#2196F3"));
        canvas.drawText("Z (mm)", PADDING_LEFT + 4, PADDING_TOP + 28, paintLabel);
        paintLabel.setColor(Color.parseColor("#FF9800"));
        canvas.drawText("ΔZ (mm)", PADDING_LEFT + 4, midY + 36, paintLabel);
        paintLabel.setColor(Color.parseColor("#555555"));

        // --- Clip alla zona grafico ---
        canvas.save();
        canvas.clipRect(PADDING_LEFT, 0, w - PADDING_RIGHT, h);

        // --- Path Z ---
        Path pathZ = new Path();
        boolean firstZ = true;
        for (int i = 0; i < n; i++) {
            float px = PADDING_LEFT + offsetX + i * stepX;
            float py = PADDING_TOP + (midY - PADDING_TOP - 8) *
                       (1f - (zValues.get(i) - zMin) / zRange);
            if (firstZ) { pathZ.moveTo(px, py); firstZ = false; }
            else        { pathZ.lineTo(px, py); }
        }
        canvas.drawPath(pathZ, paintZ);

        // --- Path dZ ---
        Path pathDz = new Path();
        boolean firstDz = true;
        for (int i = 0; i < dzValues.size(); i++) {
            float px = PADDING_LEFT + offsetX + i * stepX;
            float py = midY + 8 + (h - PADDING_BOTTOM - midY - 8) *
                       (1f - (dzValues.get(i) - dzMin) / dzRange);
            if (firstDz) { pathDz.moveTo(px, py); firstDz = false; }
            else         { pathDz.lineTo(px, py); }
        }
        canvas.drawPath(pathDz, paintDz);

        // --- Linea soglia su zona dZ ---
        float threshNorm = Math.abs(threshold);
        // La soglia si posiziona in base al valore assoluto di dzMin
        if (threshNorm <= Math.abs(dzMin)) {
            float threshY = midY + 8 + (h - PADDING_BOTTOM - midY - 8) *
                    (1f - ((-threshNorm) - dzMin) / dzRange);
            canvas.drawLine(PADDING_LEFT, threshY, w - PADDING_RIGHT, threshY, paintThresh);
        }

        // --- Punti critici su entrambe le zone ---
        for (int idx : criticalIdx) {
            if (idx >= n) continue;
            float px = PADDING_LEFT + offsetX + idx * stepX;

            // Punto su grafico Z
            float pyZ = PADDING_TOP + (midY - PADDING_TOP - 8) *
                        (1f - (zValues.get(idx) - zMin) / zRange);
            canvas.drawCircle(px, pyZ, 5f, paintCrit);

            // Linea verticale rossa leggera su entrambe le zone
            Paint lineP = new Paint();
            lineP.setColor(Color.parseColor("#33F44336"));
            lineP.setStrokeWidth(1.5f);
            canvas.drawLine(px, PADDING_TOP, px, midY, lineP);

            // Punto su grafico dZ
            if (idx < dzValues.size()) {
                float pyDz = midY + 8 + (h - PADDING_BOTTOM - midY - 8) *
                             (1f - (dzValues.get(idx) - dzMin) / dzRange);
                canvas.drawCircle(px, pyDz, 5f, paintCrit);
                canvas.drawLine(px, midY + 8, px, h - PADDING_BOTTOM, lineP);
            }
        }

        canvas.restore();

        // --- Linea divisoria tra zone ---
        Paint divP = new Paint();
        divP.setColor(Color.parseColor("#BDBDBD"));
        divP.setStrokeWidth(2f);
        canvas.drawLine(0, midY, w, midY, divP);

        // --- Asse X (numeri riga) ---
        paintLabel.setTextSize(22f);
        int step = Math.max(1, n / 8);
        for (int i = 0; i < n; i += step) {
            float px = PADDING_LEFT + offsetX + i * stepX;
            if (px >= PADDING_LEFT && px <= w - PADDING_RIGHT) {
                canvas.drawText(String.valueOf(i), px - 10, h - 6, paintLabel);
            }
        }
    }
}
