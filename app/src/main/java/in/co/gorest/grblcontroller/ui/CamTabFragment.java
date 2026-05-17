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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.joanzapata.iconify.widget.IconButton;

import org.greenrobot.eventbus.EventBus;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentCamTabBinding;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.util.SimpleGcodeMaker;

public class CamTabFragment extends BaseFragment {

    private MachineStatusListener machineStatus;
    private EnhancedSharedPreferences sharedPref;

    private TextView camFeedRate;
    private TextView camZTraversal;
    private TextView camStepOver;
    private TextView camZDeep;
    private TextView camZStep;
    private TextView camFromText;
    private TextView camToText;
    private TextView camToolDia;
    private String editIcon;

    private Double Xto   = 0.0, Yto   = 0.0, Zto   = 0.0;
    private Double Xfrom = 0.0, Yfrom = 0.0, Zfrom = 0.0;
    private int jobType = 0;

    // Flag: l'utente ha esplicitamente impostato From e To
    private boolean fromSet = false;
    private boolean toSet   = false;

    private String gcodeToSave;

    private final ActivityResultLauncher<Intent> createFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null && gcodeToSave != null) {
                                writeGcodeToUri(uri, gcodeToSave);
                                gcodeToSave = null;
                            }
                        } else {
                            EventBus.getDefault().post(
                                    new UiToastEvent("Salvataggio file annullato.", true, true));
                        }
                    });

    public CamTabFragment() {}

    public static CamTabFragment newInstance() {
        return new CamTabFragment();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListener.getInstance();
        sharedPref = EnhancedSharedPreferences.getInstance(
                requireActivity().getApplicationContext(),
                getString(R.string.shared_preference_key));
        this.editIcon = " {fa-edit 16sp}";
    }

    // NOTA: EventBus non è usato in questo fragment — rimossi register/unregister
    // per evitare crash da unregister senza register corrispondente.

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        FragmentCamTabBinding binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_cam_tab, container, false);
        binding.setMachineStatus(machineStatus);
        View view = binding.getRoot();

        // --- Viste ---
        camFromText   = view.findViewById(R.id.cam_from_text);
        camToText     = view.findViewById(R.id.cam_to_text);
        camFeedRate   = view.findViewById(R.id.cam_feed_rate);
        camZTraversal = view.findViewById(R.id.cam_z_traversal);
        camStepOver   = view.findViewById(R.id.cam_step_over);
        camZDeep      = view.findViewById(R.id.cam_z_deep);
        camZStep      = view.findViewById(R.id.cam_z_step);
        camToolDia    = view.findViewById(R.id.cam_tool_dia);

        // --- Valori iniziali da SharedPreferences ---
        camFeedRate.setText(sharedPref.getString(getString(R.string.preference_cam_feed_rate),
                String.valueOf(Constants.CAM_FEED_RATE)) + editIcon);
        camZTraversal.setText(sharedPref.getString(getString(R.string.preference_cam_z_traversal),
                String.valueOf(Constants.CAM_TRAVERSAL)) + editIcon);
        camStepOver.setText(sharedPref.getString(getString(R.string.preference_cam_step_over),
                String.valueOf(Constants.CAM_STEP_OVER)) + editIcon);
        camZDeep.setText(sharedPref.getString(getString(R.string.preference_cam_z_deep),
                String.valueOf(Constants.CAM_ZDEEP)) + editIcon);
        camZStep.setText(sharedPref.getString(getString(R.string.preference_cam_z_step),
                String.valueOf(Constants.CAM_ZSTEP)) + editIcon);
        camToolDia.setText(sharedPref.getString(getString(R.string.preference_cam_tool_dia),
                String.valueOf(Constants.CAM_TOOL_DIA)) + editIcon);

        // --- Click sui campi editabili ---
        view.findViewById(R.id.cam_feed_rate_view).setOnClickListener(v -> setCamFeedRate());
        view.findViewById(R.id.cam_z_traversal_view).setOnClickListener(v -> setCamZTraversal());
        view.findViewById(R.id.cam_step_over_view).setOnClickListener(v -> setCamStepOver());
        view.findViewById(R.id.cam_z_deep_view).setOnClickListener(v -> setCamZDeep());
        view.findViewById(R.id.cam_z_step_view).setOnClickListener(v -> setCamZStep());
        view.findViewById(R.id.cam_tool_dia_view).setOnClickListener(v -> setCamToolDia());

        // --- Spinner tipo lavorazione ---
        Spinner jobTypeSpinner = view.findViewById(R.id.job_type_spinner);
        jobTypeSpinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                jobType = position;
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { jobType = 0; }
        });

        // --- Pulsante calcola ---
        IconButton startCamCalc = view.findViewById(R.id.start_cam_calc);
        startCamCalc.setOnClickListener(v ->
                new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.text_start_cam_calc))
                        .setMessage(getString(R.string.text_start_cam_calc_desc))
                        .setPositiveButton(getString(R.string.text_yes_confirm),
                                (d, w) -> doCamCalculation())
                        .setNegativeButton(getString(R.string.text_cancel), null)
                        .show());

        // --- Pulsante From ---
        IconButton camFrom = view.findViewById(R.id.cam_from);
        camFrom.setOnClickListener(v ->
                new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.text_cam_from_title))
                        .setMessage(getString(R.string.text_cam_from_message))
                        .setPositiveButton(getString(R.string.text_ok), (d, w) -> setCamFrom())
                        .setNegativeButton(getString(R.string.text_cancel), null)
                        .show());

        // --- Pulsante To ---
        IconButton camTo = view.findViewById(R.id.cam_to);
        camTo.setOnClickListener(v ->
                new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.text_cam_to_title))
                        .setMessage(getString(R.string.text_cam_to_message))
                        .setPositiveButton(getString(R.string.text_ok), (d, w) -> setCamTo())
                        .setNegativeButton(getString(R.string.text_cancel), null)
                        .show());

        // --- Help ---
        view.findViewById(R.id.cam_help).setOnClickListener(v -> showCamHelp());

        return view;
    }

    // -------------------------------------------------------------------------
    // Calcolo GCode
    // -------------------------------------------------------------------------

    private void doCamCalculation() {

        // Validazione: From e To devono essere stati impostati
        if (!fromSet || !toSet) {
            EventBus.getDefault().post(new UiToastEvent(
                    "Imposta prima i punti FROM e TO.", true, true));
            return;
        }

        // Validazione: area non nulla
        if (Xfrom.equals(Xto) && Yfrom.equals(Yto)) {
            EventBus.getDefault().post(new UiToastEvent(
                    "FROM e TO coincidono — area di lavoro nulla.", true, true));
            return;
        }

        try {
            // FIX: parseSignedDouble conserva il segno meno (es. Z = -10.0)
            double zTraversal = parseSignedDouble(camZTraversal);
            double camZStepVal = parseSignedDouble(camZStep);
            double camZDeepVal = parseSignedDouble(camZDeep);
            double camFeedRateVal = parseSignedDouble(camFeedRate);
            double camToolDiaVal = parseSignedDouble(camToolDia);
            double stepOver = parseSignedDouble(camStepOver);

            if (camZStepVal <= camZDeepVal) {
                EventBus.getDefault().post(new UiToastEvent(
                        getString(R.string.error_z_step_greater_than_z_deep), true, true));
                return;
            }

            SimpleGcodeMaker gcodemaker = new SimpleGcodeMaker(
                    Xfrom, Yfrom, Xto, Yto, Zfrom,
                    camZStepVal, camZDeepVal, zTraversal,
                    camFeedRateVal / 2, camFeedRateVal, true);

            String gcode;
            switch (jobType) {
                case 0:  gcode = gcodemaker.snakeX(stepOver, true); break;
                case 1:  gcode = gcodemaker.snakeY(stepOver, true); break;
                case 2:  gcode = gcodemaker.cutRectangle(stepOver, true, true); break;
                case 3:  gcode = gcodemaker.cutRectangle(stepOver, true, false); break;
                case 4:  gcode = gcodemaker.circleCut(stepOver, true, true); break;
                case 5:  gcode = gcodemaker.circleCut(stepOver, true, false); break;
                case 6:  gcode = gcodemaker.cutRectangleContour(true); break;
                case 7:  gcode = gcodemaker.circleCut(0.0, true, true); break;
                case 8:  gcode = gcodemaker.corneringCut(camToolDiaVal); break;
                case 9:  gcode = gcodemaker.lineCut(); break;
                default: gcode = ""; break;
            }

            if (gcode.isEmpty()) {
                EventBus.getDefault().post(new UiToastEvent(
                        "Nessun GCode generato.", true, true));
                return;
            }

            this.gcodeToSave = gcode;
            launchCreateFileIntent();

        } catch (NumberFormatException e) {
            EventBus.getDefault().post(new UiToastEvent(
                    "Valore non valido nei parametri CAM: " + e.getMessage(), true, true));
        }
    }

    // -------------------------------------------------------------------------
    // Parsing numeri con segno — FIX principale rispetto all'originale
    // -------------------------------------------------------------------------

    /**
     * Estrae il valore numerico da una TextView che contiene anche icone FontAwesome.
     * A differenza dell'originale, conserva il segno meno per valori negativi (es. Z=-10).
     */
    private double parseSignedDouble(TextView tv) throws NumberFormatException {
        String raw = tv.getText().toString();
        // Rimuovi tutto tranne cifre, punto decimale e segno meno iniziale
        raw = raw.replaceAll("[^\\d.\\-]", "").trim();
        // Se ci sono più segni meno (artefatto), tieni solo il primo carattere se è -
        if (raw.indexOf('-') > 0) {
            raw = raw.replaceAll("-", "");
        }
        if (raw.isEmpty()) throw new NumberFormatException("Campo vuoto");
        return Double.parseDouble(raw);
    }

    // -------------------------------------------------------------------------
    // SAF — salvataggio file
    // -------------------------------------------------------------------------

    private void launchCreateFileIntent() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "job1.nc");
        createFileLauncher.launch(intent);
    }

    private void writeGcodeToUri(Uri uri, String gcodeData) {
        try {
            ParcelFileDescriptor pfd = requireActivity()
                    .getContentResolver().openFileDescriptor(uri, "w");
            if (pfd != null) {
                FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                fos.write(gcodeData.getBytes());
                fos.close();
                pfd.close();
                EventBus.getDefault().post(new UiToastEvent(
                        "File salvato: " + uri.getLastPathSegment(), true, true));
            }
        } catch (IOException e) {
            EventBus.getDefault().post(new UiToastEvent(
                    "Errore scrittura file: " + e.getMessage(), true, true));
        }
    }

    // -------------------------------------------------------------------------
    // Impostazione From / To
    // -------------------------------------------------------------------------

    private void setCamFrom() {
        if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
            Xfrom = machineStatus.getWorkPosition().getCordX();
            Yfrom = machineStatus.getWorkPosition().getCordY();
            Zfrom = machineStatus.getWorkPosition().getCordZ();
            fromSet = true;
            camFromText.setText(String.format(Locale.US, "%.3f, %.3f, %.3f", Xfrom, Yfrom, Zfrom));
        } else {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_machine_not_idle), true, true));
        }
    }

    private void setCamTo() {
        if (machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)) {
            Xto = machineStatus.getWorkPosition().getCordX();
            Yto = machineStatus.getWorkPosition().getCordY();
            Zto = machineStatus.getWorkPosition().getCordZ();
            toSet = true;
            camToText.setText(String.format(Locale.US, "%.3f, %.3f, %.3f", Xto, Yto, Zto));
        } else {
            EventBus.getDefault().post(new UiToastEvent(
                    getString(R.string.text_machine_not_idle), true, true));
        }
    }

    // -------------------------------------------------------------------------
    // Dialog impostazioni parametri
    // -------------------------------------------------------------------------

    private void setCamFeedRate() {
        showDecimalDialog(
                getString(R.string.text_cam_feedrate_title),
                getString(R.string.text_cam_feedrate_message),
                sharedPref.getString(getString(R.string.preference_cam_feed_rate), "1000.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_feed_rate), value).apply();
                    camFeedRate.setText(value + editIcon);
                });
    }

    private void setCamZTraversal() {
        showDecimalDialog(
                getString(R.string.text_cam_traversal_title),
                getString(R.string.text_cam_traversal_message),
                sharedPref.getString(getString(R.string.preference_cam_z_traversal), "5.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_z_traversal), value).apply();
                    camZTraversal.setText(value + editIcon);
                });
    }

    private void setCamStepOver() {
        showDecimalDialog(
                getString(R.string.text_cam_step_over_title),
                getString(R.string.text_cam_step_over_message),
                sharedPref.getString(getString(R.string.preference_cam_step_over), "1.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_step_over), value).apply();
                    camStepOver.setText(value + editIcon);
                });
    }

    private void setCamZDeep() {
        showDecimalDialog(
                getString(R.string.text_facing_zdeep_title),
                getString(R.string.text_facing_zdeep_message),
                sharedPref.getString(getString(R.string.preference_cam_z_deep), "0.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_z_deep), value).apply();
                    camZDeep.setText(value + editIcon);
                });
    }

    private void setCamZStep() {
        showDecimalDialog(
                getString(R.string.cam_zstep_title),
                getString(R.string.text_cam_zstep_desc),
                sharedPref.getString(getString(R.string.preference_cam_z_step), "0.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_z_step), value).apply();
                    camZStep.setText(value + editIcon);
                });
    }

    private void setCamToolDia() {
        showDecimalDialog(
                getString(R.string.text_cam_tool_dia),
                getString(R.string.text_cam_tool_dia_desc),
                sharedPref.getString(getString(R.string.preference_cam_tool_dia), "10.0"),
                (value) -> {
                    sharedPref.edit().putString(getString(R.string.preference_cam_tool_dia), value).apply();
                    camToolDia.setText(value + editIcon);
                });
    }

    /**
     * Dialog generica per input decimale — elimina la duplicazione di codice
     * presente nell'originale (6 metodi quasi identici → 1 metodo parametrico).
     */
    private interface OnValueConfirmed { void onConfirmed(String value); }

    private void showDecimalDialog(String title, String message,
                                   String currentValue, OnValueConfirmed callback) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View v = inflater.inflate(R.layout.dialog_input_decimal, null, false);

        EditText editText = v.findViewById(R.id.dialog_input_decimal);
        editText.setText(currentValue);
        editText.setSelection(editText.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setView(v)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.text_yes_confirm), (d, id) -> {
                    String val = editText.getText().toString().trim();
                    if (val.isEmpty()) val = "0";
                    callback.onConfirmed(val);
                })
                .setNegativeButton(getString(R.string.text_cancel), null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private void showCamHelp() {
        new AlertDialog.Builder(getActivity())
                .setTitle(getString(R.string.text_cam_title_help))
                .setMessage(R.string.text_cam_help)
                .setPositiveButton(getString(R.string.text_ok), null)
                .setCancelable(false)
                .show();
    }
}
