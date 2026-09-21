package in.co.gorest.grblcontroller.ui;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import in.co.gorest.grblcontroller.events.ConsoleMessageEvent;
import in.co.gorest.grblcontroller.events.GrblErrorEvent;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.service.FileStreamerIntentService;
import in.co.gorest.grblcontroller.util.FluidNcSdListing;
import in.co.gorest.grblcontroller.model.Constants;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** A controller-local job: no phone file, preprocessing or streaming service. */
public final class FluidNcSdBrowser {
    private final BaseFragment owner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FluidNcSdListing listing = new FluidNcSdListing();
    private AlertDialog dialog;
    private boolean listening;
    private final Runnable timeout = () -> fail("Nessuna risposta completa dalla SD. Verificare scheda e connessione, poi riprovare.");

    FluidNcSdBrowser(BaseFragment owner) { this.owner = owner; }

    private boolean idle() {
        return MachineStatusListener.STATE_IDLE.equals(MachineStatusListener.getInstance().getState())
                && !FileStreamerIntentService.getIsServiceRunning()
                && MachineStatusListener.getInstance().getSdJob().isEmpty();
    }

    void show() {
        if (!idle()) { fail("La macchina deve essere connessa e ferma per leggere la SD."); return; }
        dialog = new AlertDialog.Builder(owner.requireContext()).setTitle("SD di FluidNC")
                .setMessage("Lettura della scheda…")
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnDismissListener(d -> stopListening());
        dialog.show();
        listening = true;
        EventBus.getDefault().register(this);
        handler.postDelayed(timeout, 20000);
        owner.fragmentInteractionListener.onGcodeCommandReceived("$SD/List");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConsole(ConsoleMessageEvent event) {
        String line = event.getMessage();
        listing.accept(line);
        // The filesystem summary terminates the listing, unlike unrelated ok replies.
        if (line.startsWith("[") && line.contains(" Free:") && line.contains(" Total:")) {
            stopListening();
            dialog.dismiss();
            Pattern supported = Pattern.compile(Constants.SUPPORTED_FILE_TYPES_STRING, Pattern.CASE_INSENSITIVE);
            ArrayList<String> runnable = new ArrayList<>();
            for (String file : listing.files) if (supported.matcher(file).matches()) runnable.add(file);
            if (runnable.isEmpty()) { fail("Scheda presente, nessun file G-code trovato."); return; }
            String[] files = runnable.toArray(new String[0]);
            dialog = new AlertDialog.Builder(owner.requireContext()).setTitle("File sulla SD di FluidNC")
                    .setItems(files, (d, which) -> confirm(files[which]))
                    .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    private void confirm(String path) {
        dialog = new AlertDialog.Builder(owner.requireContext()).setTitle("Avvia dalla SD")
                .setMessage(path + "\n\nFluidNC eseguirà il file autonomamente. Controllare origine e utensile. Le correzioni e l’autolivellamento dell’app non vengono applicati.")
                .setPositiveButton("Avvia", (d, w) -> {
                    if (!idle()) { fail("Macchina non più disponibile per l’avvio."); return; }
                    owner.fragmentInteractionListener.onGcodeCommandReceived("$SD/Run=" + path);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onError(GrblErrorEvent event) {
        fail("SD non disponibile o comando rifiutato da FluidNC. " + event.getMessage());
    }

    private void fail(String message) {
        close();
        if (owner.isAdded()) dialog = new AlertDialog.Builder(owner.requireContext())
                .setTitle("SD di FluidNC").setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    private void stopListening() {
        handler.removeCallbacks(timeout);
        if (listening) EventBus.getDefault().unregister(this);
        listening = false;
    }

    void close() {
        stopListening();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
