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

import com.joanzapata.iconify.widget.IconButton;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentGcodeEditorBinding;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;

public class GcodeEditorFragment extends BaseFragment {

    private MachineStatusListener machineStatus;
    private FileSenderListener fileSender;

    private WebView webView;
    private TextView fileNameText;
    private RelativeLayout loadingOverlay;
    private File currentGcodeFile;
    private boolean isEditorLoaded = false;

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
                // Una volta che la pagina HTML è pronta, carichiamo il file GCode attivo
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

        IconButton btnSave = view.findViewById(R.id.btn_editor_save);
        btnSave.setOnClickListener(v -> saveCurrentGcode());

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Se l'utente ha cambiato file in un altro tab, lo rinfreschiamo al ritorno nel fragment
        if (isEditorLoaded) {
            checkAndLoadActiveFile();
        }
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
                fileNameText.setText(currentGcodeFile.getName());
                loadGcodeFileAsync(currentGcodeFile);
            }
        } else {
            currentGcodeFile = null;
            fileNameText.setText("Nessun file nel File Sender");
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

                // Iniettiamo i dati puliti via JS nel thread della UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        // Escapizziamo i caratteri speciali JS prima del passaggio passandolo in sicurezza
                        String escaped = content.replace("\\", "\\\\")
                                .replace("`", "\\`")
                                .replace("$", "\\$");

                        webView.evaluateJavascript("setGcodeContent(`" + escaped + "`);", null);
                        loadingOverlay.setVisibility(View.GONE);
                    });
                }
            } catch (IOException e) {
                postUiToast("Errore lettura file: " + e.getMessage(), true);
            }
        }).start();
    }

    /**
     * Recupera il testo modificato dall'editor web e sovrascrive in sicurezza
     * il file puntato da FileSenderListener.
     */
    private void saveCurrentGcode() {
        if (currentGcodeFile == null || !isEditorLoaded) {
            postUiToast("Nessun file da salvare", true);
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

                        // Forza FileSenderListener a ricaricare lo stato del file modificato
                        fileSender.setGcodeFile(currentGcodeFile);

                        postUiToast("File G-Code salvato e sincronizzato", false);
                    } catch (IOException e) {
                        postUiToast("Errore durante il salvataggio: " + e.getMessage(), true);
                    } finally {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> loadingOverlay.setVisibility(View.GONE));
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