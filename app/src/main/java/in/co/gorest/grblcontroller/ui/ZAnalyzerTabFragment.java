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

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    private ZChartView  zChartView;
    private TextView    tvThreshold;
    private TextView    tvZInfo;

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
        tvThreshold = view.findViewById(R.id.tvThreshold);
        tvZInfo     = view.findViewById(R.id.tvZInfo);

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

                while ((line = reader.readLine()) != null) {
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

                        // Critico se discesa brusca (dz negativo oltre soglia)
                        if (dz < -threshold) {
                            result.criticalIdx.add(result.zValues.size() - 1);
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
            zChartView.setData(result.zValues, result.dzValues,
                               result.criticalIdx, threshold);
        }
    }

    // -------------------------------------------------------------------------
    // Risultato analisi
    // -------------------------------------------------------------------------

    private static class AnalyzeResult {
        final List<Float>   zValues     = new ArrayList<>();
        final List<Float>   dzValues    = new ArrayList<>();
        final List<Integer> criticalIdx = new ArrayList<>();
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
