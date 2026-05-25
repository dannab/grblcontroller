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

import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import in.co.gorest.grblcontroller.BR;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentZAnalyzerTabBinding;
import in.co.gorest.grblcontroller.events.BluetoothDisconnectEvent;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;

public class ZAnalyzerTabFragment extends BaseFragment {

    private static final String TAG = ZAnalyzerTabFragment.class.getSimpleName();

    // -------------------------------------------------------------------------
    // Soglia discesa Z (mm) — regolabile dall'utente
    // -------------------------------------------------------------------------
    private float threshold = 1.0f;
    private static final float THRESHOLD_STEP = 0.5f;
    private static final float THRESHOLD_MIN  = 0.1f;
    private static final float THRESHOLD_MAX  = 20.0f;

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private FileSenderListener fileSender;

    // -------------------------------------------------------------------------
    // Viste
    // -------------------------------------------------------------------------
    private ZChartView         zChartView;
    private ZHeatmapStripView  zHeatmap;
    private TextView           tvThreshold;
    private TextView           tvZInfo;
    private TextView           tvVerdict;
    private LinearLayout       llTopDrops;

    private static final int TOP_DROPS_MAX = 10;

    // -------------------------------------------------------------------------
    // Callback FileSenderListener — nuovo file selezionato
    // -------------------------------------------------------------------------
    private final Observable.OnPropertyChangedCallback fileSenderCallback =
            new Observable.OnPropertyChangedCallback() {
                @Override
                public void onPropertyChanged(Observable sender, int propertyId) {
                    if (propertyId == BR.gcodeFile) {
                        if (fileSender.getGcodeFile() != null
                                && fileSender.getGcodeFile().exists()) {
                            analyzeFile(fileSender.getGcodeFile().getAbsolutePath());
                        }
                    }
                }
            };

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------
    public ZAnalyzerTabFragment() {}

    public static ZAnalyzerTabFragment newInstance() {
        return new ZAnalyzerTabFragment();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileSender = FileSenderListener.getInstance();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        fileSender.addOnPropertyChangedCallback(fileSenderCallback);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        fileSender.removeOnPropertyChangedCallback(fileSenderCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        FragmentZAnalyzerTabBinding binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_z_analyzer_tab, container, false);
        binding.setFileSender(fileSender);
        View view = binding.getRoot();

        zChartView  = view.findViewById(R.id.zChartView);
        zHeatmap    = view.findViewById(R.id.zHeatmap);
        tvThreshold = view.findViewById(R.id.tvThreshold);
        tvZInfo     = view.findViewById(R.id.tvZInfo);
        tvVerdict   = view.findViewById(R.id.tvVerdict);
        llTopDrops  = view.findViewById(R.id.llTopDrops);

        // Tap sulla heatmap → centra il grafico principale sul punto toccato
        zHeatmap.setOnSegmentTapListener(idx -> zChartView.centerOnIndex(idx));

        updateThresholdLabel();

        // --- Soglia - ---
        view.findViewById(R.id.btnThresholdMinus).setOnClickListener(v -> {
            threshold = Math.max(THRESHOLD_MIN, threshold - THRESHOLD_STEP);
            updateThresholdLabel();
            zChartView.setThreshold(threshold);
            // Rianalizziamo per aggiornare i punti critici
            if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
                analyzeFile(fileSender.getGcodeFile().getAbsolutePath());
            }
        });

        // --- Soglia + ---
        view.findViewById(R.id.btnThresholdPlus).setOnClickListener(v -> {
            threshold = Math.min(THRESHOLD_MAX, threshold + THRESHOLD_STEP);
            updateThresholdLabel();
            zChartView.setThreshold(threshold);
            if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
                analyzeFile(fileSender.getGcodeFile().getAbsolutePath());
            }
        });

        // --- Analizza manualmente ---
        view.findViewById(R.id.btnAnalyze).setOnClickListener(v -> {
            if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
                analyzeFile(fileSender.getGcodeFile().getAbsolutePath());
            } else {
                tvZInfo.setText("Nessun file GCode caricato nel tab File Sender.");
            }
        });

        // --- File già presente ---
        if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
            analyzeFile(fileSender.getGcodeFile().getAbsolutePath());
        }

        return view;
    }

    // -------------------------------------------------------------------------
    // EventBus
    // -------------------------------------------------------------------------

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothDisconnectEvent(BluetoothDisconnectEvent event) {
        // Niente da fare qui — il grafico rimane visibile
    }

    // -------------------------------------------------------------------------
    // Analisi file GCode
    // -------------------------------------------------------------------------

    private void analyzeFile(String filePath) {
        tvZInfo.setText("Analisi in corso…");
        new AnalyzeTask().execute(filePath);
    }

    /**
     * AsyncTask che legge il file GCode in background ed estrae:
     * - zValues:    quota Z di ogni segmento
     * - dzValues:   variazione Z rispetto al segmento precedente (derivata)
     * - criticalIdx: indici dove |dZ| > threshold (discese brusche)
     */
    @SuppressWarnings("deprecation")
    private class AnalyzeTask extends AsyncTask<String, Void, AnalyzeResult> {

        @Override
        protected AnalyzeResult doInBackground(String... params) {
            String filePath = params[0];
            AnalyzeResult result = new AnalyzeResult();

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

                String line;
                float x = 0, y = 0, z = 0;
                boolean isAbsolute = true;
                int modalMotion = 0;
                int lineNo = 0;

                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    line = line.trim().toUpperCase();
                    if (line.isEmpty()) continue;

                    // Rimuovi commenti
                    int ci = line.indexOf('(');
                    if (ci >= 0) line = line.substring(0, ci).trim();
                    ci = line.indexOf(';');
                    if (ci >= 0) line = line.substring(0, ci).trim();
                    if (line.isEmpty()) continue;

                    // Comandi modali
                    if (containsGCode(line, 90)) isAbsolute = true;
                    if (containsGCode(line, 91)) isAbsolute = false;
                    if (containsGCode(line, 0))  modalMotion = 0;
                    if (containsGCode(line, 1))  modalMotion = 1;
                    if (containsGCode(line, 2))  modalMotion = 2;
                    if (containsGCode(line, 3))  modalMotion = 3;

                    float nx = parseCoord(line, 'X', x);
                    float ny = parseCoord(line, 'Y', y);
                    float nz = parseCoord(line, 'Z', z);

                    if (!isAbsolute) { nx += x; ny += y; nz += z; }

                    boolean hasCoords = line.contains("X") || line.contains("Y") || line.contains("Z");
                    boolean posChanged = (nx != x || ny != y || nz != z);
                    boolean hasMotion  = containsGCode(line, 0) || containsGCode(line, 1)
                                      || containsGCode(line, 2) || containsGCode(line, 3);

                    if (hasMotion || (hasCoords && posChanged)) {
                        float dz = nz - z; // variazione Z (negativa = discesa)
                        result.zValues.add(nz);
                        result.dzValues.add(dz);
                        result.lineNumbers.add(lineNo);

                        // Critico se discesa brusca (dz negativo oltre soglia)
                        if (dz < -threshold) {
                            int segIdx = result.zValues.size() - 1;
                            result.criticalIdx.add(segIdx);
                            CriticalDrop drop = new CriticalDrop();
                            drop.segmentIdx = segIdx;
                            drop.lineNumber = lineNo;
                            drop.zBefore = z;
                            drop.zAfter  = nz;
                            drop.dz      = dz;
                            result.drops.add(drop);
                        }

                        x = nx; y = ny; z = nz;
                    }
                }

            } catch (IOException e) {
                Log.e(TAG, "Errore lettura file: " + e.getMessage());
            }

            return result;
        }

        @Override
        protected void onPostExecute(AnalyzeResult result) {
            if (result.zValues.isEmpty()) {
                tvZInfo.setText("Nessun movimento trovato nel file.");
                setVerdict(VerdictLevel.UNKNOWN, "Nessun movimento trovato nel file");
                llTopDrops.removeAllViews();
                zHeatmap.setData(null, threshold);
                return;
            }

            // Statistiche
            float zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
            float dzMin = 0;
            for (float z : result.zValues) {
                zMin = Math.min(zMin, z);
                zMax = Math.max(zMax, z);
            }
            for (float dz : result.dzValues) {
                dzMin = Math.min(dzMin, dz); // discesa massima (valore più negativo)
            }

            String info = String.format(Locale.US,
                    "Segmenti: %d  |  Z min: %.3f  Z max: %.3f  |  "
                    + "Discesa max: %.3f mm  |  Punti critici (>%.1f mm): %d",
                    result.zValues.size(), zMin, zMax,
                    dzMin, threshold, result.criticalIdx.size());

            tvZInfo.setText(info);

            // --- Banner-semaforo: verdetto a colpo d'occhio ---
            int nCrit = result.drops.size();
            if (nCrit == 0) {
                setVerdict(VerdictLevel.OK, String.format(Locale.US,
                        "OK — nessuna discesa oltre %.1f mm", threshold));
            } else {
                float worst = Math.abs(dzMin);
                boolean severe = worst >= 2f * threshold;
                String msg = String.format(Locale.US,
                        "%s — %d affond%s | discesa max: %.2f mm (soglia %.1f)",
                        severe ? "AFFONDI CRITICI" : "Attenzione",
                        nCrit, nCrit == 1 ? "o" : "i",
                        worst, threshold);
                setVerdict(severe ? VerdictLevel.CRITICAL : VerdictLevel.WARN, msg);
            }

            // --- Heatmap strip ---
            zHeatmap.setData(result.dzValues, threshold);

            // --- Lista top-N affondi peggiori ---
            populateTopDrops(result.drops);

            zChartView.setData(result.zValues, result.dzValues,
                               result.criticalIdx, threshold);
        }
    }

    // -------------------------------------------------------------------------
    // Banner-semaforo
    // -------------------------------------------------------------------------

    private enum VerdictLevel { OK, WARN, CRITICAL, UNKNOWN }

    private void setVerdict(VerdictLevel level, String message) {
        if (tvVerdict == null) return;
        int bg;
        switch (level) {
            case OK:        bg = Color.parseColor("#2E7D32"); break; // verde
            case WARN:      bg = Color.parseColor("#F57C00"); break; // arancio
            case CRITICAL:  bg = Color.parseColor("#C62828"); break; // rosso scuro
            default:        bg = Color.parseColor("#9E9E9E"); break; // grigio
        }
        tvVerdict.setBackgroundColor(bg);
        tvVerdict.setText(message);
    }

    // -------------------------------------------------------------------------
    // Lista top-N affondi peggiori (tap → centra grafico)
    // -------------------------------------------------------------------------

    private void populateTopDrops(List<CriticalDrop> drops) {
        llTopDrops.removeAllViews();
        if (drops == null || drops.isEmpty()) return;

        // Ordina per dz più negativo (più severo prima)
        List<CriticalDrop> sorted = new ArrayList<>(drops);
        Collections.sort(sorted, new Comparator<CriticalDrop>() {
            @Override
            public int compare(CriticalDrop a, CriticalDrop b) {
                return Float.compare(a.dz, b.dz); // più negativo = prima
            }
        });

        int n = Math.min(TOP_DROPS_MAX, sorted.size());

        // Header
        TextView header = new TextView(getContext());
        header.setText(String.format(Locale.US,
                "Top %d affondi (tap per centrare sul grafico)", n));
        header.setTextColor(Color.parseColor("#555555"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        header.setPadding(0, 2, 0, 4);
        llTopDrops.addView(header);

        for (int i = 0; i < n; i++) {
            final CriticalDrop d = sorted.get(i);
            TextView row = new TextView(getContext());
            float worst = Math.abs(d.dz);
            // Colore proporzionale alla severità
            int fg;
            if (worst >= 2f * threshold)      fg = Color.parseColor("#B71C1C");
            else if (worst >= 1.5f * threshold) fg = Color.parseColor("#E65100");
            else                              fg = Color.parseColor("#F9A825");

            String txt = String.format(Locale.US,
                    "%2d. riga %-5d  Z: %+7.2f → %+7.2f   ΔZ: %+6.2f mm",
                    i + 1, d.lineNumber, d.zBefore, d.zAfter, d.dz);
            row.setText(txt);
            row.setTextColor(fg);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            row.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.setPadding(8, 4, 8, 4);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> zChartView.centerOnIndex(d.segmentIdx));
            llTopDrops.addView(row);
        }
    }

    // -------------------------------------------------------------------------
    // Risultato analisi
    // -------------------------------------------------------------------------

    private static class AnalyzeResult {
        final List<Float>         zValues     = new ArrayList<>();
        final List<Float>         dzValues    = new ArrayList<>();
        final List<Integer>       lineNumbers = new ArrayList<>();
        final List<Integer>       criticalIdx = new ArrayList<>();
        final List<CriticalDrop>  drops       = new ArrayList<>();
    }

    /**
     * Dettagli di un singolo affondo critico, usato dalla lista top-N.
     */
    private static class CriticalDrop {
        int   segmentIdx;
        int   lineNumber;
        float zBefore;
        float zAfter;
        float dz;
    }

    // -------------------------------------------------------------------------
    // Helpers GCode — identici a GcodeRenderer per coerenza
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

    private void updateThresholdLabel() {
        tvThreshold.setText(String.format(Locale.US, "%.1f", threshold));
    }
}
