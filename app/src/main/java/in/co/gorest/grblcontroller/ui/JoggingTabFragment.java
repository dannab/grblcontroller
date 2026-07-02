/*
 * Copyright (C) 2017 Grbl Controller Contributors (zeevy)
 * https://github.com/zeevy/grblcontroller
 * Modifications Copyright (C) 2024-2026 Daniele Cicchinelli
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
package in.co.gorest.grblcontroller.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.joanzapata.iconify.widget.IconButton;

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

public class JoggingTabFragment extends BaseFragment implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = JoggingTabFragment.class.getSimpleName();

    /** Nome del file dove vengono salvate le coordinate dei punti di piazzamento */
    private static final String POINTS_FILE_NAME = "points.txt";

    private MachineStatusListener machineStatus;
    private EnhancedSharedPreferences sharedPref;
    private BlockingQueue<Integer> completedCommands;
    private CustomCommandsAsyncTask customCommandsAsyncTask;

    /**
     * Sistema di coordinate (G54..G57) attualmente attivo lato app.
     * È solo una memoria locale: l'unica fonte di verità su quale sia attivo
     * davvero in GRBL è il parser state ($G), ma noi ci limitiamo a ricordare
     * l'ultima scelta fatta dall'utente e a tenere il pulsante allineato.
     */
    private static final String[] WPOS_SYSTEMS = {"G54", "G55", "G56", "G57"};
    private String activeWpos = WPOS_SYSTEMS[0];
    /** Pulsante che mostra/permette di cambiare il coord system attivo. */
    private IconButton btnWposSelect;

    /**
     * Buffer in memoria delle coordinate salvate.
     * FIX: inizializzato a "" invece di null per evitare la riga "null"
     * all'inizio del file alla prima pressione del tasto.
     * Viene pre-caricato dal file esistente in onCreate() per persistere
     * i punti tra sessioni successive.
     */
    private String pointsCoords = "";

    /**
     * Valori possibili per il ciclo step.
     * Click breve cicla: 1.0 -> 0.1 -> 0.01 -> 1.0 ...
     * Il pulsante centrale del pad XY cicla per XY+A; il pulsante in colonna Z
     * (tra Z+ e Z-) cicla per Z. Due indici separati per non far interferire i due gruppi.
     */
    private static final double[] STEP_CYCLE = {1.0, 0.1, 0.01};
    private int stepCycleIndexXYA = 0;
    private int stepCycleIndexZ = 0;

    /** Pulsante centrale pad XY — cicla step XY+A, long-click toggla continuous XY+A. */
    private IconButton btnStepCycle;
    /** Pulsante in colonna Z (ex jog_cancel) — cicla step Z, long-click toggla continuous Z. */
    private IconButton btnStepCycleZ;

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

    /**
     * Modalità continuous indipendenti per i due gruppi di assi:
     *  - XYA: pad direzionale XY + tasti A+/A-
     *  - Z:   solo Z+ / Z-
     * In modalità continuous press = jog immediato verso il fondoscala,
     * release = jog cancel.
     */
    private boolean continuousModeXYA = false;
    private boolean continuousModeZ = false;

    /** Pulsante torcia (flash fotocamera) — non legato allo stato GRBL. */
    private IconButton btnTorch;
    /** Stato corrente della torcia: true = accesa. */
    private boolean torchOn = false;
    /** Id della camera con flash, risolto la prima volta che serve. */
    private String torchCameraId;

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

        // Ripristina l'ultima scelta del sistema di coordinate. Default G54
        // se non c'è ancora nulla salvato o se il valore salvato è corrotto.
        String saved = sharedPref.getString(
                getString(R.string.preference_active_wpos), WPOS_SYSTEMS[0]);
        activeWpos = isValidWpos(saved) ? saved : WPOS_SYSTEMS[0];

        // Stato di default all'avvio dell'app: stepping a 1mm su entrambi i gruppi
        // (XY+A e Z), modalità step (non continuous). Solo al primo onCreate —
        // se il fragment viene ricreato dopo una rotazione/config change rispettiamo
        // lo stato precedente.
        if (savedInstanceState == null) {
            stepCycleIndexXYA = 0;
            stepCycleIndexZ = 0;
            continuousModeXYA = false;
            continuousModeZ = false;
            applyStepXYA(STEP_CYCLE[0]);
            applyStepZ(STEP_CYCLE[0]);
        }

        // FIX: carica il file esistente in memoria all'avvio
        // così i punti precedenti non vengono persi se il fragment
        // viene ricreato (es. rotazione schermo, cambio tab)
        loadPointsFromFile();
    }

    private boolean isValidWpos(String s) {
        if (s == null) return false;
        for (String w : WPOS_SYSTEMS) if (w.equals(s)) return true;
        return false;
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

                        // Modalità continuous è per gruppo: il pulsante in colonna Z
                        // controlla continuousModeZ, tutti gli altri (XY diagonali, A)
                        // continuousModeXYA. Il discriminante è la lettera dell'asse nel tag.
                        boolean useContinuous = isZAxisTag(iconButton.getTag().toString())
                                ? continuousModeZ
                                : continuousModeXYA;

                        if (useContinuous) {
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

        // Pulsanti registrati esplicitamente per ID — ognuno con il proprio
        // short/long click. Niente più registrazione "per contenitore", così
        // spostare i tasti nel layout non li scollega più dalle funzioni.
        for (int resourceId : new Integer[]{
                R.id.get_point, R.id.run_homing_cycle, R.id.do_leveling}) {
            IconButton iconButton = view.findViewById(resourceId);
            iconButton.setOnClickListener(this);
            iconButton.setOnLongClickListener(this);
        }

        // Pulsante coordinate system (l'id è rimasto "wpos_g54" per compatibilità XML,
        // ma ora rappresenta un selettore tra G54/G55/G56/G57):
        //   short-click → dialog di selezione (single choice) e invio del G5x scelto
        //   long-click  → salva WPos corrente nel sistema attivo (saveWPos)
        btnWposSelect = view.findViewById(R.id.wpos_g54);
        btnWposSelect.setText(activeWpos);
        btnWposSelect.setTag(activeWpos);
        btnWposSelect.setOnClickListener(v -> showWposSelectionDialog());
        btnWposSelect.setOnLongClickListener(this);

        // Set-zero per asse: short-click = azzera WPos qui (con dialog), long-click = goto axis zero
        bindZeroAxisButton(view, R.id.goto_x_zero);
        bindZeroAxisButton(view, R.id.goto_y_zero);
        bindZeroAxisButton(view, R.id.goto_z_zero);
        bindZeroAxisButton(view, R.id.goto_a_zero);

        // Kill alarm lock ($X) — prima era agganciato iterando reset_zero_layout,
        // ora ha un ID dedicato e listener proprio.
        IconButton btnKillAlarmLock = view.findViewById(R.id.btn_kill_alarm_lock);
        if (btnKillAlarmLock != null) {
            btnKillAlarmLock.setOnClickListener(v -> {
                if (!machineStatus.getState().equals(Constants.MACHINE_STATUS_RUN)) {
                    fragmentInteractionListener.onGcodeCommandReceived(
                            GrblUtils.GRBL_KILL_ALARM_LOCK_COMMAND);
                }
            });
        }

        // btn_step_cycle (centro pad XY): controlla XY + A
        //   click       → cicla 1 → 0.1 → 0.01
        //   long-click  → toggla modalità STEP ↔ CONTINUOUS (per XY+A)
        btnStepCycle = view.findViewById(R.id.btn_step_cycle);
        btnStepCycle.setOnClickListener(v -> cycleStepXYA());
        btnStepCycle.setOnLongClickListener(v -> {
            continuousModeXYA = !continuousModeXYA;
            updateStepCycleButton();
            return true;
        });
        updateStepCycleButton();

        // jog_cancel (in colonna Z, tra Z+ e Z-): stesso ruolo di btn_step_cycle ma solo per Z
        //   click       → cicla 1 → 0.1 → 0.01 (asse Z)
        //   long-click  → toggla modalità STEP ↔ CONTINUOUS (asse Z)
        // NOTA: l'ID resta "jog_cancel" per non rompere il layout, ma la funzione
        //       di toggle-globale-continuous che aveva prima è stata sostituita.
        btnStepCycleZ = view.findViewById(R.id.jog_cancel);
        btnStepCycleZ.setOnClickListener(v -> cycleStepZ());
        btnStepCycleZ.setOnLongClickListener(v -> {
            continuousModeZ = !continuousModeZ;
            updateStepCycleZButton();
            return true;
        });
        updateStepCycleZButton();

        // Pulsante torcia (flash fotocamera Android). Click = accende/spegne.
        // Indipendente dallo stato GRBL, quindi resta sempre attivo.
        btnTorch = view.findViewById(R.id.btn_torch);
        if (btnTorch != null) {
            btnTorch.setOnClickListener(v -> toggleTorch());
            updateTorchButton();
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
                 * Il file è in formato CSV, una riga per punto:
                 *   10.0,25.5,-2.0
                 *   30.0,15.0,-2.0
                 *
                 * Utile per memorizzare i punti di piazzamento dei pezzi
                 * durante operazioni di foratura o fresatura seriale.
                 *
                 * Il file viene mantenuto tra sessioni successive.
                 * Per cancellarlo: tocco lungo sul pulsante.
                 */
                saveCurrentPoint();
                break;

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
            case R.id.wpos_g54:
                // Long click: salva la WPos corrente nel sistema attivo lato app
                saveWPos(activeWpos);
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
                    getString(R.string.text_point_saved, newPoint.trim()), true, false));
        } catch (IOException e) {
            Log.e(TAG, "Errore scrittura points.txt: " + e.getMessage());
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_point_save_error), true, true));
        }
    }

    /**
     * Mostra un dialog di conferma e cancella il file points.txt.
     * Svuota anche il buffer in memoria.
     */
    private void deletePointsFile() {
        new AlertDialog.Builder(getActivity())
                .setTitle(getString(R.string.text_clear_points_title))
                .setMessage(getString(R.string.text_clear_points_desc))
                .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                    File pointsFile = getPointsFile();
                    if (pointsFile != null && pointsFile.exists()) {
                        if (pointsFile.delete()) {
                            pointsCoords = "";
                            EventBus.getDefault().post(new UiToastEvent(
                                    getString(R.string.text_points_file_deleted), true, false));
                        } else {
                            EventBus.getDefault().post(new UiToastEvent(
                                    getString(R.string.text_points_delete_error), true, true));
                        }
                    } else {
                        pointsCoords = "";
                        EventBus.getDefault().post(new UiToastEvent(
                                getString(R.string.text_no_points_file), true, false));
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
                    new in.co.gorest.grblcontroller.events.UiToastEvent(getString(R.string.text_no_file_in_sender), true, true));
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
                                        getString(R.string.text_leveled_file_created, leveledFile.getName()), true, false));
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
     * Cicla lo step di XY+A tra 1.0 → 0.1 → 0.01 → 1.0.
     * Se è attiva la modalità CONTINUOUS, il click la disattiva e riporta
     * direttamente a stepping 1mm (uscita "di emergenza" intuitiva: tocco il
     * pulsante centrale e torno in stato sicuro/predefinito).
     * Z resta indipendente (gestito da {@link #cycleStepZ()}).
     */
    private void cycleStepXYA() {
        if (continuousModeXYA) {
            continuousModeXYA = false;
            stepCycleIndexXYA = 0;
        } else {
            stepCycleIndexXYA = (stepCycleIndexXYA + 1) % STEP_CYCLE.length;
        }
        applyStepXYA(STEP_CYCLE[stepCycleIndexXYA]);
    }

    /**
     * Cicla lo step di Z tra 1.0 → 0.1 → 0.01 → 1.0.
     * Stesso comportamento di {@link #cycleStepXYA()}: click in continuous
     * torna a stepping 1mm.
     */
    private void cycleStepZ() {
        if (continuousModeZ) {
            continuousModeZ = false;
            stepCycleIndexZ = 0;
        } else {
            stepCycleIndexZ = (stepCycleIndexZ + 1) % STEP_CYCLE.length;
        }
        applyStepZ(STEP_CYCLE[stepCycleIndexZ]);
    }

    /**
     * Applica step a XY e A lasciando Z invariato. Persiste su SharedPreferences
     * (stepXY e stepA condividono il valore — il pad XY e i tasti A+/A- usano lo
     * stesso ciclo).
     */
    private void applyStepXYA(double step) {
        boolean inches = sharedPref.getBoolean(
                getString(R.string.preference_jogging_in_inches), false);
        machineStatus.setJogging(
                step,
                machineStatus.getJogging().stepZ,
                step,
                machineStatus.getJogging().feed,
                inches);

        sharedPref.edit()
                .putDouble(getString(R.string.preference_jogging_step_size), step)
                .putDouble(getString(R.string.preference_jogging_step_size_a), step)
                .commit();

        updateStepCycleButton();
    }

    /**
     * Applica step solo a Z, lasciando XY e A invariati.
     */
    private void applyStepZ(double step) {
        boolean inches = sharedPref.getBoolean(
                getString(R.string.preference_jogging_in_inches), false);
        machineStatus.setJogging(
                machineStatus.getJogging().stepXY,
                step,
                machineStatus.getJogging().stepA,
                machineStatus.getJogging().feed,
                inches);

        sharedPref.edit()
                .putDouble(getString(R.string.preference_jogging_step_size_z), step)
                .commit();

        updateStepCycleZButton();
    }

    /**
     * Aggiorna il pulsante centrale XY:
     *  - in modalità STEP: mostra il valore corrente (1 / 0.1 / 0.01)
     *  - in modalità CONTINUOUS: mostra l'icona frecce quadridirezionali in colore accent
     */
    private void updateStepCycleButton() {
        if (btnStepCycle == null) return;
        if (continuousModeXYA) {
            btnStepCycle.setText("{fa-arrows 22dp @color/colorAccent}");
        } else {
            btnStepCycle.setText(formatStepLabel(STEP_CYCLE[stepCycleIndexXYA]));
        }
    }

    /**
     * Aggiorna il pulsante centrale Z (ex jog_cancel):
     *  - in modalità STEP: mostra "Z " + valore (Z1 / Z0.1 / Z0.01) — il prefisso
     *    chiarisce a colpo d'occhio che il pulsante riguarda l'asse Z
     *  - in modalità CONTINUOUS: mostra l'icona frecce verticali in colore accent
     */
    private void updateStepCycleZButton() {
        if (btnStepCycleZ == null) return;
        if (continuousModeZ) {
            btnStepCycleZ.setText("{fa-arrows-v 22dp @color/colorAccent}");
        } else {
            btnStepCycleZ.setText("Z" + formatStepLabel(STEP_CYCLE[stepCycleIndexZ]));
        }
    }

    /** "1", "0.1", "0.01" senza zeri inutili. */
    private String formatStepLabel(double step) {
        if (step >= 1.0) return "1";
        if (step >= 0.1) return "0.1";
        return "0.01";
    }

    /**
     * True se il tag del pulsante muove l'asse Z. Il tag è il template GRBL
     * passato a String.format, quindi cerco la lettera asse — uppercase per
     * insensibilità a maiuscole/minuscole nel template.
     */
    private boolean isZAxisTag(String tag) {
        return tag != null && tag.toUpperCase().contains("Z");
    }

    // -------------------------------------------------------------------------
    // Torcia (flash fotocamera Android)
    // -------------------------------------------------------------------------

    /**
     * Accende/spegne la torcia del dispositivo usando CameraManager.setTorchMode
     * (disponibile da API 23, in linea con minSdkVersion del progetto).
     * Risolve la prima camera dotata di flash e ne ricorda l'id per le chiamate
     * successive. In caso di errore (nessun flash, camera occupata, ecc.) mostra
     * un toast e lascia lo stato coerente.
     */
    private void toggleTorch() {
        if (getActivity() == null) return;

        CameraManager cameraManager =
                (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_torch_unavailable), true, true));
            return;
        }

        try {
            if (torchCameraId == null) {
                torchCameraId = findTorchCameraId(cameraManager);
            }
            if (torchCameraId == null) {
                EventBus.getDefault().post(new UiToastEvent(
                        getString(R.string.text_no_flash), true, true));
                return;
            }

            torchOn = !torchOn;
            cameraManager.setTorchMode(torchCameraId, torchOn);
            updateTorchButton();
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Errore torcia: " + e.getMessage());
            torchOn = false;
            updateTorchButton();
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_torch_error), true, true));
        }
    }

    /** Restituisce l'id della prima camera con flash, o null se nessuna. */
    private String findTorchCameraId(CameraManager cameraManager)
            throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (hasFlash != null && hasFlash) return id;
        }
        return null;
    }

    /**
     * Aggiorna l'icona del pulsante torcia: accesa = lampadina piena in colore
     * accent, spenta = lampadina vuota.
     */
    private void updateTorchButton() {
        if (btnTorch == null) return;
        btnTorch.setText(torchOn
                ? "{fa-lightbulb-o 26dp @color/colorAccent}"
                : "{fa-lightbulb-o 26dp}");
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

    /**
     * Aggancia un pulsante "azzera asse" cercandolo per ID dentro view.
     * Short-click: chiede conferma e invia il G10 L20 P0 X/Y/Z/A0 letto dal tag.
     * Long-click:  delega a {@link #onLongClick(View)} che chiama gotoAxisZero(...).
     *
     * Versione esplicita per-ID: prima era gestita iterando i figli di
     * reset_zero_layout, ma spostare i pulsanti nel layout li sganciava
     * silenziosamente dalla funzione.
     */
    private void bindZeroAxisButton(View parent, int buttonId) {
        IconButton button = parent.findViewById(buttonId);
        if (button == null) return;
        button.setOnClickListener(v -> {
            final String tag = v.getTag().toString();
            new AlertDialog.Builder(getActivity())
                    .setTitle(getString(R.string.text_zero_selected_axis))
                    .setMessage(getString(R.string.text_set_axis_location_in_current_wpos) + tag)
                    .setPositiveButton(getString(R.string.text_yes_confirm),
                            (dialog, which) -> sendCommandIfIdle(tag))
                    .setNegativeButton(getString(R.string.text_no_confirm), null)
                    .show();
        });
        button.setOnLongClickListener(this);
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

    /**
     * Mostra il dialog di conferma e, se accettato, salva la WPos corrente
     * nello slot G10 corrispondente al coord system passato.
     * Mappatura: G54→P1, G55→P2, G56→P3, G57→P4.
     */
    private void saveWPos(String wpos) {
        final String slot;
        switch (wpos) {
            case "G54": slot = "P1"; break;
            case "G55": slot = "P2"; break;
            case "G56": slot = "P3"; break;
            case "G57": slot = "P4"; break;
            default:    slot = "P1"; break;
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

    /**
     * Dialog di selezione del sistema di coordinate.
     * Mostra G54/G55/G56/G57 con quello attivo pre-selezionato.
     * Alla conferma invia il G5x scelto + un $G per riallineare il parser state,
     * salva la nuova scelta in SharedPreferences e aggiorna il pulsante.
     * Bloccato se la macchina non è in IDLE (sendCommandIfIdle se ne occupa).
     */
    private void showWposSelectionDialog() {
        int currentIdx = 0;
        for (int i = 0; i < WPOS_SYSTEMS.length; i++) {
            if (WPOS_SYSTEMS[i].equals(activeWpos)) { currentIdx = i; break; }
        }
        // Holder per la selezione provvisoria — single click sulla riga aggiorna
        // questo array; il commit avviene solo se l'utente preme OK.
        final int[] chosen = { currentIdx };

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.text_select_coordinate_system)
                .setSingleChoiceItems(WPOS_SYSTEMS, currentIdx,
                        (dialog, which) -> chosen[0] = which)
                .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                    String selected = WPOS_SYSTEMS[chosen[0]];
                    if (!machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
                        EventBus.getDefault().post(new UiToastEvent(
                                getString(R.string.text_machine_not_idle), true, true));
                        return;
                    }
                    fragmentInteractionListener.onGcodeCommandReceived(selected);
                    fragmentInteractionListener.onGcodeCommandReceived(
                            GrblUtils.GRBL_VIEW_PARSER_STATE_COMMAND);

                    activeWpos = selected;
                    sharedPref.edit()
                            .putString(getString(R.string.preference_active_wpos), selected)
                            .commit();
                    if (btnWposSelect != null) {
                        btnWposSelect.setText(selected);
                        btnWposSelect.setTag(selected);
                    }
                    EventBus.getDefault().post(new UiToastEvent(
                            getString(R.string.text_selected_coordinate_system) + selected));
                })
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