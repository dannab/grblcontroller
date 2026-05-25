/*
 * Copyright (C) 2017 Grbl Controller Contributors (zeevy)
 * https://github.com/zeevy/grblcontroller
 * Modifications Copyright (C) 2026 Daniele Cicchinelli
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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.databinding.DataBindingUtil;

import com.joanzapata.iconify.widget.IconButton;
import com.joanzapata.iconify.widget.IconToggleButton;
import com.warkiz.widget.IndicatorSeekBar;
import com.warkiz.widget.OnSeekChangeListener;
import com.warkiz.widget.SeekParams;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentJoggingTabBinding;
import in.co.gorest.grblcontroller.events.GrblOkEvent;
import in.co.gorest.grblcontroller.events.JogCommandEvent;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.helpers.RepeatListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.util.GcodeLeveling;
import in.co.gorest.grblcontroller.util.GrblUtils;


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
import android.view.View;



public class JoggingTabFragment extends BaseFragment implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = JoggingTabFragment.class.getSimpleName();

    /** Nome del file dove vengono salvate le coordinate dei punti di piazzamento */
    private static final String POINTS_FILE_NAME = "points.txt";

    private MachineStatusListener machineStatus;
    private EnhancedSharedPreferences sharedPref;
    private BlockingQueue<Integer> completedCommands;
    private CustomCommandsAsyncTask customCommandsAsyncTask;

    /**
     * Buffer in memoria delle coordinate salvate.
     * FIX: inizializzato a "" invece di null per evitare la riga "null"
     * all'inizio del file alla prima pressione del tasto.
     * Viene pre-caricato dal file esistente in onCreate() per persistere
     * i punti tra sessioni successive.
     */
    private String pointsCoords = "";

    /**
     * Valori possibili per il ciclo step XY e Z.
     * Click breve cicla: 1.0 -> 0.1 -> 0.01 -> 1.0 ...
     * Long click: imposta 1.0 direttamente.
     */
    private static final double[] STEP_CYCLE = {1.0, 0.1, 0.01};
    private int stepCycleIndex = 0;
    private IconButton btnStepCycle;

    /**
     * Distanza grande per jog continuo: GRBL si muove fino al jog cancel (0x85).
     * Usata solo in modalità continuo.
     */
    private static final double JOG_CONTINUOUS_DISTANCE = 9999.0;

    /**
     * Ritardo (ms) prima che inizi la ripetizione automatica degli step
     * quando il pulsante viene tenuto premuto. Dopo questo ritardo viene
     * inviato un nuovo step ogni STEP_REPEAT_INTERVAL_MS.
     */
    private static final long STEP_REPEAT_INITIAL_DELAY_MS = 400;

    /**
     * Intervallo (ms) tra step ripetuti quando il pulsante è tenuto premuto.
     */
    private static final long STEP_REPEAT_INTERVAL_MS = 100;

    /** true se è attivo un jog continuo — serve per mandare cancel al rilascio */
    private boolean jogContinuousActive = false;

    /** true = modalità continuo (press = jog immediato, release = stop) */
    private boolean continuousModeEnabled = false;

    private IconButton jogCancelButton;

    public JoggingTabFragment() {}

    public static JoggingTabFragment newInstance() {
        return new JoggingTabFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListener.getInstance();
        sharedPref = EnhancedSharedPreferences.getInstance(
                requireActivity().getApplicationContext(),
                getString(R.string.shared_preference_key));
        EventBus.getDefault().register(this);

        // FIX: carica il file esistente in memoria all'avvio
        // così i punti precedenti non vengono persi se il fragment
        // viene ricreato (es. rotazione schermo, cambio tab)
        loadPointsFromFile();
    }

    @Override
    public void onResume() {
        super.onResume();
        SetCustomButtons(requireView());

        String[] jogTags = {"$J=%1$sG91X-%2$sY%2$sF%3$s", "$J=%sG91Y%sF%s", "$J=%1$sG91X%2$sY%2$sF%3$s",
                "$J=%sG91X-%sF%s", "$J=%sG91X%sF%s",
                "$J=%1$sG91X-%2$sY-%2$sF%3$s", "$J=%sG91Y-%sF%s", "$J=%1$sG91X%2$sY-%2$sF%3$s",
                "$J=%sG91A-%sF%s", "$J=%sG91A%sF%s"};
        int jogPadIndex = 0;
        for (int resourceId : new Integer[]{
                R.id.jog_xy_top_left, R.id.jog_y_positive, R.id.jog_xy_top_right,
                R.id.jog_x_negative, R.id.jog_x_positive,
                R.id.jog_xy_bottom_left, R.id.jog_y_negative, R.id.jog_xy_bottom_right,
                R.id.jog_a_negative, R.id.jog_a_positive}) {
            requireView().findViewById(resourceId).setTag(jogTags[jogPadIndex++]);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isAdded() && fragmentInteractionListener != null
                && machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)) {
            fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_JOG_CANCEL_COMMAND);
        }
        jogContinuousActive = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    private void sendJogCancelRobust() {
        fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_JOG_CANCEL_COMMAND);
        requireView().postDelayed(() -> {
            if (isAdded() && machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)) {
                fragmentInteractionListener.onGrblRealTimeCommandReceived(GrblUtils.GRBL_JOG_CANCEL_COMMAND);
            }
        }, 60);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        FragmentJoggingTabBinding binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_jogging_tab, container, false);
        binding.setMachineStatus(machineStatus);
        View view = binding.getRoot();

        RelativeLayout joggingStepFeedView = view.findViewById(R.id.jogging_step_feed_view);
        joggingStepFeedView.setOnClickListener(this);

        for (int resourceId : new Integer[]{
                R.id.jog_y_positive, R.id.jog_x_positive, R.id.jog_z_positive,
                R.id.jog_xy_top_left, R.id.jog_xy_top_right,
                R.id.jog_xy_bottom_left, R.id.jog_xy_bottom_right,
                R.id.jog_y_negative, R.id.jog_x_negative, R.id.jog_z_negative,
                R.id.jog_a_positive, R.id.jog_a_negative}) {

            final IconButton iconButton = view.findViewById(resourceId);

            // Comportamento:
            //  - Modalità STEP (default): tap = 1 step singolo, hold = ripetizione
            //    automatica dello stesso step finché il tasto resta premuto.
            //    Niente più soglia temporale: lo step parte sempre al DOWN,
            //    poi si ripete da solo dopo STEP_REPEAT_INITIAL_DELAY_MS e
            //    successivamente ogni STEP_REPEAT_INTERVAL_MS.
            //  - Modalità CONTINUO: press = jog continuo, release = jog cancel.
            iconButton.setOnTouchListener((v, event) -> {
                if (!isAdded()) return false;

                switch (event.getAction()) {

                    case android.view.MotionEvent.ACTION_DOWN:
                        iconButton.removeCallbacks(null);
                        jogContinuousActive = false;

                        if (continuousModeEnabled) {
                            // Modalità continuo: jog immediato senza attesa
                            sendJogContinuous(iconButton.getTag().toString());
                            jogContinuousActive = true;
                        } else {
                            // Modalità step: invia subito uno step, poi avvia
                            // la ripetizione automatica finché il tasto è premuto.
                            final String tag = iconButton.getTag().toString();
                            sendJogCommand(tag);

                            iconButton.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (iconButton.isPressed() && isAdded()) {
                                        sendJogCommand(tag);
                                        iconButton.postDelayed(this, STEP_REPEAT_INTERVAL_MS);
                                    }
                                }
                            }, STEP_REPEAT_INITIAL_DELAY_MS);
                        }
                        return false;

                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        iconButton.removeCallbacks(null);
                        if (jogContinuousActive) {
                            sendJogCancelRobust();
                            jogContinuousActive = false;
                        }
                        return false;
                }
                return false;
            });
        }

        for (int resourceId : new Integer[]{
                R.id.jog_cancel,R.id.wpos_g54,
                R.id.goto_x_zero, R.id.goto_y_zero, R.id.goto_z_zero, R.id.goto_a_zero,
                R.id.get_point,R.id.run_homing_cycle,R.id.do_leveling}) {
            IconButton iconButton = view.findViewById(resourceId);
            iconButton.setOnClickListener(this);
            iconButton.setOnLongClickListener(this);
        }

        jogCancelButton = view.findViewById(R.id.jog_cancel);

        // btn_step_cycle: click breve = cicla step, long click = imposta 1.0
        btnStepCycle = view.findViewById(R.id.btn_step_cycle);
        updateStepCycleButton();
        btnStepCycle.setOnClickListener(v -> cycleStep());
        btnStepCycle.setOnLongClickListener(v -> {
            stepCycleIndex = 0; // torna a 1.0
            applyStep(STEP_CYCLE[stepCycleIndex]);
            return true;
        });

        TableRow resetZeroLayout = view.findViewById(R.id.reset_zero_layout);
        for (int i = 0; i < resetZeroLayout.getChildCount(); i++) {
            View resetZeroLayoutView = resetZeroLayout.getChildAt(i);
            if (resetZeroLayoutView instanceof IconButton
                    || resetZeroLayoutView instanceof IconToggleButton) {
                resetZeroLayoutView.setOnClickListener(view12 -> {
                    final String tag = view12.getTag().toString();
                    if (tag.equals(GrblUtils.GRBL_KILL_ALARM_LOCK_COMMAND)) {
                        if (!machineStatus.getState().equals(Constants.MACHINE_STATUS_RUN)) {
                            fragmentInteractionListener.onGcodeCommandReceived(tag);
                        }
                        return;
                    }
                    new AlertDialog.Builder(getActivity())
                            .setTitle(getString(R.string.text_zero_selected_axis))
                            .setMessage(getString(R.string.text_set_axis_location_in_current_wpos) + tag)
                            .setPositiveButton(getString(R.string.text_yes_confirm),
                                    (dialog, which) -> sendCommandIfIdle(tag))
                            .setNegativeButton(getString(R.string.text_no_confirm), null)
                            .show();
                });
            }
        }

//        TableRow wposLayout = view.findViewById(R.id.wpos_layout);
//        for (int i = 0; i < wposLayout.getChildCount(); i++) {
//            View wposLayoutView = wposLayout.getChildAt(i);
//            if (wposLayoutView instanceof Button) {
//                wposLayoutView.setOnClickListener(view13 -> {
//                    if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
//                        sendCommandIfIdle(view13.getTag().toString());
//                        sendCommandIfIdle(GrblUtils.GRBL_VIEW_PARSER_STATE_COMMAND);
//                        EventBus.getDefault().post(new UiToastEvent(
//                                getString(R.string.text_selected_coordinate_system)
//                                        + view13.getTag().toString()));
//                    } else {
//                        EventBus.getDefault().post(new UiToastEvent(
//                                getString(R.string.text_machine_not_idle), true, true));
//                    }
//                });
//                wposLayoutView.setOnLongClickListener(this);
//            }
//        }

        SeekBar feedSlider = view.findViewById(R.id.jog_feed_slider);
        TextView feedValueLabel = view.findViewById(R.id.jog_feed_value_label);
        Double maxFeed = sharedPref.getDouble(getString(R.string.preference_jogging_max_feed_rate), 2400.0);
        feedSlider.setMax(maxFeed.intValue());
        feedSlider.setProgress(machineStatus.getJogging().feed.intValue());
        feedValueLabel.setText(String.valueOf(machineStatus.getJogging().feed.intValue()));
        feedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    feedValueLabel.setText(String.valueOf(progress));
                    machineStatus.setJogging(
                            machineStatus.getJogging().stepXY,
                            machineStatus.getJogging().stepZ,
                            (double) progress,
                            sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sharedPref.edit().putDouble(getString(R.string.preference_jogging_feed_rate),
                        seekBar.getProgress()).commit();
            }
        });

        return view;
    }

    private void SetCustomButtons(View view) {
        TableRow customButtonLayout = view.findViewById(R.id.custom_button_layout);
        if (customButtonLayout == null) return;

        if (sharedPref.getBoolean(getString(R.string.preference_enable_custom_buttons), false)) {
            customButtonLayout.setVisibility(View.VISIBLE);

            for (int resourceId : new Integer[]{
                    R.id.custom_button_1, R.id.custom_button_2,
                    R.id.custom_button_3, R.id.custom_button_4}) {

                IconButton iconButton = view.findViewById(resourceId);

                if (resourceId == R.id.custom_button_1)
                    iconButton.setText(sharedPref.getString(getString(R.string.preference_custom_button_one), getString(R.string.text_value_na)));
                if (resourceId == R.id.custom_button_2)
                    iconButton.setText(sharedPref.getString(getString(R.string.preference_custom_button_two), getString(R.string.text_value_na)));
                if (resourceId == R.id.custom_button_3)
                    iconButton.setText(sharedPref.getString(getString(R.string.preference_custom_button_three), getString(R.string.text_value_na)));
                if (resourceId == R.id.custom_button_4)
                    iconButton.setText(sharedPref.getString(getString(R.string.preference_custom_button_four), getString(R.string.text_value_na)));

                iconButton.setOnLongClickListener(this);
                iconButton.setOnClickListener(this);
            }
        } else {
            customButtonLayout.setVisibility(View.GONE);
        }
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View view) {
        int id = view.getId();

        switch (id) {
            case R.id.jogging_step_feed_view:
                this.setJoggingStepAndFeed();
                return;

            case R.id.jog_cancel:
                continuousModeEnabled = !continuousModeEnabled;
                jogCancelButton.setText(continuousModeEnabled
                        ? "{fa-arrows 22dp @color/colorAccent}"
                        : "{fa-stop-circle-o 26dp @color/colorPrimary}");
                break;

            case R.id.run_homing_cycle:
                // Long click: homing cycle (spostato da run_homing_cycle)
                if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)
                        || machineStatus.getState().equals(Constants.MACHINE_STATUS_ALARM)) {
                    new AlertDialog.Builder(getActivity())
                            .setTitle(getString(R.string.text_homing_cycle))
                            .setMessage(getString(R.string.text_do_homing_cycle))
                            .setPositiveButton(getString(R.string.text_yes_confirm),
                                    (dialog, which) -> fragmentInteractionListener
                                            .onGcodeCommandReceived(GrblUtils.GRBL_RUN_HOMING_CYCLE))
                            .setNegativeButton(getString(R.string.text_no_confirm), null)
                            .show();
                } else {
                    EventBus.getDefault().post(new UiToastEvent(
                            getString(R.string.text_machine_not_idle), true, true));
                }
                break;

            case R.id.do_leveling:
                break;
            case R.id.get_point:
                /**
                 * SALVATAGGIO PUNTO DI PIAZZAMENTO
                 * ---------------------------------
                 * Al tocco breve: salva le coordinate work position correnti
                 * (X, Y, Z) nel file points.txt nella memoria esterna dell'app.
                 *
                 * Il file è in formato testo, una riga per punto:
                 *   X=10.000 Y=25.500 Z=-2.000
                 *   X=30.000 Y=15.000 Z=-2.000
                 *
                 * Utile per memorizzare i punti di piazzamento dei pezzi
                 * durante operazioni di foratura o fresatura seriale.
                 *
                 * Il file viene mantenuto tra sessioni successive.
                 * Per cancellarlo: tocco lungo sul pulsante.
                 */
                saveCurrentPoint();
                break;

            case R.id.goto_a_zero: {
                // Click breve: azzera WPos asse A nella posizione corrente
                // Stessa logica di goto_x/y/z_zero nel reset_zero_layout
                final String tag = view.getTag().toString();
                new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.text_zero_selected_axis))
                        .setMessage(getString(R.string.text_set_axis_location_in_current_wpos) + tag)
                        .setPositiveButton(getString(R.string.text_yes_confirm),
                                (dialog, which) -> sendCommandIfIdle(tag))
                        .setNegativeButton(getString(R.string.text_no_confirm), null)
                        .show();
                break;
            }

            case R.id.custom_button_1:
            case R.id.custom_button_2:
            case R.id.custom_button_3:
            case R.id.custom_button_4:
                customButton(id, false);
                break;
        }
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onLongClick(View view) {
        int id = view.getId();

        switch (id) {
            case R.id.jog_cancel:
                // Long click: homing cycle (spostato da run_homing_cycle)

                return true;

            case R.id.wpos_g54:
            //case R.id.wpos_g55:
            //case R.id.wpos_g56:
            //case R.id.wpos_g57:
                saveWPos((Button) view);
                return true;

            case R.id.goto_x_zero:
                gotoAxisZero("X");
                return true;

            case R.id.goto_y_zero:
                gotoAxisZero("Y");
                return true;

            case R.id.goto_z_zero:
                gotoAxisZero("Z");
                return true;

            case R.id.goto_a_zero:
                gotoAxisZero("A");
                return true;

            case R.id.get_point:
                /**
                 * CANCELLAZIONE FILE PUNTI DI PIAZZAMENTO
                 * ----------------------------------------
                 * Al tocco lungo: mostra un dialog di conferma e,
                 * se confermato, cancella il file points.txt e
                 * svuota il buffer in memoria.
                 *
                 * Questa operazione è irreversibile.
                 */
                deletePointsFile();
                return true;

            case R.id.do_leveling:
               generaFileLivellato();
                break;

            case R.id.custom_button_1:
            case R.id.custom_button_2:
            case R.id.custom_button_3:
            case R.id.custom_button_4:
                customButton(id, true);
                return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Gestione file punti di piazzamento
    // -------------------------------------------------------------------------

    /**
     * Salva le coordinate work position correnti nel buffer e nel file.
     * Formato riga CSV: "10.000,25.500,-2.000\n"
     */
    private void saveCurrentPoint() {
        double x = machineStatus.getWorkPosition().getCordX();
        double y = machineStatus.getWorkPosition().getCordY();
        double z = machineStatus.getWorkPosition().getCordZ();

        // Formato CSV: X,Y,Z — compatibile con software CAD/CAM
        String newPoint = String.valueOf(x) + ',' + String.valueOf(y) + ',' + String.valueOf(z) + "\n";

        pointsCoords += newPoint;

        File pointsFile = getPointsFile();
        if (pointsFile == null) return;

        try (FileOutputStream fos = new FileOutputStream(pointsFile)) {
            fos.write(pointsCoords.getBytes());
            fos.flush();
            EventBus.getDefault().post(new UiToastEvent(
                    "Punto salvato: " + newPoint.trim(), true, false));
        } catch (IOException e) {
            Log.e(TAG, "Errore scrittura points.txt: " + e.getMessage());
            EventBus.getDefault().post(new UiToastEvent(
                    "Errore salvataggio punto", true, true));
        }
    }

    /**
     * Mostra un dialog di conferma e cancella il file points.txt.
     * Svuota anche il buffer in memoria.
     */
    private void deletePointsFile() {
        new AlertDialog.Builder(getActivity())
                .setTitle("Cancella punti di piazzamento")
                .setMessage("Vuoi cancellare tutti i punti salvati in points.txt?\n"
                        + "L'operazione è irreversibile.")
                .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                    File pointsFile = getPointsFile();
                    if (pointsFile != null && pointsFile.exists()) {
                        if (pointsFile.delete()) {
                            pointsCoords = "";
                            EventBus.getDefault().post(new UiToastEvent(
                                    "File points.txt cancellato.", true, false));
                        } else {
                            EventBus.getDefault().post(new UiToastEvent(
                                    "Errore durante la cancellazione.", true, true));
                        }
                    } else {
                        pointsCoords = "";
                        EventBus.getDefault().post(new UiToastEvent(
                                "Nessun file da cancellare.", true, false));
                    }
                })
                .setNegativeButton(getString(R.string.text_no_confirm), null)
                .show();
    }
    private void generaFileLivellato() {
        File appDir = getAppMediaDir();
        File pointsFile = new File(appDir, "points.txt");

        // Recupera il file attualmente attivo (senza modificarne lo stato nell'app)
        File currentGcodeFile = in.co.gorest.grblcontroller.listeners.FileSenderListener.getInstance().getGcodeFile();

        if (currentGcodeFile == null) {
            org.greenrobot.eventbus.EventBus.getDefault().post(
                    new in.co.gorest.grblcontroller.events.UiToastEvent("Nessun file selezionato nel File Sender!", true, true));
            return;
        }

        // Chiamata all'utility di calcolo
        in.co.gorest.grblcontroller.util.GcodeLeveling.applyAutolevel(pointsFile, currentGcodeFile, new in.co.gorest.grblcontroller.util.GcodeLeveling.LevelingCallback() {
            @Override
            public void onSuccess(File leveledFile) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        // Notifica l'utente del completamento.
                        // Ora potrà andare nel primo tab, fare "Seleziona File" e troverà il file pronto.
                        org.greenrobot.eventbus.EventBus.getDefault().post(
                                new in.co.gorest.grblcontroller.events.UiToastEvent(
                                        "File compensato creato: " + leveledFile.getName(), true, false));
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        org.greenrobot.eventbus.EventBus.getDefault().post(
                                new in.co.gorest.grblcontroller.events.UiToastEvent(error, true, true));
                    });
                }
            }
        });
    }
    /**
     * Carica il contenuto del file points.txt in memoria all'avvio.
     * FIX: evita di perdere i punti precedenti quando il fragment
     * viene ricreato (rotazione schermo, cambio tab, ecc.).
     */
    private void loadPointsFromFile() {
        File pointsFile = getPointsFile();
        if (pointsFile == null || !pointsFile.exists()) {
            pointsCoords = "";
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(pointsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            pointsCoords = sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Errore lettura points.txt: " + e.getMessage());
            pointsCoords = "";
        }
    }

    /**
     * Restituisce il File per points.txt nella directory esterna dell'app.
     * Restituisce null se la directory non è disponibile.
     */
    private File getAppMediaDir() {
        if (getActivity() == null) return null;
        File[] dirs = getActivity().getExternalMediaDirs();
        if (dirs == null || dirs.length == 0 || dirs[0] == null) return null;
        if (!dirs[0].exists()) dirs[0].mkdirs();
        return dirs[0];
    }

    private File getPointsFile() {
        File dir = getAppMediaDir();
        if (dir == null) return null;
        return new File(dir, POINTS_FILE_NAME);
    }

    // -------------------------------------------------------------------------
    // Resto del fragment — invariato rispetto all'originale
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Step cycle — pulsante centrale pad direzionale
    // -------------------------------------------------------------------------

    /**
     * Cicla il valore step tra 1.0 -> 0.1 -> 0.01 -> 1.0
     * Aggiorna XY e Z insieme e aggiorna il testo del pulsante.
     */
    private void cycleStep() {
        stepCycleIndex = (stepCycleIndex + 1) % STEP_CYCLE.length;
        applyStep(STEP_CYCLE[stepCycleIndex]);
    }

    /**
     * Applica il valore step a XY, Z e A e aggiorna MachineStatus e SharedPreferences.
     */
    private void applyStep(double step) {
        machineStatus.setJogging(
                step,
                step,
                step,
                machineStatus.getJogging().feed,
                sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));

        sharedPref.edit()
                .putDouble(getString(R.string.preference_jogging_step_size), step)
                .putDouble(getString(R.string.preference_jogging_step_size_z), step)
                .putDouble(getString(R.string.preference_jogging_step_size_a), step)
                .commit();

        updateStepCycleButton();
    }

    /**
     * Aggiorna il testo del pulsante centrale con il valore step corrente.
     * Mostra il numero senza zeri inutili: 1, 0.1, 0.01
     */
    private void updateStepCycleButton() {
        if (btnStepCycle == null) return;
        double step = STEP_CYCLE[stepCycleIndex];
        String label;
        if (step >= 1.0) {
            label = "1";
        } else if (step >= 0.1) {
            label = "0.1";
        } else {
            label = "0.01";
        }
        btnStepCycle.setText(label);
    }

    private void customButton(int resourceId, boolean isLongClick) {
        if (!machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_machine_not_idle), true, true));
            return;
        }

        String title = "";
        String commands = "";
        boolean confirmFirst = true;

        if (resourceId == R.id.custom_button_1) {
            title = sharedPref.getString(getString(R.string.preference_custom_button_one), getString(R.string.text_value_na));
            commands = isLongClick
                    ? sharedPref.getString(getString(R.string.preference_custom_button_one_long_click), "")
                    : sharedPref.getString(getString(R.string.preference_custom_button_one_short_click), "");
            confirmFirst = sharedPref.getBoolean(getString(R.string.preference_custom_button_one_confirm), true);
        }
        if (resourceId == R.id.custom_button_2) {
            title = sharedPref.getString(getString(R.string.preference_custom_button_two), getString(R.string.text_value_na));
            commands = isLongClick
                    ? sharedPref.getString(getString(R.string.preference_custom_button_two_long_click), "")
                    : sharedPref.getString(getString(R.string.preference_custom_button_two_short_click), "");
            confirmFirst = sharedPref.getBoolean(getString(R.string.preference_custom_button_two_confirm), true);
        }
        if (resourceId == R.id.custom_button_3) {
            title = sharedPref.getString(getString(R.string.preference_custom_button_three), getString(R.string.text_value_na));
            commands = isLongClick
                    ? sharedPref.getString(getString(R.string.preference_custom_button_three_long_click), "")
                    : sharedPref.getString(getString(R.string.preference_custom_button_three_short_click), "");
            confirmFirst = sharedPref.getBoolean(getString(R.string.preference_custom_button_three_confirm), true);
        }
        if (resourceId == R.id.custom_button_4) {
            title = sharedPref.getString(getString(R.string.preference_custom_button_four), getString(R.string.text_value_na));
            commands = isLongClick
                    ? sharedPref.getString(getString(R.string.preference_custom_button_four_long_click), "")
                    : sharedPref.getString(getString(R.string.preference_custom_button_four_short_click), "");
            confirmFirst = sharedPref.getBoolean(getString(R.string.preference_custom_button_four_confirm), true);
        }

        if (commands.trim().length() <= 0) {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_empty_command), true, true));
            return;
        }

        final String finalCommands = commands;

        if (confirmFirst) {
            String alertSummary = isLongClick
                    ? getString(R.string.text_long_click)
                    : getString(R.string.text_short_click);
            new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.text_custom_action) + title)
                    .setMessage(getString(R.string.text_send_custom_command)
                            + alertSummary + getString(R.string.text_on_button) + title)
                    .setPositiveButton(getString(R.string.text_send), (dialog, which) -> {
                        customCommandsAsyncTask = new CustomCommandsAsyncTask();
                        customCommandsAsyncTask.execute(finalCommands);
                    })
                    .setNegativeButton(getString(R.string.text_cancel), null)
                    .show();
        } else {
            customCommandsAsyncTask = new CustomCommandsAsyncTask();
            customCommandsAsyncTask.execute(finalCommands);
        }
    }

    private class CustomCommandsAsyncTask extends AsyncTask<String, Integer, Integer> {

        private int MAX_RX_SERIAL_BUFFER = Constants.DEFAULT_SERIAL_RX_BUFFER - 3;
        private int CURRENT_RX_SERIAL_BUFFER;
        private LinkedList<Integer> activeCommandSizes;

        protected void onPreExecute() {
            MachineStatusListener.CompileTimeOptions compileTimeOptions =
                    MachineStatusListener.getInstance().getCompileTimeOptions();
            if (compileTimeOptions.serialRxBuffer > 0)
                MAX_RX_SERIAL_BUFFER = compileTimeOptions.serialRxBuffer - 3;

            completedCommands = new ArrayBlockingQueue<>(Constants.DEFAULT_SERIAL_RX_BUFFER);
            activeCommandSizes = new LinkedList<>();
            CURRENT_RX_SERIAL_BUFFER = 0;
        }

        protected Integer doInBackground(String... commands) {
            String[] lines = commands[0].split("[\r\n]+");
            for (String command : lines) {
                if (isCancelled()) break;
                streamLine(command);
            }
            return 1;
        }

        private void streamLine(String gcodeCommand) {
            int commandSize = gcodeCommand.length() + 1;
            while (MAX_RX_SERIAL_BUFFER < (CURRENT_RX_SERIAL_BUFFER + commandSize)) {
                try {
                    completedCommands.take();
                    if (activeCommandSizes.size() > 0)
                        CURRENT_RX_SERIAL_BUFFER -= activeCommandSizes.removeFirst();
                } catch (InterruptedException e) {
                    Log.e(TAG, e.getMessage(), e);
                    return;
                }
            }
            activeCommandSizes.offer(commandSize);
            CURRENT_RX_SERIAL_BUFFER += commandSize;
            fragmentInteractionListener.onGcodeCommandReceived(gcodeCommand);
        }
    }

    private void gotoAxisZero(final String axis) {
        new AlertDialog.Builder(getActivity())
                .setTitle(getString(R.string.text_move) + axis
                        + getString(R.string.text_axis_to_zero_position))
                .setMessage(getString(R.string.text_go_to_zero_position) + axis + "0")
                .setPositiveButton(getString(R.string.text_yes_confirm),
                        (dialog, which) -> sendCommandIfIdle("G0 " + axis + "0"))
                .setNegativeButton(getString(R.string.text_no_confirm), null)
                .show();
    }

    private void saveWPos(Button button) {
        String wpos = button.getTag().toString();
        final String slot;
        switch (wpos) {
            case "G54": slot = "P1"; break;
            case "G56": slot = "P3"; break;
            case "G57": slot = "P4"; break;
            default:    slot = "P2"; break;
        }
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.text_save_coordinate_system)
                .setMessage(getString(R.string.text_save_coordinate_system_desc) + " " + wpos + "?")
                .setPositiveButton(getString(R.string.text_yes_confirm),
                        (dialog, which) -> sendCommandIfIdle(
                                String.format("G10 L20 %s X0Y0Z0", slot)))
                .setNegativeButton(getString(R.string.text_no_confirm), null)
                .show();
    }

    private void sendJogCommand(String tag) {
        if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)
                || machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)) {

            String units = "G21";
            double jogFeed = machineStatus.getJogging().feed;

            if (machineStatus.getJogging().inches) {
                units = "G20";
                jogFeed = jogFeed / 25.4;
            }

            String upperTag = tag.toUpperCase();
            Double stepSize;
            if (upperTag.contains("A")) {
                stepSize = machineStatus.getJogging().stepA;
            } else if (upperTag.contains("Z")) {
                stepSize = machineStatus.getJogging().stepZ;
            } else {
                stepSize = machineStatus.getJogging().stepXY;
            }

            String jog = String.format(tag, units, stepSize, jogFeed);
            EventBus.getDefault().post(new JogCommandEvent(jog));
        } else {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_machine_not_idle), true, true));
        }
    }

    /**
     * Invia un comando di jog continuo verso la direzione indicata dal tag.
     * Usa una distanza molto grande (9999mm) — GRBL si muove indefinitamente
     * fino a quando non riceve il jog cancel (0x85).
     * Il movimento è completamente fluido perché GRBL gestisce
     * internamente accelerazione e decelerazione.
     *
     * @param tag formato del comando jog (es. "$J=%sG91X%sF%s")
     */
    private void sendJogContinuous(String tag) {
        if (!machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)
                && !machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)) {
            return;
        }

        String units = "G21";
        double jogFeed = machineStatus.getJogging().feed;

        if (machineStatus.getJogging().inches) {
            units = "G20";
            jogFeed = jogFeed / 25.4;
        }

        // Per il jog continuo usiamo sempre la distanza massima
        // Il segno (+ o -) è nel tag, la distanza è sempre positiva e grande
        String jog = String.format(tag, units, JOG_CONTINUOUS_DISTANCE, jogFeed);
        EventBus.getDefault().post(new JogCommandEvent(jog));
    }

    @SuppressLint("NonConstantResourceId")
    private void setJoggingStepAndFeed() {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.dialog_step_and_feed, null, false);

        final IndicatorSeekBar jogStepSeekBarXY = view.findViewById(R.id.jog_xy_step_seek_bar);
        jogStepSeekBarXY.setProgress(machineStatus.getJogging().stepXY.floatValue());
        jogStepSeekBarXY.setMax(sharedPref.getInt(getString(R.string.preference_jogging_max_step_size), 10));
        jogStepSeekBarXY.setIndicatorTextFormat("XY: ${PROGRESS}");
        jogStepSeekBarXY.setDecimalScale(3);

        for (final int resourceId : new Integer[]{
                R.id.jog_xy_step_small, R.id.jog_xy_step_medium, R.id.jog_xy_step_high}) {
            final IconButton iconButton = view.findViewById(resourceId);

            iconButton.setOnLongClickListener(v -> {
                new AlertDialog.Builder(getActivity())
                        .setTitle("Save Quick Button Value")
                        .setMessage("do you want to save the quick button value as "
                                + jogStepSeekBarXY.getProgressFloat())
                        .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                            EnhancedSharedPreferences.Editor editor = sharedPref.edit();
                            editor.putString(iconButton.getTag().toString(),
                                    Float.toString(jogStepSeekBarXY.getProgressFloat())).commit();
                        })
                        .setNegativeButton(getString(R.string.text_no_confirm), null)
                        .show();
                return true;
            });

            iconButton.setOnClickListener(v -> {
                if (isAdded()) {
                    String stepValue = sharedPref.getString(iconButton.getTag().toString(), "0");
                    if (stepValue.equals("0")) {
                        switch (resourceId) {
                            case R.id.jog_xy_step_small:  stepValue = "0.01"; break;
                            case R.id.jog_xy_step_medium: stepValue = "0.1";  break;
                            case R.id.jog_xy_step_high:   stepValue = "1";    break;
                        }
                    }
                    if (stepValue.length() > 0) {
                        float step_value = Float.parseFloat(stepValue);
                        if (step_value > jogStepSeekBarXY.getMax()) {
                            EventBus.getDefault().post(new UiToastEvent(
                                    "Value is grater than the bar size", true, true));
                            return;
                        }
                        jogStepSeekBarXY.setProgress(step_value);
                        EventBus.getDefault().post(new UiToastEvent(
                                "XY Axis step value is set to " + step_value));
                        sharedPref.edit().putDouble(getString(R.string.preference_jogging_step_size),
                                Double.parseDouble(Float.toString(step_value))).commit();
                    } else {
                        EventBus.getDefault().post(new UiToastEvent(
                                "Invalid step size value, please check settings", true, true));
                    }
                }
            });
        }

        jogStepSeekBarXY.setOnSeekChangeListener(new OnSeekChangeListener() {
            @Override
            public void onSeeking(SeekParams seekParams) {
                machineStatus.setJogging(
                        Double.parseDouble(Float.toString(seekParams.progressFloat)),
                        machineStatus.getJogging().stepZ,
                        machineStatus.getJogging().feed,
                        sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));
            }
            @Override public void onStartTrackingTouch(IndicatorSeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(IndicatorSeekBar seekBar) {
                sharedPref.edit().putDouble(getString(R.string.preference_jogging_step_size),
                        Double.parseDouble(Float.toString(seekBar.getProgressFloat()))).commit();
            }
        });

        final IndicatorSeekBar jogStepSeekBarZ = view.findViewById(R.id.jog_z_step_seek_bar);
        jogStepSeekBarZ.setProgress(machineStatus.getJogging().stepZ.floatValue());
        jogStepSeekBarZ.setMax(sharedPref.getInt(
                getString(R.string.preference_jogging_max_step_size_z), 5));
        jogStepSeekBarZ.setIndicatorTextFormat("Z: ${PROGRESS}");
        jogStepSeekBarZ.setDecimalScale(3);

        for (final int resourceId : new Integer[]{
                R.id.jog_z_step_small, R.id.jog_z_step_medium, R.id.jog_z_step_high}) {
            final IconButton iconButton = view.findViewById(resourceId);

            iconButton.setOnLongClickListener(v -> {
                new AlertDialog.Builder(getActivity())
                        .setTitle("Save Quick Button Value")
                        .setMessage("do you want to save the quick button value as "
                                + jogStepSeekBarZ.getProgressFloat())
                        .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                            sharedPref.edit().putString(iconButton.getTag().toString(),
                                    Float.toString(jogStepSeekBarZ.getProgressFloat())).commit();
                        })
                        .setNegativeButton(getString(R.string.text_no_confirm), null)
                        .show();
                return true;
            });

            iconButton.setOnClickListener(v -> {
                if (isAdded()) {
                    String stepValue = sharedPref.getString(iconButton.getTag().toString(), "0");
                    if (stepValue.equals("0")) {
                        switch (resourceId) {
                            case R.id.jog_z_step_small:  stepValue = "0.01"; break;
                            case R.id.jog_z_step_medium: stepValue = "0.1";  break;
                            case R.id.jog_z_step_high:   stepValue = "1";    break;
                        }
                    }
                    if (stepValue.length() > 0) {
                        float step_value = Float.parseFloat(stepValue);
                        if (step_value > jogStepSeekBarZ.getMax()) {
                            EventBus.getDefault().post(new UiToastEvent(
                                    "Value is grater than the bar size", true, true));
                            return;
                        }
                        jogStepSeekBarZ.setProgress(step_value);
                        EventBus.getDefault().post(new UiToastEvent(
                                "Z Axis step value is set to " + step_value));
                        sharedPref.edit().putDouble(getString(R.string.preference_jogging_step_size_z),
                                Double.parseDouble(Float.toString(step_value))).commit();
                    } else {
                        EventBus.getDefault().post(new UiToastEvent(
                                "Invalid step size value, please check settings", true, true));
                    }
                }
            });
        }

        jogStepSeekBarZ.setOnSeekChangeListener(new OnSeekChangeListener() {
            @Override
            public void onSeeking(SeekParams seekParams) {
                machineStatus.setJogging(
                        machineStatus.getJogging().stepXY,
                        Double.parseDouble(Float.toString(seekParams.progressFloat)),
                        machineStatus.getJogging().feed,
                        sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));
            }
            @Override public void onStartTrackingTouch(IndicatorSeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(IndicatorSeekBar seekBar) {
                sharedPref.edit().putDouble(getString(R.string.preference_jogging_step_size_z),
                        Double.parseDouble(Float.toString(seekBar.getProgressFloat()))).commit();
            }
        });

        IndicatorSeekBar jogFeedSeekBar = view.findViewById(R.id.jog_feed_seek_bar);
        jogFeedSeekBar.setProgress(machineStatus.getJogging().feed.floatValue());
        Double maxFeedRate = sharedPref.getDouble(
                getString(R.string.preference_jogging_max_feed_rate), 2400.00);
        jogFeedSeekBar.setMax(Float.parseFloat(maxFeedRate.toString()));
        jogFeedSeekBar.setIndicatorTextFormat("Feed: ${PROGRESS}");

        jogFeedSeekBar.setOnSeekChangeListener(new OnSeekChangeListener() {
            @Override
            public void onSeeking(SeekParams seekParams) {
                machineStatus.setJogging(
                        machineStatus.getJogging().stepXY,
                        machineStatus.getJogging().stepZ,
                        seekParams.progress,
                        sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));
            }
            @Override public void onStartTrackingTouch(IndicatorSeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(IndicatorSeekBar seekBar) {
                sharedPref.edit().putDouble(getString(R.string.preference_jogging_feed_rate),
                        seekBar.getProgress()).commit();
            }
        });

        SwitchCompat jogInches = view.findViewById(R.id.jog_inches);
        jogInches.setChecked(sharedPref.getBoolean(
                getString(R.string.preference_jogging_in_inches), false));
        jogInches.setOnCheckedChangeListener((compoundButton, b) -> {
            machineStatus.setJogging(machineStatus.getJogging().stepXY,
                    machineStatus.getJogging().stepZ,
                    machineStatus.getJogging().feed, b);
            sharedPref.edit().putBoolean(
                    getString(R.string.preference_jogging_in_inches), b).commit();
        });

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.text_ok), (d, id) -> {})
                .create();
        dialog.setCancelable(false);
        dialog.show();
    }



    private void sendCommandIfIdle(String command) {
        if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
            fragmentInteractionListener.onGcodeCommandReceived(command);
        } else {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_machine_not_idle), true, true));
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGrblOkEvent(GrblOkEvent event) {
        if (customCommandsAsyncTask != null
                && customCommandsAsyncTask.getStatus() == AsyncTask.Status.RUNNING) {
            completedCommands.offer(1);
        }
    }
}