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
package in.co.gorest.grblcontroller.service;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.greenrobot.eventbus.EventBus;

import fi.iki.elonen.NanoHTTPD;
import in.co.gorest.grblcontroller.events.GrblRealTimeCommandEvent;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Overrides;
import in.co.gorest.grblcontroller.util.GrblUtils;

public class GcodeHttpServer extends NanoHTTPD {

    private static final String TAG = GcodeHttpServer.class.getSimpleName();

    private static final List<String> ALLOWED_EXT = Arrays.asList(
            "nc", "gcode", "gco", "tap", "cnc", "ngc", "txt");

    /** Hard cap on a single upload request (declared via Content-Length). */
    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    /** Sleep on failed authentication to throttle brute-force attempts. */
    private static final long AUTH_FAIL_DELAY_MS = 500;

    /** Cap on the no-overwrite rename loop. */
    private static final int MAX_RENAME_ATTEMPTS = 999;

    /**
     * Hook the server uses to make the host phone ring/stop. Implemented by
     * {@link HttpServerService} (which has a Context); kept as an interface so
     * the HTTP layer stays decoupled from Android audio APIs. May be null.
     */
    public interface RingHandler {
        void ring();
        void stopRing();
    }

    /**
     * App-derived colours so the web page follows the in-app theme. {@code on*}
     * are the readable text colours (white or near-black) computed for contrast
     * against {@code primary}/{@code accent} on the Android side.
     */
    public static final class Palette {
        final String primary, primaryDark, accent, onPrimary, onAccent;
        public Palette(String primary, String primaryDark, String accent,
                       String onPrimary, String onAccent) {
            this.primary = primary;
            this.primaryDark = primaryDark;
            this.accent = accent;
            this.onPrimary = onPrimary;
            this.onAccent = onAccent;
        }
        static final Palette DEFAULT =
                new Palette("#37474f", "#263238", "#ffa000", "#ffffff", "#212121");
    }

    private final File rootDir;
    private final byte[] expectedAuthBytes;
    private final RingHandler ringHandler;
    private final Palette palette;

    public GcodeHttpServer(int port, File rootDir, String password,
                           RingHandler ringHandler, Palette palette) {
        super(port);
        this.rootDir = rootDir;
        this.ringHandler = ringHandler;
        this.palette = (palette != null) ? palette : Palette.DEFAULT;
        if (password != null && !password.isEmpty()) {
            String creds = HttpServerManager.USERNAME + ":" + password;
            String header = "Basic " + Base64.encodeToString(
                    creds.getBytes(), Base64.NO_WRAP);
            this.expectedAuthBytes = header.getBytes();
        } else {
            this.expectedAuthBytes = null;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String reqUri = session.getUri();
        Method reqMethod = session.getMethod();
        Log.i(TAG, "REQ " + reqMethod + " " + reqUri + " from " + session.getRemoteIpAddress());

        // Auth (constant-time compare, slow on failure to throttle brute force).
        if (expectedAuthBytes != null) {
            String auth = session.getHeaders().get("authorization");
            boolean hasAuth = auth != null;
            boolean ok = hasAuth && constantTimeEquals(auth.getBytes(), expectedAuthBytes);
            if (!ok) {
                Log.i(TAG, "AUTH " + (hasAuth ? "MISMATCH" : "MISSING") + " for " + reqUri);
                try { Thread.sleep(AUTH_FAIL_DELAY_MS); } catch (InterruptedException ignore) {}
                Response r = newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, "text/plain", "Auth required");
                r.addHeader("WWW-Authenticate", "Basic realm=\"GrblController\"");
                return finalizeResponse(r);
            }
            Log.i(TAG, "AUTH OK for " + reqUri);
        }

        String uri = reqUri;
        Method method = reqMethod;

        // Catch Throwable so a coding bug in renderIndex/handleUpload doesn't
        // kill the NanoHTTPD worker mid-response (which surfaces as
        // ERR_CONNECTION_RESET on the client).
        try {
            Response resp;
            if (Method.GET.equals(method) && (uri.equals("/") || uri.equals("/index"))) {
                resp = renderIndex(session);
            } else if (Method.GET.equals(method) && uri.startsWith("/d/")) {
                String name = decode(uri.substring(3));
                resp = serveDownload(name);
            } else if (Method.POST.equals(method) && uri.equals("/upload")) {
                resp = handleUpload(session);
            } else if (Method.GET.equals(method) && uri.equals("/status")) {
                resp = handleStatus();
            } else if (Method.POST.equals(method) && uri.equals("/delete")) {
                resp = handleDelete(session);
            } else if (Method.POST.equals(method) && uri.equals("/control")) {
                resp = handleControl(session);
            } else if (Method.POST.equals(method) && uri.equals("/ring")) {
                resp = handleRing(session, true);
            } else if (Method.POST.equals(method) && uri.equals("/ring/stop")) {
                resp = handleRing(session, false);
            } else {
                resp = newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
            Log.i(TAG, "RESP " + resp.getStatus() + " for " + uri);
            return finalizeResponse(resp);
        } catch (Throwable t) {
            Log.e(TAG, "serve error for " + uri, t);
            Response r = newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "Server error");
            return finalizeResponse(r);
        }
    }

    /**
     * Applies security headers AND disables HTTP keep-alive on every response.
     *
     * Why no keep-alive: NanoHTTPD honours SOCKET_READ_TIMEOUT (5s) on idle
     * connections; when it expires the server-side socket is closed, but if the
     * client (Edge/Chrome aggressively pool connections) has not yet seen the
     * FIN it will try to reuse the connection for its next request, the kernel
     * answers with RST, and the user sees ERR_CONNECTION_RESET. Our traffic
     * volume is tiny so the cost of TCP handshake per request is negligible.
     */
    private Response finalizeResponse(Response r) {
        if (r == null) return null;
        addSecurityHeaders(r);
        try {
            r.setKeepAlive(false);
        } catch (Throwable ignore) {
            // Older/forked NanoHTTPD without setKeepAlive: fall back to header.
            r.addHeader("Connection", "close");
        }
        return r;
    }

    private Response renderIndex(IHTTPSession session) {
        // Post-Redirect-Get: handleUpload redirects to /?ok=N on full success
        // and the index then shows a modal banner. We clamp to a small range
        // so a hand-crafted URL can't inject huge numbers into the markup.
        int uploadedOk = 0;
        try {
            List<String> p = session.getParameters().get("ok");
            if (p != null && !p.isEmpty()) {
                int n = Integer.parseInt(p.get(0));
                if (n > 0 && n < 10000) uploadedOk = n;
            }
        } catch (NumberFormatException ignore) {}

        // ?delerr=busy is set by handleDelete when a delete is refused because
        // the file is currently being streamed.
        boolean delBusy = false;
        List<String> de = session.getParameters().get("delerr");
        if (de != null && !de.isEmpty()) delBusy = "busy".equals(de.get(0));

        List<File> files = listGcodeFiles();
        long totalBytes = 0;
        long lastModified = 0;
        for (File f : files) {
            totalBytes += f.length();
            if (f.lastModified() > lastModified) lastModified = f.lastModified();
        }
        String machineName = HttpServerManager.getMachineName();

        // Titolo scheda = nome macchina quando connessa, così più schede aperte
        // su macchine diverse si distinguono nel browser. Lo stesso valore viene
        // poi tenuto in sincrono lato client dal polling di /status.
        String stateNow = MachineStatusListener.getInstance().getState();
        boolean connectedNow = !MachineStatusListener.STATE_NOT_CONNECTED.equals(stateNow);
        String pageTitle = (connectedNow && machineName != null && !machineName.isEmpty())
                ? machineName : "GRBL Machining";

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html lang=\"it\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"theme-color\" content=\"").append(palette.primary).append("\">")
                .append("<title>").append(escapeHtml(pageTitle)).append("</title>")
                // Favicon: small SVG wrench emoji as data URI, no asset round-trip
                .append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"").append(faviconHref()).append("\">")
                .append("<style>")
                .append(cssVars())
                .append("*{box-sizing:border-box}")
                .append("body{margin:0;background:#f5f5f5;color:#212121;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;")
                .append("font-size:15px;line-height:1.45}")
                .append("header{background:var(--primary);color:var(--on-primary);padding:18px 20px;")
                .append("box-shadow:0 2px 4px rgba(0,0,0,.2)}")
                .append("header .row{display:flex;align-items:center;gap:14px;max-width:780px;margin:0 auto;flex-wrap:wrap}")
                .append("header .logo{width:42px;height:42px;background:rgba(255,255,255,.15);")
                .append("border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:24px;flex-shrink:0}")
                .append("header .hinfo{flex:1;min-width:160px}")
                .append("header h1{margin:0;font-size:1.15em;font-weight:500;letter-spacing:.2px}")
                .append("header .sub{opacity:.85;font-size:.85em;margin-top:2px}")
                .append("header .ring{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}")
                .append("header .ring button{padding:8px 16px;font-size:13px}")
                .append("header .ring .stop{background:#c62828;color:#fff}")
                .append("header .ring .msg{color:var(--on-primary);opacity:.9;font-size:.8em;text-transform:none;letter-spacing:0}")
                .append("main{max-width:780px;margin:18px auto;padding:0 16px}")
                .append(".card{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12);")
                .append("margin-bottom:16px;overflow:hidden}")
                .append(".card-head{padding:14px 18px;border-bottom:1px solid #eee;font-weight:500;color:#424242}")
                .append(".card-body{padding:18px}")
                .append("input[type=file]{display:block;width:100%;padding:10px;border:1px dashed #bdbdbd;")
                .append("border-radius:6px;background:#fafafa;margin-bottom:12px}")
                // Upload progress bar (hidden until an upload is in flight)
                .append(".progress{display:none;margin-top:14px}")
                .append(".progress.on{display:block}")
                .append(".progress .bar{height:10px;background:#eceff1;border-radius:5px;overflow:hidden}")
                .append(".progress .fill{height:100%;width:0;background:var(--accent);")
                .append("transition:width .15s ease-out}")
                .append(".progress .ptext{margin-top:8px;color:#616161;font-size:.86em;")
                .append("font-variant-numeric:tabular-nums}")
                .append(".progress.err .fill{background:#c62828}")
                .append(".progress.err .ptext{color:#c62828}")
                .append("button{background:var(--accent);color:var(--on-accent);border:0;border-radius:4px;")
                .append("padding:10px 22px;font-size:14px;font-weight:500;text-transform:uppercase;")
                .append("letter-spacing:.5px;cursor:pointer;box-shadow:0 1px 2px rgba(0,0,0,.2);")
                .append("transition:filter .15s,box-shadow .15s}")
                .append("button:hover{filter:brightness(.92);box-shadow:0 2px 4px rgba(0,0,0,.25)}")
                .append("button:active{filter:brightness(.85)}")
                .append(".hint{color:#757575;font-size:.85em;margin:0 0 10px}")
                .append("table{width:100%;border-collapse:collapse}")
                .append("th{text-align:left;padding:10px 18px;background:#fafafa;font-weight:500;")
                .append("font-size:.8em;text-transform:uppercase;letter-spacing:.5px;color:#757575;")
                .append("border-bottom:1px solid #eee}")
                .append("th.size{text-align:right}")
                .append("td{padding:12px 18px;border-bottom:1px solid #f0f0f0;vertical-align:middle}")
                .append("td.size{text-align:right;white-space:nowrap;color:#616161;font-variant-numeric:tabular-nums}")
                .append("tr:last-child td{border-bottom:0}")
                .append("tr:hover td{background:#fafafa}")
                .append("td a{color:var(--primary);text-decoration:none;font-weight:500}")
                .append("td a:hover{text-decoration:underline}")
                .append(".empty{padding:30px 18px;text-align:center;color:#9e9e9e;font-style:italic}")
                // Header: prominent machine name + two info lines
                .append(headerInfoCss())
                // Delete controls + banner
                .append("th.act,td.act{text-align:right;white-space:nowrap}")
                .append(".delform{display:inline;margin:0}")
                .append(".del{background:#c62828;color:#fff;padding:6px 14px;font-size:12px}")
                .append(".banner{margin-bottom:14px;padding:11px 16px;border-radius:6px;font-size:.9em}")
                .append(".banner.warn{background:#fff3e0;color:#e65100;border:1px solid #ffcc80}")
                // Machine control bar: pause/stop (left) + overrides (right)
                .append(".ctrl{display:flex;align-items:stretch;gap:20px;flex-wrap:wrap}")
                .append(".ctrl .cmain{display:flex;align-items:center;justify-content:center;gap:10px;")
                .append("padding-right:20px;border-right:1px solid #eee;flex-wrap:wrap}")
                .append(".ctrl .covr{flex:1;min-width:240px;display:flex;flex-direction:column;")
                .append("justify-content:center;gap:12px}")
                .append(".ctrl .ovr{display:flex;align-items:center;gap:10px;flex-wrap:wrap}")
                .append(".ctrl .ovr .lbl{min-width:104px;color:#616161;font-size:.9em}")
                .append(".ctrl .ovr b{min-width:50px;text-align:center;color:#263238;")
                .append("font-variant-numeric:tabular-nums}")
                .append(".ctrl .ob{background:#eceff1;color:#263238;padding:8px 14px;box-shadow:none}")
                .append(".ctrl .ob.rst{font-size:12px}")
                .append(".ctrl .pause{background:#ef6c00;color:#fff;padding:11px 26px}")
                .append(".ctrl .stopbtn{background:#c62828;color:#fff;padding:11px 26px}")
                .append(".ctrl .chint{flex-basis:100%;color:#757575;font-size:.82em;margin:6px 0 0}")
                .append("button:disabled{opacity:.4;cursor:not-allowed;filter:none!important;box-shadow:none}")
                .append("footer{max-width:780px;margin:8px auto 20px;padding:0 18px;color:#757575;")
                .append("font-size:.82em;display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px}")
                .append("footer .stats{display:flex;gap:18px;flex-wrap:wrap}")
                .append("footer .stats b{color:#424242;font-weight:600}")
                // ----- Modal (upload success notification) -----
                .append(".modal-bg{position:fixed;inset:0;background:rgba(0,0,0,.5);")
                .append("display:flex;align-items:center;justify-content:center;z-index:1000;")
                .append("animation:fadeIn .2s ease-out;padding:20px}")
                .append(".modal{background:#fff;border-radius:8px;max-width:420px;width:100%;")
                .append("box-shadow:0 8px 24px rgba(0,0,0,.3);animation:slideUp .25s ease-out;overflow:hidden}")
                .append("@keyframes fadeIn{from{opacity:0}to{opacity:1}}")
                .append("@keyframes slideUp{from{transform:translateY(20px);opacity:0}")
                .append("to{transform:translateY(0);opacity:1}}")
                .append(".modal-body{padding:24px;display:flex;align-items:center;gap:18px}")
                .append(".modal-ico{width:54px;height:54px;border-radius:50%;background:#338a3e;color:#fff;")
                .append("display:flex;align-items:center;justify-content:center;font-size:30px;font-weight:700;flex-shrink:0}")
                .append(".modal-txt h2{margin:0 0 4px;font-size:1.15em;font-weight:500;color:#212121}")
                .append(".modal-txt p{margin:0;color:#616161;font-size:.92em}")
                .append(".modal-foot{padding:12px 24px;border-top:1px solid #eee;")
                .append("display:flex;justify-content:flex-end;gap:8px;align-items:center}")
                .append(".modal-foot .countdown{color:#9e9e9e;font-size:.85em;margin-right:auto}")
                .append(".modal-foot button{background:transparent;color:var(--primary);border:0;padding:8px 14px;")
                .append("cursor:pointer;font-weight:500;text-transform:uppercase;font-size:.82em;")
                .append("letter-spacing:.5px;border-radius:4px;box-shadow:none}")
                .append(".modal-foot button:hover{background:rgba(0,0,0,.05);box-shadow:none}")
                .append("</style></head><body>");

        // ----- Header (machine name + state + find-the-phone controls) -----
        appendHeader(html, machineName, true);

        html.append("<main>");

        if (delBusy) {
            html.append("<div class=\"banner warn\">Impossibile eliminare: il file è in esecuzione.</div>");
        }

        // ----- Machine control card (pause / stop / overrides). No resume. -----
        // Two columns: pause/stop on the left (centred), overrides on the right.
        html.append("<div class=\"card\">")
                .append("<div class=\"card-head\">Controllo macchina</div>")
                .append("<div class=\"card-body ctrl\">")
                .append("<div class=\"cmain\">")
                .append("<button class=\"pause\" data-cmd=\"pause\">Pausa</button>")
                .append("<button class=\"stopbtn\" data-cmd=\"stop\">Arresto</button></div>")
                .append("<div class=\"covr\">")
                .append("<div class=\"ovr\"><span class=\"lbl\">Avanzamento</span>")
                .append("<button class=\"ob\" data-cmd=\"feed_minus\">&minus;</button>")
                .append("<b id=\"ovrFeed\">—</b>")
                .append("<button class=\"ob\" data-cmd=\"feed_plus\">+</button>")
                .append("<button class=\"ob rst\" data-cmd=\"feed_reset\">Reset</button></div>")
                .append("<div class=\"ovr\"><span class=\"lbl\">Mandrino</span>")
                .append("<button class=\"ob\" data-cmd=\"spindle_minus\">&minus;</button>")
                .append("<b id=\"ovrSpindle\">—</b>")
                .append("<button class=\"ob\" data-cmd=\"spindle_plus\">+</button>")
                .append("<button class=\"ob rst\" data-cmd=\"spindle_reset\">Reset</button></div>")
                .append("</div>")
                .append("<p class=\"chint\">La pausa ferma il movimento ma <b>non il mandrino</b>. ")
                .append("Per riprendere usa il telefono alla macchina.</p>")
                .append("</div></div>");

        // ----- Upload card -----
        html.append("<div class=\"card\">")
                .append("<div class=\"card-head\">Carica file dal PC</div>")
                .append("<div class=\"card-body\">")
                .append("<form id=\"uploadForm\" method=\"POST\" action=\"/upload\" enctype=\"multipart/form-data\">")
                .append("<p class=\"hint\">Se un file con lo stesso nome esiste, viene rinominato con un suffisso. ")
                .append("Estensioni accettate: .nc .gcode .gco .tap .cnc .ngc .txt</p>")
                .append("<input type=\"file\" id=\"filePicker\" name=\"file\" multiple required>")
                .append("<button type=\"submit\" id=\"upBtn\">Carica</button>")
                .append("</form>")
                // Progress bar: nascosta finché non parte un upload.
                .append("<div class=\"progress\" id=\"upProg\">")
                .append("<div class=\"bar\"><div class=\"fill\" id=\"upFill\"></div></div>")
                .append("<div class=\"ptext\" id=\"upText\">&nbsp;</div></div>")
                .append("</div></div>")
                // JS upload: invia il form via XHR così da mostrare l'avanzamento
                // reale (upload.onprogress) — con file grandi su WiFi lento non si
                // capirebbe altrimenti se sta caricando. Prima dell'invio aggiunge
                // un input "expected" per ogni file ("<nome>:<byte>"): il server
                // confronta i byte ricevuti con la dimensione dichiarata e rifiuta
                // i file troncati (WiFi che cade a metà upload). A fine richiesta
                // il server risponde con la index (dopo il 303) oppure con la
                // pagina d'esito: in entrambi i casi sostituiamo il documento.
                .append("<script>(function(){")
                .append("var form=document.getElementById('uploadForm');")
                .append("var picker=document.getElementById('filePicker');")
                .append("var prog=document.getElementById('upProg');")
                .append("var fill=document.getElementById('upFill');")
                .append("var ptext=document.getElementById('upText');")
                .append("var btn=document.getElementById('upBtn');")
                .append("form.addEventListener('submit',function(ev){")
                .append("if(!picker.files.length)return;")
                .append("ev.preventDefault();")
                .append("form.querySelectorAll('input[name=\"expected\"]').forEach(function(i){i.remove();});")
                .append("for(var i=0;i<picker.files.length;i++){")
                .append("var f=picker.files[i];")
                .append("var inp=document.createElement('input');")
                .append("inp.type='hidden';inp.name='expected';inp.value=f.name+':'+f.size;")
                .append("form.appendChild(inp);}")
                .append("var xhr=new XMLHttpRequest();")
                .append("xhr.open('POST',form.action,true);")
                .append("btn.disabled=true;prog.className='progress on';")
                .append("fill.style.width='0%';ptext.textContent='Caricamento\\u2026 0%';")
                .append("xhr.upload.addEventListener('progress',function(e){")
                .append("if(e.lengthComputable){var pc=Math.round(e.loaded*100/e.total);")
                .append("fill.style.width=pc+'%';")
                .append("ptext.textContent=pc<100?('Caricamento\\u2026 '+pc+'%')")
                .append(":'Elaborazione sul dispositivo\\u2026';}});")
                .append("xhr.addEventListener('load',function(){")
                .append("fill.style.width='100%';")
                .append("document.open();document.write(xhr.responseText);document.close();")
                .append("if(history.replaceState&&xhr.responseURL){")
                .append("history.replaceState({},'',xhr.responseURL);}});")
                .append("xhr.addEventListener('error',function(){")
                .append("btn.disabled=false;prog.className='progress on err';")
                .append("ptext.textContent='Errore di rete durante l\\'upload. Riprova.';});")
                .append("xhr.send(new FormData(form));")
                .append("});")
                .append("})();</script>");

        // ----- File list card -----
        html.append("<div class=\"card\">")
                .append("<div class=\"card-head\">File G-code disponibili (")
                .append(files.size()).append(")</div>");
        if (files.isEmpty()) {
            html.append("<div class=\"empty\">Nessun file G-code presente.<br>Caricane uno qui sopra per iniziare.</div>");
        } else {
            html.append("<table><thead><tr><th>Nome</th><th class=\"size\">Dimensione</th>")
                    .append("<th class=\"act\">Azioni</th></tr></thead><tbody>");
            for (File f : files) {
                html.append("<tr><td><a href=\"/d/").append(encode(f.getName())).append("\">")
                        .append(escapeHtml(f.getName())).append("</a></td>")
                        .append("<td class=\"size\">").append(formatSize(f.length())).append("</td>")
                        .append("<td class=\"act\">")
                        .append("<form class=\"delform\" method=\"POST\" action=\"/delete?name=")
                        .append(encode(f.getName())).append("\" data-name=\"")
                        .append(escapeHtml(f.getName())).append("\">")
                        .append("<button type=\"submit\" class=\"del\" title=\"Elimina\">Elimina</button>")
                        .append("</form></td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div></main>");

        // ----- Footer stats -----
        html.append("<footer><div class=\"stats\">")
                .append("<span><b>").append(files.size()).append("</b> file</span>")
                .append("<span><b>").append(formatSize(totalBytes)).append("</b> totali</span>");
        if (lastModified > 0) {
            html.append("<span>ultimo aggiornamento: <b>")
                    .append(formatRelativeTime(lastModified)).append("</b></span>");
        }
        html.append("</div><div>GRBL Machining</div></footer>");

        // ----- Upload success modal (only when ?ok=N is in the URL) -----
        if (uploadedOk > 0) {
            String label = (uploadedOk == 1) ? " file caricato" : " file caricati";
            html.append("<div class=\"modal-bg\" id=\"modalBg\" role=\"dialog\" aria-modal=\"true\">")
                    .append("<div class=\"modal\">")
                    .append("<div class=\"modal-body\">")
                    .append("<div class=\"modal-ico\">✓</div>")
                    .append("<div class=\"modal-txt\">")
                    .append("<h2>Upload completato</h2>")
                    .append("<p><b>").append(uploadedOk).append("</b>").append(label).append(" correttamente</p>")
                    .append("</div></div>")
                    .append("<div class=\"modal-foot\">")
                    .append("<span class=\"countdown\" id=\"cd\">&nbsp;</span>")
                    .append("<button type=\"button\" id=\"closeBtn\">OK</button>")
                    .append("</div></div></div>")
                    .append("<script>(function(){")
                    .append("var s=4;")
                    .append("var bg=document.getElementById('modalBg');")
                    .append("var cd=document.getElementById('cd');")
                    .append("var done=false;")
                    .append("function close(){if(done)return;done=true;clearInterval(t);")
                    .append("bg.style.display='none';")
                    // Strip the ?ok=N from the URL so a refresh doesn't re-show the modal.
                    .append("if(history.replaceState){history.replaceState({},'',location.pathname);}}")
                    .append("var t=setInterval(function(){s--;if(s<=0){close();return;}")
                    .append("cd.textContent='Chiude in '+s+'s';},1000);")
                    .append("cd.textContent='Chiude in '+s+'s';")
                    .append("document.getElementById('closeBtn').addEventListener('click',close);")
                    .append("bg.addEventListener('click',function(e){if(e.target===bg)close();});")
                    .append("document.addEventListener('keydown',function(e){if(e.key==='Escape')close();});")
                    .append("})();</script>");
        }

        html.append(ringScript());
        html.append(jobStatusScript());
        html.append(controlScript());
        html.append(deleteConfirmScript());
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.toString());
    }

    private static String formatRelativeTime(long millis) {
        long delta = System.currentTimeMillis() - millis;
        if (delta < 60_000) return "ora";
        if (delta < 3_600_000) return (delta / 60_000) + " min fa";
        if (delta < 86_400_000) return (delta / 3_600_000) + " h fa";
        if (delta < 7L * 86_400_000) return (delta / 86_400_000) + " g fa";
        return new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(new java.util.Date(millis));
    }

    private Response serveDownload(String name) throws IOException {
        File target = safeResolve(name);
        if (target == null || !target.isFile() || !hasAllowedExtension(target.getName())) {
            return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "File not found");
        }
        InputStream is = new FileInputStream(target);
        Response r = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", is, target.length());
        // sanitizeFileName output already strips \r\n and slashes; double quote
        // is the only meta-character to handle for the Content-Disposition value.
        String safeName = target.getName().replace("\"", "_");
        r.addHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        return r;
    }

    private Response handleUpload(IHTTPSession session) throws IOException, ResponseException {
        // Refuse oversized uploads up-front via Content-Length. Note that a
        // chunked / mis-declared request can still exceed this; NanoHTTPD will
        // spool to its own temp files which we delete after the fact.
        String clenStr = session.getHeaders().get("content-length");
        if (clenStr != null) {
            try {
                long clen = Long.parseLong(clenStr.trim());
                if (clen > MAX_UPLOAD_BYTES) {
                    return newFixedLengthResponse(
                            Response.Status.BAD_REQUEST, "text/plain",
                            "Upload troppo grande (max "
                                    + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB)");
                }
            } catch (NumberFormatException ignore) { /* fall through */ }
        }

        // CSRF defence: if an Origin header is present (browser cross-site
        // fetch), it must match the Host we listened on. Missing Origin
        // (curl, native clients, some same-origin form posts) is allowed.
        if (!isSameOriginIfPresent(session)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "Cross-origin upload denied");
        }

        Map<String, String> tempFiles = new HashMap<>();
        try {
            session.parseBody(tempFiles);
        } catch (IOException ioe) {
            // Connection cut mid-upload (typical on a flaky WiFi). Nothing
            // reliable was received — show a clear error instead of a silent
            // 500 so the operator knows to retry.
            Log.w(TAG, "upload interrupted before multipart parsing completed", ioe);
            return renderUploadResult(0, new ArrayList<String>(),
                    Collections.singletonList("Connessione interrotta durante l'upload"));
        }

        Map<String, List<String>> params = session.getParameters();

        // Map of <client filename> -> <byte size declared by the browser JS>.
        // Used to detect truncated uploads where the multipart parser
        // succeeded but the file part is short of what the client intended.
        Map<String, Long> expectedSizes = new HashMap<>();
        List<String> expectedList = params.get("expected");
        if (expectedList != null) {
            for (String s : expectedList) {
                int colon = s.lastIndexOf(':');
                if (colon <= 0 || colon == s.length() - 1) continue;
                try {
                    expectedSizes.put(s.substring(0, colon),
                            Long.parseLong(s.substring(colon + 1)));
                } catch (NumberFormatException ignore) {}
            }
        }

        int saved = 0;
        List<String> savedNames = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (Map.Entry<String, String> e : tempFiles.entrySet()) {
            String fieldName = e.getKey();
            String tmpPath = e.getValue();

            String originalName = null;
            if (params.containsKey(fieldName) && !params.get(fieldName).isEmpty()) {
                originalName = params.get(fieldName).get(0);
            }
            if (originalName == null || originalName.isEmpty()) continue;

            String safeName = sanitizeFileName(originalName);
            if (!hasAllowedExtension(safeName)) {
                rejected.add(originalName);
                continue;
            }
            File dest = safeResolve(safeName);
            if (dest == null) {
                rejected.add(originalName);
                continue;
            }
            // Never overwrite: pick a free name if dest exists.
            dest = pickNonExistingName(dest);
            if (dest == null) {
                rejected.add(originalName);
                continue;
            }
            File tmp = new File(tmpPath);
            if (!copyAndReplace(tmp, dest)) {
                rejected.add(originalName);
                continue;
            }

            // Integrity check: if the browser told us how many bytes it was
            // sending (see the form's submit-time JS), verify that we wrote
            // exactly that many. A short file is a truncated upload — common
            // on weak WiFi mid-transfer — and on a CNC controller silently
            // accepting a half-written G-code is genuinely dangerous: the job
            // would stop mid-machining with the spindle still down. We delete
            // the partial save and surface the error to the operator.
            Long expected = expectedSizes.get(originalName);
            if (expected != null && dest.length() != expected) {
                Log.w(TAG, "size mismatch for " + originalName
                        + ": declared=" + expected + " got=" + dest.length());
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                rejected.add(originalName + " — file incompleto ("
                        + dest.length() + " su " + expected + " byte ricevuti)");
                continue;
            }

            saved++;
            savedNames.add(dest.getName());
        }

        // Full-success path: POST-Redirect-GET to the index so the user lands
        // on the up-to-date file list with a non-blocking modal banner. The
        // detailed result page is only shown when something went wrong, where
        // the per-file list actually matters.
        if (saved > 0 && rejected.isEmpty()) {
            Response redirect = newFixedLengthResponse(
                    Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
            redirect.addHeader("Location", "/?ok=" + saved);
            return redirect;
        }
        return renderUploadResult(saved, savedNames, rejected);
    }

    /**
     * Rings (or stops ringing) the host phone so an operator can locate it in
     * the workshop. Same-origin guarded like uploads — this changes device
     * state, so we don't want a cross-site page triggering it. No-ops cleanly
     * if no {@link RingHandler} was wired.
     */
    private Response handleRing(IHTTPSession session, boolean start) {
        if (!isSameOriginIfPresent(session)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "Cross-origin denied");
        }
        if (ringHandler != null) {
            if (start) ringHandler.ring();
            else ringHandler.stopRing();
        }
        return newFixedLengthResponse(
                Response.Status.OK, "text/plain", start ? "ringing" : "stopped");
    }

    /**
     * Sends a GRBL real-time command to the machine on behalf of a browser
     * button. Same-origin guarded (changes device state). Only a whitelist of
     * "slow down / pause / stop" commands is honoured — crucially NOT cycle
     * start / resume ('~'): the machine must never be restarted remotely, an
     * operator has to be physically present to resume motion.
     */
    private Response handleControl(IHTTPSession session) {
        if (!isSameOriginIfPresent(session)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "Cross-origin denied");
        }
        try { session.parseBody(new HashMap<String, String>()); } catch (Exception ignore) {}

        String cmd = null;
        List<String> p = session.getParameters().get("cmd");
        if (p != null && !p.isEmpty()) cmd = p.get(0);

        Byte b = controlByte(cmd);
        if (b == null) {
            return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "text/plain", "Unknown command");
        }
        EventBus.getDefault().post(new GrblRealTimeCommandEvent(b));
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
    }

    /**
     * Maps a control id to its GRBL real-time byte. Returns null for anything
     * not on the whitelist. Resume ('~') and play are deliberately absent.
     */
    private static Byte controlByte(String cmd) {
        if (cmd == null) return null;
        switch (cmd) {
            case "pause":         return GrblUtils.GRBL_PAUSE_COMMAND;   // '!' feed hold
            case "stop":          return GrblUtils.GRBL_RESET_COMMAND;   // 0x18 soft reset
            case "feed_minus":    return GrblUtils.getOverrideForEnum(Overrides.CMD_FEED_OVR_COARSE_MINUS);
            case "feed_plus":     return GrblUtils.getOverrideForEnum(Overrides.CMD_FEED_OVR_COARSE_PLUS);
            case "feed_reset":    return GrblUtils.getOverrideForEnum(Overrides.CMD_FEED_OVR_RESET);
            case "spindle_minus": return GrblUtils.getOverrideForEnum(Overrides.CMD_SPINDLE_OVR_COARSE_MINUS);
            case "spindle_plus":  return GrblUtils.getOverrideForEnum(Overrides.CMD_SPINDLE_OVR_COARSE_PLUS);
            case "spindle_reset": return GrblUtils.getOverrideForEnum(Overrides.CMD_SPINDLE_OVR_RESET);
            default:              return null;
        }
    }

    private Response renderUploadResult(int saved, List<String> savedNames, List<String> rejected) {
        String machineName = HttpServerManager.getMachineName();
        boolean allOk = rejected.isEmpty() && saved > 0;
        boolean allFail = saved == 0 && !rejected.isEmpty();
        String iconBg, iconChar, statusTitle, statusSub;
        if (allOk) {
            iconBg = "#338a3e"; iconChar = "✓";
            statusTitle = "Upload completato";
            statusSub = saved + (saved == 1 ? " file caricato" : " file caricati") + " correttamente";
        } else if (allFail) {
            iconBg = "#c62828"; iconChar = "✕";
            statusTitle = "Upload non riuscito";
            statusSub = "Nessun file accettato";
        } else if (saved > 0) {
            iconBg = "#ef6c00"; iconChar = "!";
            statusTitle = "Upload parziale";
            statusSub = saved + " caricati · " + rejected.size() + " rifiutati";
        } else {
            iconBg = "#9e9e9e"; iconChar = "?";
            statusTitle = "Nessun file ricevuto";
            statusSub = "Seleziona almeno un file e riprova";
        }

        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html><html lang=\"it\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"theme-color\" content=\"").append(palette.primary).append("\">")
                .append("<title>Esito upload — GRBL Machining</title>")
                .append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"").append(faviconHref()).append("\">")
                .append("<style>")
                .append(cssVars())
                .append("*{box-sizing:border-box}")
                .append("body{margin:0;background:#f5f5f5;color:#212121;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;")
                .append("font-size:15px;line-height:1.45}")
                .append("header{background:var(--primary);color:var(--on-primary);padding:18px 20px;box-shadow:0 2px 4px rgba(0,0,0,.2)}")
                .append("header .row{display:flex;align-items:center;gap:14px;max-width:780px;margin:0 auto;flex-wrap:wrap}")
                .append("header .hinfo{flex:1;min-width:160px}")
                .append("header .logo{width:42px;height:42px;background:rgba(255,255,255,.15);")
                .append("border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:24px;flex-shrink:0}")
                .append("header h1{margin:0;font-size:1.15em;font-weight:500;letter-spacing:.2px}")
                .append("header .sub{opacity:.85;font-size:.85em;margin-top:2px}")
                .append("main{max-width:780px;margin:18px auto;padding:0 16px}")
                .append(".card{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12);margin-bottom:16px;overflow:hidden}")
                .append(".status{padding:24px 20px;display:flex;align-items:center;gap:18px}")
                .append(".status .ico{width:54px;height:54px;border-radius:50%;color:#fff;")
                .append("display:flex;align-items:center;justify-content:center;font-size:30px;font-weight:700;flex-shrink:0;")
                .append("background:").append(iconBg).append("}")
                .append(".status .txt h2{margin:0 0 4px;font-size:1.15em;font-weight:500;color:#212121}")
                .append(".status .txt p{margin:0;color:#616161;font-size:.92em}")
                .append(".section{padding:14px 20px;border-top:1px solid #eee}")
                .append(".section h3{margin:0 0 10px;font-size:.78em;text-transform:uppercase;letter-spacing:.5px;color:#757575;font-weight:600}")
                .append(".filelist{list-style:none;margin:0;padding:0}")
                .append(".filelist li{padding:8px 0;border-bottom:1px solid #f0f0f0;color:#424242;")
                .append("font-family:'SFMono-Regular',Consolas,'Liberation Mono',monospace;font-size:.92em;word-break:break-all}")
                .append(".filelist li:last-child{border-bottom:0}")
                .append(".filelist li.bad{color:#c62828}")
                .append(".filelist li::before{content:'';display:inline-block;width:6px;height:6px;border-radius:50%;")
                .append("background:#338a3e;margin-right:10px;vertical-align:middle}")
                .append(".filelist li.bad::before{background:#c62828}")
                .append(".actions{padding:14px 20px;display:flex;gap:10px;flex-wrap:wrap}")
                .append(".btn{display:inline-block;background:var(--primary);color:var(--on-primary);border:0;border-radius:4px;")
                .append("padding:10px 22px;font-size:14px;font-weight:500;text-transform:uppercase;letter-spacing:.5px;")
                .append("text-decoration:none;box-shadow:0 1px 2px rgba(0,0,0,.2);transition:filter .15s,box-shadow .15s}")
                .append(".btn:hover{filter:brightness(.92);box-shadow:0 2px 4px rgba(0,0,0,.25)}")
                .append(".btn.alt{background:#fff;color:var(--primary);border:1px solid var(--primary);box-shadow:none}")
                .append(".btn.alt:hover{background:rgba(0,0,0,.04);box-shadow:0 1px 2px rgba(0,0,0,.1)}")
                .append(".hint{padding:0 20px 16px;color:#757575;font-size:.85em}")
                .append(headerInfoCss())
                .append("</style></head><body>");

        // Header (same as index, without the ring controls)
        appendHeader(html, machineName, false);

        html.append("<main>");

        // Status card
        html.append("<div class=\"card\">")
                .append("<div class=\"status\">")
                .append("<div class=\"ico\">").append(iconChar).append("</div>")
                .append("<div class=\"txt\"><h2>").append(statusTitle).append("</h2>")
                .append("<p>").append(statusSub).append("</p></div>")
                .append("</div>");

        if (!savedNames.isEmpty()) {
            html.append("<div class=\"section\"><h3>File salvati (")
                    .append(savedNames.size()).append(")</h3><ul class=\"filelist\">");
            for (String n : savedNames) {
                html.append("<li>").append(escapeHtml(n)).append("</li>");
            }
            html.append("</ul></div>");
        }
        if (!rejected.isEmpty()) {
            html.append("<div class=\"section\"><h3>Rifiutati (")
                    .append(rejected.size()).append(")</h3><ul class=\"filelist\">");
            for (String n : rejected) {
                html.append("<li class=\"bad\">").append(escapeHtml(n)).append("</li>");
            }
            html.append("</ul>")
                    .append("<p class=\"hint\">Estensioni accettate: .nc .gcode .gco .tap .cnc .ngc .txt · ")
                    .append("dimensione max ").append(MAX_UPLOAD_BYTES / (1024 * 1024)).append(" MB</p>")
                    .append("</div>");
        }

        // Actions. The full-success case is handled server-side via a 303
        // redirect to /?ok=N, so this page is only ever rendered when at least
        // one file was rejected — no auto-dismiss here, the user reads the
        // details and clicks back when ready.
        html.append("<div class=\"actions\">")
                .append("<a class=\"btn\" href=\"/\">Torna alla lista</a>");
        if (!rejected.isEmpty() || saved == 0) {
            html.append("<a class=\"btn alt\" href=\"/\">Riprova upload</a>");
        }
        html.append("</div></div>");
        html.append("</main>");

        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.toString());
    }

    /** App-theme colours exposed to the page as CSS custom properties. */
    private String cssVars() {
        return ":root{--primary:" + palette.primary
                + ";--primary-dark:" + palette.primaryDark
                + ";--accent:" + palette.accent
                + ";--on-primary:" + palette.onPrimary
                + ";--on-accent:" + palette.onAccent + "}";
    }

    /** Wrench favicon as an inline SVG data URI, tinted with the primary colour. */
    private String faviconHref() {
        String fill = palette.primary.replace("#", "%23");
        return "data:image/svg+xml,"
                + "%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20viewBox%3D%270%200%2064%2064%27%3E"
                + "%3Crect%20width%3D%2764%27%20height%3D%2764%27%20rx%3D%2712%27%20fill%3D%27" + fill + "%27%2F%3E"
                + "%3Ctext%20x%3D%2750%25%27%20y%3D%2754%27%20font-size%3D%2742%27%20text-anchor%3D%27middle%27%20fill%3D%27white%27%20font-family%3D%27sans-serif%27%3E%E2%9A%99%3C%2Ftext%3E"
                + "%3C%2Fsvg%3E";
    }

    /**
     * Shared page header: machine name + live machine state, and (on the index)
     * the "find the phone" controls on the right.
     */
    private void appendHeader(StringBuilder html, String machineName, boolean withRing) {
        String state = MachineStatusListener.getInstance().getState();
        FileSenderListener fs = FileSenderListener.getInstance();
        boolean hasFile = fs.getGcodeFile() != null;
        String fileName = hasFile ? fs.getGcodeFileName() : "—";
        int total = fs.getRowsInFile() != null ? fs.getRowsInFile() : 0;
        int sent = fs.getRowsSent() != null ? fs.getRowsSent() : 0;
        int perc = total > 0 ? (int) Math.round(sent * 100.0 / total) : 0;
        String elapsed = (hasFile && fs.getElapsedTime() != null) ? fs.getElapsedTime() : "—";
        // Ultimo commento del GCode in esecuzione (stessa info del File Sender);
        // la riga è nascosta finché non c'è nulla da mostrare.
        String comment = (hasFile && fs.getLastComment() != null) ? fs.getLastComment() : "";
        String mname = (machineName != null && !machineName.isEmpty())
                ? machineName : "Nessuna macchina connessa";
        html.append("<header><div class=\"row\">")
                .append("<div class=\"logo\">⚙</div>")
                .append("<div class=\"hinfo\">")
                .append("<div class=\"eyebrow\">GRBL Machining &middot; connesso a</div>")
                .append("<div class=\"mname\" id=\"machineName\">").append(escapeHtml(mname)).append("</div>")
                .append("<div class=\"jline\">File: <b id=\"jName\">").append(escapeHtml(fileName))
                .append("</b> &middot; Stato: <b id=\"machineState\">")
                .append(escapeHtml(state != null && !state.isEmpty() ? state : "—")).append("</b></div>")
                .append("<div class=\"jline\" id=\"jCommentLine\"")
                .append(comment.isEmpty() ? " style=\"display:none\"" : "")
                .append("><b id=\"jComment\">").append(escapeHtml(comment)).append("</b></div>")
                .append("<div class=\"jline\">Righe <b id=\"jSent\">").append(sent)
                .append("</b> / <b id=\"jTotal\">").append(total).append("</b> (<b id=\"jPerc\">").append(perc)
                .append("%</b>) &middot; Tempo <b id=\"jElapsed\">").append(escapeHtml(elapsed))
                .append("</b> &middot; Stima <b id=\"jEta\">—</b></div>")
                .append("</div>");
        if (withRing) {
            html.append("<div class=\"ring\">")
                    .append("<button type=\"button\" id=\"ringBtn\">Fai squillare</button>")
                    .append("<button type=\"button\" id=\"stopRingBtn\" class=\"stop\" style=\"display:none\">Ferma</button>")
                    .append("<span class=\"msg\" id=\"ringMsg\" style=\"display:none\"></span>")
                    .append("</div>");
        }
        html.append("</div></header>");
    }

    /** CSS for the header info block (prominent machine name + two info lines). */
    private static String headerInfoCss() {
        return "header .eyebrow{font-size:12px;opacity:.8;letter-spacing:.3px}"
                + "header .mname{font-size:21px;font-weight:500;line-height:1.2;margin:1px 0 8px}"
                + "header .jline{font-size:13.5px;opacity:.95;margin-bottom:3px}"
                + "header .jline b{font-weight:600}";
    }

    /** Client logic for the header "find the phone" buttons. */
    private String ringScript() {
        return "<script>(function(){"
                + "var rb=document.getElementById('ringBtn');"
                + "var sb=document.getElementById('stopRingBtn');"
                + "var msg=document.getElementById('ringMsg');"
                + "if(!rb)return;"
                + "var timer=null;"
                + "function reset(){if(timer){clearTimeout(timer);timer=null;}"
                + "sb.style.display='none';rb.style.display='';msg.style.display='none';}"
                + "function post(u,cb){fetch(u,{method:'POST'}).then(function(r){cb(r.ok);})"
                + ".catch(function(){cb(false);});}"
                + "rb.addEventListener('click',function(){post('/ring',function(ok){"
                + "if(ok){rb.style.display='none';sb.style.display='';msg.style.display='';"
                + "msg.textContent='Sta squillando\\u2026';"
                + "timer=setTimeout(reset,30000);}"
                + "else{msg.style.display='';msg.textContent='Errore';}});});"
                + "sb.addEventListener('click',function(){post('/ring/stop',function(){reset();});});"
                + "})();</script>";
    }

    /** JSON snapshot of machine state + current job progress, polled by the page. */
    private Response handleStatus() {
        FileSenderListener fs = FileSenderListener.getInstance();
        MachineStatusListener ms = MachineStatusListener.getInstance();
        String state = ms.getState();
        boolean streaming = FileStreamerIntentService.getIsServiceRunning();
        boolean hasFile = fs.getGcodeFile() != null;
        boolean connected = !MachineStatusListener.STATE_NOT_CONNECTED.equals(state);
        int total = fs.getRowsInFile() != null ? fs.getRowsInFile() : 0;
        int sent = fs.getRowsSent() != null ? fs.getRowsSent() : 0;
        long elapsedSec = (streaming && fs.getJobStartTime() > 0)
                ? (System.currentTimeMillis() - fs.getJobStartTime()) / 1000 : 0;
        MachineStatusListener.OverridePercents ovr = ms.getOverridePercents();
        int ovrFeed = (ovr != null) ? ovr.feed : 100;
        int ovrSpindle = (ovr != null) ? ovr.spindle : 100;
        String machine = HttpServerManager.getMachineName();
        String json = "{"
                + "\"state\":\"" + jsonEscape(state != null ? state : "") + "\","
                + "\"machine\":\"" + jsonEscape(machine != null ? machine : "") + "\","
                + "\"status\":\"" + jsonEscape(fs.getStatus() != null ? fs.getStatus() : "") + "\","
                + "\"connected\":" + connected + ","
                + "\"hasFile\":" + hasFile + ","
                + "\"streaming\":" + streaming + ","
                + "\"file\":\"" + jsonEscape(hasFile ? fs.getGcodeFileName() : "") + "\","
                + "\"comment\":\"" + jsonEscape(hasFile && fs.getLastComment() != null ? fs.getLastComment() : "") + "\","
                + "\"sent\":" + sent + ","
                + "\"total\":" + total + ","
                + "\"elapsed\":\"" + jsonEscape(fs.getElapsedTime() != null ? fs.getElapsedTime() : "") + "\","
                + "\"elapsedSec\":" + elapsedSec + ","
                + "\"ovrFeed\":" + ovrFeed + ","
                + "\"ovrSpindle\":" + ovrSpindle
                + "}";
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
    }

    /**
     * Deletes a G-code file from the list. Same-origin guarded (it changes
     * device state) and refuses to delete the file currently being streamed —
     * pulling the file out from under a running job is dangerous on a CNC.
     */
    private Response handleDelete(IHTTPSession session) throws IOException {
        if (!isSameOriginIfPresent(session)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "Cross-origin denied");
        }
        // Some clients send a body on POST; parse it so NanoHTTPD doesn't leave
        // the socket in a weird state. The file name itself is read from the
        // query string of the action URL, which is always parsed into params.
        try { session.parseBody(new HashMap<String, String>()); } catch (Exception ignore) {}

        String name = null;
        List<String> p = session.getParameters().get("name");
        if (p != null && !p.isEmpty()) name = p.get(0);

        Response redirect = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
        if (name == null || name.isEmpty()) {
            redirect.addHeader("Location", "/");
            return redirect;
        }

        // Safety: never delete the file currently being streamed.
        FileSenderListener fs = FileSenderListener.getInstance();
        if (FileStreamerIntentService.getIsServiceRunning() && name.equals(fs.getGcodeFileName())) {
            redirect.addHeader("Location", "/?delerr=busy");
            return redirect;
        }

        File target = safeResolve(sanitizeFileName(name));
        if (target != null && target.isFile() && hasAllowedExtension(target.getName())) {
            if (!target.delete()) Log.w(TAG, "delete failed for " + name);
        }
        redirect.addHeader("Location", "/");
        return redirect;
    }

    /** Polls /status every 2s and updates the header job line in place. */
    private String jobStatusScript() {
        return "<script>(function(){"
                + "function p(n){return(n<10?'0':'')+n;}"
                + "function fmt(s){s=Math.max(0,Math.round(s));var h=Math.floor(s/3600),"
                + "m=Math.floor((s%3600)/60),x=s%60;return p(h)+':'+p(m)+':'+p(x);}"
                + "function set(id,v){var e=document.getElementById(id);if(e)e.textContent=v;}"
                + "function upd(){fetch('/status',{cache:'no-store'}).then(function(r){return r.json();})"
                + ".then(function(d){"
                + "set('machineName',d.connected?(d.machine||'Macchina connessa'):'Nessuna macchina connessa');"
                + "document.title=d.connected?(d.machine||'Macchina connessa'):'GRBL Machining';"
                + "set('machineState',d.state||'\\u2014');"
                + "set('ovrFeed',(d.ovrFeed!=null?d.ovrFeed:100)+'%');"
                + "set('ovrSpindle',(d.ovrSpindle!=null?d.ovrSpindle:100)+'%');"
                + "var dis=!d.connected,cb=document.querySelectorAll('.ctrl [data-cmd]');"
                + "for(var i=0;i<cb.length;i++){cb[i].disabled=dis;}"
                + "set('jName',d.hasFile?d.file:'\\u2014');set('jSent',d.sent);set('jTotal',d.total);"
                + "var cm=(d.hasFile&&d.comment)?d.comment:'';set('jComment',cm);"
                + "var cl=document.getElementById('jCommentLine');if(cl)cl.style.display=cm?'':'none';"
                + "var perc=d.total>0?Math.round(d.sent*100/d.total):0;set('jPerc',perc+'%');"
                + "set('jElapsed',d.hasFile?d.elapsed:'\\u2014');"
                + "var eta='\\u2014';if(d.streaming&&d.sent>0&&d.total>d.sent){"
                + "eta=fmt(d.elapsedSec*(d.total-d.sent)/d.sent);}set('jEta',eta);"
                + "}).catch(function(){});}"
                + "upd();setInterval(upd,2000);"
                + "})();</script>";
    }

    /**
     * Wires the machine-control buttons to POST /control?cmd=… . The "stop"
     * (soft reset) button asks for confirmation first, since it aborts the job.
     */
    private String controlScript() {
        return "<script>(function(){"
                + "var btns=document.querySelectorAll('.ctrl [data-cmd]');"
                + "for(var i=0;i<btns.length;i++){(function(b){"
                + "b.addEventListener('click',function(){"
                + "var cmd=b.getAttribute('data-cmd');"
                + "if(cmd==='stop'&&!confirm('Arrestare la macchina? Il job in corso verrà interrotto.'))return;"
                + "fetch('/control?cmd='+encodeURIComponent(cmd),{method:'POST'}).catch(function(){});"
                + "});})(btns[i]);}"
                + "})();</script>";
    }

    /** Confirmation prompt before submitting a delete form. */
    private String deleteConfirmScript() {
        return "<script>(function(){"
                + "var forms=document.querySelectorAll('.delform');"
                + "for(var i=0;i<forms.length;i++){(function(f){"
                + "f.addEventListener('submit',function(e){"
                + "if(!confirm('Eliminare il file \"'+f.getAttribute('data-name')+'\"?'))e.preventDefault();"
                + "});})(forms[i]);}"
                + "})();</script>";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private List<File> listGcodeFiles() {
        if (rootDir == null || !rootDir.isDirectory()) return Collections.emptyList();
        File[] arr = rootDir.listFiles();
        if (arr == null) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        for (File f : arr) {
            if (f.isFile() && hasAllowedExtension(f.getName())) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private boolean hasAllowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXT.contains(ext);
    }

    private String sanitizeFileName(String raw) {
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\x00-\\x1f]", "");
        if (name.equals(".") || name.equals("..")) name = "_" + name;
        return name;
    }

    private File safeResolve(String name) throws IOException {
        File f = new File(rootDir, name);
        String rootCanon = rootDir.getCanonicalPath();
        String fCanon = f.getCanonicalPath();
        if (!fCanon.equals(rootCanon) && !fCanon.startsWith(rootCanon + File.separator)) {
            return null;
        }
        return f;
    }

    /**
     * If {@code dest} doesn't exist, return it. Otherwise return a sibling like
     * {@code name (1).ext}, {@code name (2).ext}, ... up to a cap. Returns null
     * if no slot is free or canonicalisation fails.
     */
    private File pickNonExistingName(File dest) throws IOException {
        if (!dest.exists()) return dest;
        String name = dest.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext  = (dot >= 0) ? name.substring(dot)    : "";
        for (int i = 1; i <= MAX_RENAME_ATTEMPTS; i++) {
            File candidate = safeResolve(base + " (" + i + ")" + ext);
            if (candidate == null) return null;
            if (!candidate.exists()) return candidate;
        }
        return null;
    }

    private boolean copyAndReplace(File src, File dest) {
        try (InputStream in = new FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dest, false)) {
            byte[] buf = new byte[8192];
            int n;
            long written = 0;
            while ((n = in.read(buf)) > 0) {
                written += n;
                if (written > MAX_UPLOAD_BYTES) {
                    Log.w(TAG, "upload exceeded MAX_UPLOAD_BYTES, aborting");
                    return false;
                }
                out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copy failed", e);
            return false;
        }
    }

    /**
     * Returns true when no Origin header is present, OR when its hostname
     * matches the request Host hostname (port-insensitive). Ports are ignored
     * because some clients drop the port from Host when it's the default for
     * the scheme, and same-origin POSTs from the browser legitimately differ
     * only by port encoding. The hostname-only check is sufficient defence
     * against cross-site CSRF.
     */
    private boolean isSameOriginIfPresent(IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        // Treat the literal string "null" the same as a missing Origin: per
        // the Fetch spec some browsers (notably Chrome mobile on multipart
        // form POSTs) send Origin: null even for same-origin requests, e.g.
        // when the form is inside a redirect chain or an opaque context.
        // A "null" Origin gives us no information either way, so don't use it
        // to deny — fall back to allowing (CSRF defence in depth still relies
        // on the Authorization header being attached only to same-origin).
        if (origin == null || origin.isEmpty() || "null".equalsIgnoreCase(origin)) return true;
        String host = session.getHeaders().get("host");
        if (host == null) {
            Log.w(TAG, "CSRF reject: Origin=" + origin + " but Host missing");
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String oHost = uri.getHost();
            if (oHost == null) {
                Log.w(TAG, "CSRF reject: cannot parse Origin=" + origin);
                return false;
            }
            String hHost = stripPort(host);
            if (oHost.equalsIgnoreCase(hHost)) return true;
            Log.w(TAG, "CSRF reject: Origin host=" + oHost + " != Host host=" + hHost);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "CSRF reject: Origin parse exception: " + origin, e);
            return false;
        }
    }

    private static String stripPort(String hostHeader) {
        int colon = hostHeader.lastIndexOf(':');
        // Bracketed IPv6 host like [::1]:8888 — leave bracketed part intact.
        if (hostHeader.startsWith("[")) {
            int bracket = hostHeader.indexOf(']');
            return (bracket > 0) ? hostHeader.substring(1, bracket) : hostHeader;
        }
        return (colon > 0) ? hostHeader.substring(0, colon) : hostHeader;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return a == b;
        return MessageDigest.isEqual(a, b);
    }

    private static void addSecurityHeaders(Response r) {
        if (r == null) return;
        r.addHeader("X-Content-Type-Options", "nosniff");
        r.addHeader("X-Frame-Options", "DENY");
        r.addHeader("Referrer-Policy", "no-referrer");
        r.addHeader("Cache-Control", "no-store");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
