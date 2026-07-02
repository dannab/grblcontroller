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
package in.co.gorest.grblcontroller.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;

import com.joanzapata.iconify.widget.IconButton;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import in.co.gorest.grblcontroller.BR;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentGcodeEditorBinding;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;

public class GcodeEditorFragment extends BaseFragment {

    private MachineStatusListener machineStatus;
    private FileSenderListener fileSender;

    private WebView webView;
    private TextView fileNameText;
    private IconButton btnSave;
    private RelativeLayout loadingOverlay;
    private File currentGcodeFile;
    private boolean isEditorLoaded = false;

    /**
     * Riga (1-based) richiesta dall'esterno (es. "Run from line" nel File Sender)
     * su cui posizionare il cursore appena l'editor ha caricato il file.
     * Statica perché il fragment potrebbe non esistere ancora quando arriva la
     * richiesta (ViewPager offscreen limit). 0 = nessuna richiesta.
     */
    private static volatile int pendingGotoLine = 0;

    public static void requestGotoLine(int line) {
        pendingGotoLine = line;
    }

    /**
     * Callback registrato su MachineStatusListener: ogni cambio di stato
     * macchina riapplica la policy "editing solo in IDLE".
     * Riferimento tenuto per poterlo deregistrare in onDestroyView.
     */
    private Observable.OnPropertyChangedCallback machineStateCallback;

    public GcodeEditorFragment() {}

    public static GcodeEditorFragment newInstance() {
        return new GcodeEditorFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListener.getInstance();
        fileSender = FileSenderListener.getInstance();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FragmentGcodeEditorBinding binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_gcode_editor, container, false);
        binding.setMachineStatus(machineStatus);
        View view = binding.getRoot();

        fileNameText = view.findViewById(R.id.editor_file_name);
        loadingOverlay = view.findViewById(R.id.loading_overlay);
        webView = view.findViewById(R.id.gcode_webview);

        // Impostazioni WebView per ottimizzare l'esecuzione JS
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isEditorLoaded = true;
                // Una volta che la pagina HTML è pronta, applica subito la policy
                // di sola lettura in base allo stato corrente, poi carica il file.
                applyReadOnlyForCurrentState();
                checkAndLoadActiveFile();
            }
        });

        // Carica l'editor locale
        webView.loadUrl("file:///android_asset/gcode_editor.html");

        // Click handler dei pulsanti in cima
        IconButton btnSearch = view.findViewById(R.id.btn_editor_search);
        btnSearch.setOnClickListener(v -> {
            if (isEditorLoaded) {
                webView.evaluateJavascript("openSearch();", null);
            }
        });

        btnSave = view.findViewById(R.id.btn_editor_save);
        btnSave.setOnClickListener(v -> saveCurrentGcode());

        // Osserva i cambi di stato macchina per aggiornare la sola lettura
        machineStateCallback = new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable sender, int propertyId) {
                if (propertyId == BR.state || propertyId == BR._all) {
                    applyReadOnlyForCurrentState();
                }
            }
        };
        machineStatus.addOnPropertyChangedCallback(machineStateCallback);

        return view;
    }

    /**
     * Editing permesso solo quando lo stato macchina è IDLE.
     * Aggiorna la WebView (Ace) e il pulsante Save, e mostra "(R/O)" davanti
     * al nome file quando il salvataggio è bloccato.
     */
    private void applyReadOnlyForCurrentState() {
        if (webView == null) return;
        boolean idle = isMachineIdle();
        webView.evaluateJavascript("setEditorReadOnly(" + (!idle) + ");", null);

        if (btnSave != null) {
            btnSave.setEnabled(idle);
            btnSave.setAlpha(idle ? 1.0f : 0.4f);
        }
        if (fileNameText != null) {
            String base = currentGcodeFile != null
                    ? currentGcodeFile.getName()
                    : getString(R.string.text_no_file_in_sender);
            fileNameText.setText(idle ? base : "(R/O) " + base);
        }
    }

    private boolean isMachineIdle() {
        return machineStatus != null
                && Constants.MACHINE_STATUS_IDLE.equals(machineStatus.getState());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (machineStateCallback != null && machineStatus != null) {
            machineStatus.removeOnPropertyChangedCallback(machineStateCallback);
            machineStateCallback = null;
        }
        // Reset degli stati legati alla view: quando il ViewPager ricrea il fragment
        // questi devono ripartire da zero altrimenti l'editor resta vuoto
        isEditorLoaded = false;
        currentGcodeFile = null;
        webView = null;
        btnSave = null;
        fileNameText = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isEditorLoaded) {
            applyReadOnlyForCurrentState();
            checkAndLoadActiveFile();
            // Se il file era già caricato (stesso file) checkAndLoadActiveFile non
            // ricarica nulla: consumiamo qui l'eventuale richiesta di goto line.
            consumePendingGotoLine();
        }
    }

    /**
     * Se c'è una richiesta pendente di posizionamento su una riga (da "Run from
     * line"), la inoltra all'editor ACE e la azzera. No-op se l'editor non è
     * pronto o non c'è richiesta.
     */
    private void consumePendingGotoLine() {
        if (!isEditorLoaded || webView == null) return;
        int line = pendingGotoLine;
        if (line <= 0) return;
        pendingGotoLine = 0;
        webView.evaluateJavascript("gotoLine(" + line + ");", null);
    }

    /**
     * Verifica se esiste un file selezionato dentro FileSenderListener
     * e avvia il thread di lettura se differisce da quello visualizzato.
     */
    private void checkAndLoadActiveFile() {
        File activeFile = fileSender.getGcodeFile(); // Recupero riferimento da FileSenderListener
        if (activeFile != null) {
            if (currentGcodeFile == null || !currentGcodeFile.getAbsolutePath().equals(activeFile.getAbsolutePath())) {
                currentGcodeFile = activeFile;
                applyReadOnlyForCurrentState(); // setta anche il testo del nome file con eventuale prefisso (R/O)
                loadGcodeFileAsync(currentGcodeFile);
            }
        } else {
            currentGcodeFile = null;
            applyReadOnlyForCurrentState();
            if (isEditorLoaded) {
                webView.evaluateJavascript("setGcodeContent('');", null);
            }
        }
    }

    /**
     * Lettura asincrona per evitare blocchi ANR con file superiori a 100k righe
     */
    private void loadGcodeFileAsync(File file) {
        loadingOverlay.setVisibility(View.VISIBLE);
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String content = sb.toString();
                String escaped = content.replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("$", "\\$");

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        webView.evaluateJavascript("setGcodeContent(`" + escaped + "`);", null);
                        loadingOverlay.setVisibility(View.GONE);
                        consumePendingGotoLine();
                    });
                } else {
                    // Fragment non più visibile durante il caricamento —
                    // reset del file corrente così al prossimo onResume verrà ricaricato
                    currentGcodeFile = null;
                }
            } catch (IOException e) {
                currentGcodeFile = null;
                postUiToast(getString(R.string.text_file_read_error, e.getMessage()), true);
                if (isAdded()) {
                    requireActivity().runOnUiThread(() ->
                            loadingOverlay.setVisibility(View.GONE));
                }
            }
        }).start();
    }

    /**
     * Recupera il testo modificato dall'editor web e sovrascrive in sicurezza
     * il file puntato da FileSenderListener.
     */
    private void saveCurrentGcode() {
        if (currentGcodeFile == null || !isEditorLoaded) {
            postUiToast(getString(R.string.text_no_file_to_save), true);
            return;
        }

        // Salvataggio bloccato se la macchina non è IDLE — il file potrebbe essere
        // in lettura dal FileSender e modificarlo durante un job è pericoloso.
        if (!isMachineIdle()) {
            postUiToast(getString(R.string.text_machine_not_idle), true);
            return;
        }

        loadingOverlay.setVisibility(View.VISIBLE);

        // Chiediamo ad Ace Editor il contenuto testuale aggiornato
        webView.evaluateJavascript("getGcodeContent();", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                // WebView.evaluateJavascript restituisce una stringa JSON formattata (inclusi doppi apici agli estremi)
                if (value == null || value.equals("null")) {
                    loadingOverlay.setVisibility(View.GONE);
                    return;
                }

                // Decodifica la stringa JSON grezza arrivata dalla webview
                final String unescapedContent = jsonUnescape(value);

                new Thread(() -> {
                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(currentGcodeFile))) {
                        bw.write(unescapedContent);
                        bw.flush();
                        postUiToast(getString(R.string.text_file_saved_synced), false);
                    } catch (IOException e) {
                        postUiToast(getString(R.string.text_file_save_error, e.getMessage()), true);
                    } finally {
                        if (isAdded()) {
                            final File saved = currentGcodeFile;
                            requireActivity().runOnUiThread(() -> {
                                // setGcodeFile chiama notifyPropertyChanged — deve stare sul main thread
                                FileSenderListener.getInstance().setGcodeFile(saved);
                                FileSenderListener.getInstance().setElapsedTime("00:00:00");
                                new FileSenderTabFragment.ReadFileAsyncTask().execute(saved);
                                //fileSender.setGcodeFile(saved);
                                loadingOverlay.setVisibility(View.GONE);
                            });
                        }
                    }
                }).start();
            }
        });
    }

    /**
     * Rimuove la formattazione JSON generata da evaluateJavascript (es: apici iniziali/finali, \n trasformati, ecc)
     */
    private String jsonUnescape(String jsonStr) {
        if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
            jsonStr = jsonStr.substring(1, jsonStr.length() - 1);
        }
        return jsonStr.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private void postUiToast(String msg, boolean isError) {
        EventBus.getDefault().post(new UiToastEvent(msg, true, isError));
    }
}