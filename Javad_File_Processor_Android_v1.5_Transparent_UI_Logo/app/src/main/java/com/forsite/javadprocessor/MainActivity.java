package com.forsite.javadprocessor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.method.ScrollingMovementMethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_INPUT = 201;
    private static final int PICK_OUTPUT = 202;
    private static final String UPLOAD_URL = "https://app.javad.com/jca/#/dpos/upload";
    private static final int REPORT_PAGE_SIZE = 8;
    private static final String REPORT_URL = "https://app.javad.com/jca/#/dpos/report/list/%d/8/0/d/0";
    private static final int MAX_REPORT_PAGES = 10;

    private WebView web;
    private WebView loginWindow;
    private ScrollView controls;
    private FrameLayout browserPanel;
    private TextView inputPath, outputPath, uploadText, downloadText, status, failedText, log;
    private StripedProgressView uploadBar, downloadBar;
    private Button start, stop;
    private Button backToControls;
    private CheckBox reprocess;
    private EditText maxWait;
    private Uri inputTree, outputTree;
    private final List<Observation> observations = new ArrayList<>();
    private final List<Observation> submitted = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private int uploadIndex = 0, downloadIndex = 0, reportPage = 1;
    private int uploadAttempt = 0;
    private int chosenUploadIndex = -1;
    private int downloadSuccess = 0;
    private long reportDeadline = 0;
    private boolean running = false, stopping = false, selectingForUpload = false;
    private boolean downloadInProgress = false;
    private boolean loginConfirmed = false;
    private Phase phase = Phase.IDLE;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reportCheckTask = this::checkReportPage;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashRecorder();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bindViews();

        configureWebView(web);

        findViewById(R.id.inputButton).setOnClickListener(v -> chooseTree(PICK_INPUT));
        findViewById(R.id.outputButton).setOnClickListener(v -> chooseTree(PICK_OUTPUT));
        findViewById(R.id.loginButton).setOnClickListener(v -> showBrowser(UPLOAD_URL));
        backToControls = findViewById(R.id.backToControls);
        backToControls.setOnClickListener(v -> showControls());
        start.setOnClickListener(v -> startRun());
        stop.setOnClickListener(v -> { stopping = true; setStatus("Stopping safely after the current step…"); });
        restoreFolders();
        showRecordedCrash();
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("last_crash", trace.toString()).commit();
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void showRecordedCrash() {
        SharedPreferences diagnostics = getSharedPreferences("diagnostics", MODE_PRIVATE);
        String crash = diagnostics.getString("last_crash", "");
        if (!crash.isEmpty()) {
            diagnostics.edit().remove("last_crash").apply();
            String[] lines = crash.split("\\n");
            StringBuilder important = new StringBuilder("Previous crash:\n");
            for (int i = 0; i < Math.min(lines.length, 14); i++) important.append(lines[i]).append('\n');
            failedText.setText(important.toString());
            log.setText("LAST CRASH:\n" + crash);
            log.scrollTo(0, 0);
            status.setText("Diagnostic information recovered from the previous crash.");
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(target, true);
        target.addJavascriptInterface(new WebBridge(), "AndroidProcessor");
        target.setWebViewClient(new ProcessorWebClient());
        target.setWebChromeClient(new ProcessorChromeClient());
        target.setDownloadListener(new ReportDownloadListener());
    }

    private void bindViews() {
        web = findViewById(R.id.webView); controls = findViewById(R.id.controlScroll);
        browserPanel = findViewById(R.id.browserPanel); inputPath = findViewById(R.id.inputPath);
        outputPath = findViewById(R.id.outputPath); uploadText = findViewById(R.id.uploadText);
        downloadText = findViewById(R.id.downloadText); status = findViewById(R.id.statusText);
        failedText = findViewById(R.id.failedText); log = findViewById(R.id.logText);
        uploadBar = findViewById(R.id.uploadProgress); downloadBar = findViewById(R.id.downloadProgress);
        start = findViewById(R.id.startButton); stop = findViewById(R.id.stopButton);
        reprocess = findViewById(R.id.reprocessCheck); maxWait = findViewById(R.id.maxWait);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setVerticalScrollBarEnabled(true);
        log.setTextIsSelectable(true);
    }

    private void chooseTree(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, flags);
        if (request == PICK_INPUT) { inputTree = uri; inputPath.setText(displayTree(uri)); }
        if (request == PICK_OUTPUT) { outputTree = uri; outputPath.setText(displayTree(uri)); }
        SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
        if (inputTree != null) edit.putString("input", inputTree.toString());
        if (outputTree != null) edit.putString("output", outputTree.toString());
        edit.apply();
    }

    private void restoreFolders() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String in = p.getString("input", ""), out = p.getString("output", "");
        if (!in.isEmpty()) { inputTree = Uri.parse(in); inputPath.setText(displayTree(inputTree)); }
        if (!out.isEmpty()) { outputTree = Uri.parse(out); outputPath.setText(displayTree(outputTree)); }
    }

    private String displayTree(Uri uri) {
        String id = DocumentsContract.getTreeDocumentId(uri);
        return id.replace("primary:", "Internal storage/");
    }

    private void startRun() {
        if (inputTree == null || outputTree == null) { toast("Choose both folders first."); return; }
        if (!loginConfirmed) { toast("Sign in to JAVAD first and wait for the login-confirmed message."); return; }
        observations.clear(); submitted.clear(); failures.clear(); uploadIndex = 0; downloadIndex = 0; downloadSuccess = 0;
        downloadInProgress = false;
        scanTree(inputTree, DocumentsContract.getTreeDocumentId(inputTree), observations);
        if (!reprocess.isChecked()) {
            Set<String> completed = new HashSet<>();
            scanTxtNames(outputTree, DocumentsContract.getTreeDocumentId(outputTree), completed);
            observations.removeIf(item -> completed.contains(stem(item.name).toLowerCase(Locale.US)));
        }
        observations.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
        if (observations.isEmpty()) { toast("No .jps observation files were found."); return; }
        running = true; stopping = false; phase = Phase.UPLOAD;
        start.setEnabled(false); stop.setEnabled(true); failedText.setText(""); log.setText("");
        updateProgress(true, 0, observations.size()); updateProgress(false, 0, observations.size());
        appendLog("UPLOAD PHASE — " + observations.size() + " observation file(s)");
        showBrowser(UPLOAD_URL);
    }

    private void scanTree(Uri tree, String parentId, List<Observation> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTree(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".jps"))
                    found.add(new Observation(name, DocumentsContract.buildDocumentUriUsingTree(tree, id)));
            }
        } catch (Exception e) { appendLog("Folder scan warning: " + e.getMessage()); }
    }

    private void scanTxtNames(Uri tree, String parentId, Set<String> found) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanTxtNames(tree, id, found);
                else if (name != null && name.toLowerCase(Locale.US).endsWith(".txt")) found.add(stem(name).toLowerCase(Locale.US));
            }
        } catch (Exception e) { appendLog("Results scan warning: " + e.getMessage()); }
    }

    private void showBrowser(String url) {
        controls.setVisibility(View.GONE); browserPanel.setVisibility(View.VISIBLE);
        if (url != null) web.loadUrl(url);
    }
    private void showControls() { browserPanel.setVisibility(View.GONE); controls.setVisibility(View.VISIBLE); }

    private class ProcessorWebClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) { return false; }
        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            CookieManager.getInstance().flush();
            if (!running && url.contains("#/dpos/upload")) {
                handler.postDelayed(() -> verifyLoginPage(), 3000);
            }
            if (!running || stopping) return;
            if (phase == Phase.UPLOAD && url.contains("#/dpos/upload")) handler.postDelayed(() -> prepareUploadPage(), 5000);
            else if (phase == Phase.DOWNLOAD && url.contains("#/dpos/report")) scheduleReportCheck(1800);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            appendLog("JAVAD browser restarted while loading reports; recovering automatically…");
            if (view == loginWindow) {
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
                loginWindow = null;
                web.loadUrl(UPLOAD_URL);
                return true;
            }
            if (view == web) {
                browserPanel.removeView(web);
                web.destroy();
                web = new WebView(MainActivity.this);
                browserPanel.addView(web, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                configureWebView(web);
                backToControls.bringToFront();
                if (running && phase == Phase.DOWNLOAD) handler.postDelayed(() -> loadReportPage(reportPage), 1500);
                else if (running && phase == Phase.UPLOAD) handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 1500);
                return true;
            }
            return false;
        }
    }

    private void verifyLoginPage() {
        String js = "(function(){var text=(document.body.innerText||'');" +
                "if(/drop files/i.test(text))AndroidProcessor.loginReady();" +
                "else AndroidProcessor.loginWaiting();})();";
        web.evaluateJavascript(js, null);
    }

    private class ProcessorChromeClient extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (!running || !selectingForUpload || uploadIndex >= observations.size()) return false;
            if (chosenUploadIndex == uploadIndex) {
                callback.onReceiveValue(null);
                appendLog("Ignored a duplicate file request for " + observations.get(uploadIndex).name);
                return true;
            }
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback; selectingForUpload = false;
            chosenUploadIndex = uploadIndex;
            callback.onReceiveValue(new Uri[]{observations.get(uploadIndex).uri}); pendingFileCallback = null;
            return true;
        }

        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (loginWindow != null) {
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
            }
            loginWindow = new WebView(MainActivity.this);
            WebSettings s = loginWindow.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setAllowContentAccess(true);
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setSupportMultipleWindows(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(loginWindow, true);
            loginWindow.setWebChromeClient(this);
            loginWindow.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView child, android.webkit.WebResourceRequest request) {
                    return false;
                }
                @Override public void onPageFinished(WebView child, String url) {
                    CookieManager.getInstance().flush();
                    setStatus("Complete the JAVAD sign-in shown on this screen.");
                    if (url != null && url.startsWith("https://app.javad.com/jca/")) {
                        handler.postDelayed(() -> onCloseWindow(child), 1500);
                    }
                }
            });
            browserPanel.addView(loginWindow, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            loginWindow.bringToFront();
            backToControls.bringToFront();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(loginWindow);
            resultMsg.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView window) {
            if (window == loginWindow) {
                CookieManager.getInstance().flush();
                browserPanel.removeView(loginWindow);
                loginWindow.destroy();
                loginWindow = null;
                web.bringToFront();
                backToControls.bringToFront();
                setStatus("Sign-in window closed. Verifying JAVAD login…");
                web.loadUrl(UPLOAD_URL);
            }
        }
    }

    private void prepareUploadPage() {
        // Ignore delayed callbacks left over from the final upload page.
        if (phase != Phase.UPLOAD) return;
        if (!running || stopping) { finishStopped(); return; }
        if (uploadIndex >= observations.size()) { beginDownloads(); return; }
        Observation item = observations.get(uploadIndex);
        setStatus("Uploading " + (uploadIndex + 1) + " of " + observations.size() + ": " + item.name);
        appendLog("[Upload " + (uploadIndex + 1) + "/" + observations.size() + "] " + item.name);
        selectingForUpload = true;
        String filename = jsQuote(item.name);
        String js = "(function(){var tries=0,name='" + filename + "'.toLowerCase();" +
                "var findDrop=function(){var all=[].slice.call(document.querySelectorAll('div,span,p,a,button'));" +
                "return all.filter(function(x){return /drop files.*click here/i.test((x.innerText||'').trim());})" +
                ".sort(function(a,b){return (a.innerText||'').length-(b.innerText||'').length;})[0];};" +
                "var open=setInterval(function(){tries++;var inputs=document.querySelectorAll('input[type=file]'),drop=findDrop();" +
                "if(drop){clearInterval(open);var r=drop.getBoundingClientRect();AndroidProcessor.tapUploadArea(r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio||1);afterPick();}" +
                "else if(inputs.length){clearInterval(open);inputs[inputs.length-1].click();afterPick();}" +
                "else if(tries>=45){clearInterval(open);AndroidProcessor.uploadError('Drop files area did not appear');}},1000);" +
                "function afterPick(){var n=0,added=setInterval(function(){n++;var body=(document.body.innerText||'').toLowerCase();" +
                "if(body.indexOf(name)>=0){clearInterval(added);submit();}" +
                "else if(n>=30){clearInterval(added);AndroidProcessor.uploadError('JAVAD did not display the selected file');}},1000);}" +
                "function submit(){var b=[].slice.call(document.querySelectorAll('button,a')).find(function(x){return /^submit$/i.test((x.innerText||'').trim())});" +
                "if(!b||b.disabled){AndroidProcessor.uploadError('Submit button not ready');return;}b.click();var n=0,t=setInterval(function(){n++;var x=(document.body.innerText||'').toLowerCase();" +
                "if(/observe the progress|has been submitted|submitted successfully/.test(x)){clearInterval(t);AndroidProcessor.submitted();}" +
                "else if(n>=60){clearInterval(t);AndroidProcessor.uploadError('JAVAD did not confirm submission');}},1000);}})();";
        web.evaluateJavascript(js, null);
    }

    private class WebBridge {
        @JavascriptInterface public void tapUploadArea(float cssX, float cssY, float pixelRatio) { runOnUiThread(() -> {
            float x = cssX * pixelRatio;
            float y = cssY * pixelRatio;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(down);
            web.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
            appendLog("Tapped JAVAD's Drop files area for " + observations.get(uploadIndex).name);
        }); }
        @JavascriptInterface public void loginReady() { runOnUiThread(() -> {
            loginConfirmed = true;
            setStatus("JAVAD login confirmed — Upload Data is ready.");
            toast("JAVAD login confirmed. Tap Return to Processor.");
        }); }
        @JavascriptInterface public void loginWaiting() { runOnUiThread(() ->
                { loginConfirmed = false; setStatus("JAVAD is not signed in yet. Complete the login in this app."); }); }
        @JavascriptInterface public void submitted() { runOnUiThread(() -> {
            Observation item = observations.get(uploadIndex); submitted.add(item);
            uploadAttempt = 0; chosenUploadIndex = -1;
            appendLog("CONFIRMED submitted: " + item.name); uploadIndex++; updateProgress(true, uploadIndex, observations.size());
            if (stopping) { finishStopped(); return; }
            if (uploadIndex >= observations.size()) { handler.postDelayed(() -> beginDownloads(), 3000); return; }
            setStatus("Resetting Upload Data for the next plot…");
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
        }); }
        @JavascriptInterface public void uploadError(String message) { runOnUiThread(() -> failUpload(message)); }
        @JavascriptInterface public void reportReady() { runOnUiThread(() -> clickTxtDownload()); }
        @JavascriptInterface public void reportMissing() { runOnUiThread(() -> nextReportPage()); }
        @JavascriptInterface public void reportText(String text) { runOnUiThread(() -> {
            if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
            Observation item = submitted.get(downloadIndex);
            boolean saved = saveTextResult(text, txtName(item.name));
            completeDownload(item, saved ? null : "Could not save TXT to results folder");
        }); }
        @JavascriptInterface public void reportDownloadError(String message) { runOnUiThread(() -> {
            if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
            completeDownload(submitted.get(downloadIndex), "Could not read JAVAD TXT: " + message);
        }); }
    }

    private void failUpload(String message) {
        Observation item = observations.get(uploadIndex);
        uploadAttempt++;
        if (uploadAttempt < 3) {
            appendLog("JAVAD was not ready for " + item.name + "; retrying (" + uploadAttempt + "/3)");
            chosenUploadIndex = -1;
            web.loadUrl(String.format(Locale.US, REPORT_URL, 1));
            handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 10000);
            return;
        }
        failures.add(item.name + " — upload: " + message);
        appendLog("UPLOAD FAILED " + item.name + ": " + message);
        uploadAttempt = 0; chosenUploadIndex = -1; uploadIndex++; updateProgress(true, uploadIndex, observations.size());
        handler.postDelayed(() -> web.loadUrl(UPLOAD_URL), 2000);
    }

    private void beginDownloads() {
        try {
        if (!running) return;
        // Several WebView callbacks may notice the final submitted plot. Only
        // the first one is allowed to transition into the download phase.
        if (phase == Phase.DOWNLOAD) return;
        phase = Phase.DOWNLOAD;
        handler.removeCallbacks(reportCheckTask);
        appendLog("DOWNLOAD PHASE — " + submitted.size() + " submitted file(s)");
        if (submitted.isEmpty()) { finishRun(); return; }
        downloadIndex = 0; reportPage = 1;
        resetReportDeadline();
        loadReportPage(reportPage);
        } catch (Throwable error) {
            handleDownloadFailure("Starting Browse Reports", error);
        }
    }

    private void loadReportPage(int pageNumber) {
        try {
            web.loadUrl(String.format(Locale.US, REPORT_URL, pageNumber));
            // JAVAD uses Angular hash routes. WebView may change the route without
            // calling onPageFinished, so always schedule the report inspection.
            scheduleReportCheck(4000);
        } catch (Throwable error) {
            handleDownloadFailure("Opening Browse Reports page " + pageNumber, error);
        }
    }

    private void handleDownloadFailure(String step, Throwable error) {
        String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        appendLog("DOWNLOAD ENGINE ERROR during " + step + " — " + detail);
        failures.add("Download engine — " + detail);
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls();
        failedText.setText("Download engine error: " + detail);
        status.setText("Download stopped safely. Send a photo of this error.");
    }

    private void scheduleReportCheck(long delayMs) {
        handler.removeCallbacks(reportCheckTask);
        handler.postDelayed(reportCheckTask, delayMs);
    }

    private void checkReportPage() {
        if (!running || phase != Phase.DOWNLOAD || downloadIndex >= submitted.size()) return;
        String name = jsQuote(submitted.get(downloadIndex).name);
        setStatus("Checking reports for " + submitted.get(downloadIndex).name + " — page " + reportPage);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r&&r.querySelectorAll('save-as').length>=2)AndroidProcessor.reportReady();else AndroidProcessor.reportMissing();})();";
        web.evaluateJavascript(js, null);
    }

    private void clickTxtDownload() {
        String name = jsQuote(submitted.get(downloadIndex).name);
        String js = "(function(){var n='" + name + "'.toLowerCase(),rows=[].slice.call(document.querySelectorAll('tbody tr'));" +
                "var r=rows.find(function(x){var c=x.querySelectorAll('td');return c.length>1&&(c[1].innerText||'').trim().toLowerCase()===n});" +
                "if(r){var s=r.querySelectorAll('save-as');if(s.length>1){var t=s[1].querySelector('a,button,[role=button]')||s[1];" +
                "t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));t.click();}}})()";
        web.evaluateJavascript(js, null);
    }

    private void nextReportPage() {
        if (System.currentTimeMillis() >= reportDeadline) {
            completeDownload(submitted.get(downloadIndex), "No downloadable TXT report appeared within the maximum wait");
            return;
        }
        int pages = Math.min(MAX_REPORT_PAGES, Math.max(1, (submitted.size() + REPORT_PAGE_SIZE - 1) / REPORT_PAGE_SIZE));
        if (reportPage < pages) { reportPage++; loadReportPage(reportPage); }
        else handler.postDelayed(() -> { reportPage = 1; loadReportPage(1); }, 15000);
    }

    private class ReportDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
            if (!running || downloadIndex >= submitted.size()) return;
            if ((mime != null && mime.toLowerCase(Locale.US).contains("pdf")) ||
                    (url != null && url.toLowerCase(Locale.US).contains(".pdf"))) {
                appendLog("Ignored PDF download"); return;
            }
            if (downloadInProgress) {
                appendLog("Ignored a duplicate TXT download event");
                return;
            }
            downloadInProgress = true;
            Observation item = submitted.get(downloadIndex);
            try {
                if (url != null && url.startsWith("blob:")) {
                    appendLog("Reading JAVAD TXT report for " + item.name);
                    String quotedUrl = org.json.JSONObject.quote(url);
                    String js = "fetch(" + quotedUrl + ").then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();})" +
                            ".then(function(t){AndroidProcessor.reportText(t);})" +
                            ".catch(function(e){AndroidProcessor.reportDownloadError(String(e));});";
                    web.evaluateJavascript(js, null);
                    return;
                }
                if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                    downloadInProgress = false;
                    completeDownload(item, "Unsupported JAVAD download address");
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType("text/plain");
                request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, txtName(item.name));
                long id = ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                waitForDownload(id, item);
            } catch (Throwable error) {
                downloadInProgress = false;
                completeDownload(item, "TXT download error: " + error.getMessage());
            }
        }
    }

    private boolean saveTextResult(String text, String filename) {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", filename);
            if (file == null) return false;
            try (OutputStream out = getContentResolver().openOutputStream(file, "w")) {
                if (out == null) return false;
                out.write((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            appendLog("Save error: " + e.getMessage());
            return false;
        }
    }

    private void waitForDownload(long id, Observation item) {
        new Thread(() -> {
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            for (int tries=0; tries<180; tries++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) {
                        int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (state == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri local = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                            boolean copied = copyResult(local, txtName(item.name));
                            runOnUiThread(() -> completeDownload(item, copied ? null : "Could not copy TXT to results folder")); return;
                        }
                        if (state == DownloadManager.STATUS_FAILED) { runOnUiThread(() -> completeDownload(item, "Android download failed")); return; }
                    }
                }
            }
            runOnUiThread(() -> completeDownload(item, "TXT download timed out"));
        }).start();
    }

    private boolean copyResult(Uri source, String filename) {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", filename);
            if (file == null) return false;
            try (FileInputStream in = new FileInputStream(new File(source.getPath())); OutputStream out = getContentResolver().openOutputStream(file, "w")) {
                byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n);
            }
            return true;
        } catch (Exception e) { appendLog("Save error: " + e.getMessage()); return false; }
    }

    private void completeDownload(Observation item, String error) {
        downloadInProgress = false;
        if (error == null) { downloadSuccess++; appendLog("Downloaded TXT report for " + item.name); }
        else { failures.add(item.name + " — download: " + error); appendLog("DOWNLOAD FAILED " + item.name + ": " + error); }
        downloadIndex++; updateProgress(false, downloadIndex, submitted.size());
        if (downloadIndex >= submitted.size() || stopping) finishRun();
        else { reportPage = 1; resetReportDeadline(); loadReportPage(1); }
    }

    private void finishRun() {
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls();
        failedText.setText(failures.isEmpty() ? "No files failed." : "Failed files:\n" + String.join("\n", failures));
        setStatus("Finished: " + downloadSuccess + " succeeded, " + failures.size() + " failed.");
        saveFailureList();
    }
    private void finishStopped() {
        running = false; phase = Phase.IDLE; handler.removeCallbacks(reportCheckTask);
        start.setEnabled(true); stop.setEnabled(false); showControls(); setStatus("Processing stopped safely.");
    }

    private void saveFailureList() {
        try {
            String parent = DocumentsContract.getTreeDocumentId(outputTree);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(outputTree, parent);
            Uri file = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", "failed_files_latest.txt");
            if (file == null) return;
            String body = failures.isEmpty() ? "No files failed during the latest run.\n" : "Failed files from the latest run:\n\n" + String.join("\n", failures) + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(file, "w")) { out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } catch (Exception e) { appendLog("Could not save failure list: " + e.getMessage()); }
    }

    private void updateProgress(boolean upload, int current, int total) {
        int pct = total == 0 ? 0 : Math.round(current * 100f / total);
        if (upload) { uploadBar.setProgress(pct); uploadText.setText("Uploading: " + pct + "% — " + current + " of " + total); }
        else { downloadBar.setProgress(pct); downloadText.setText("Downloading: " + pct + "% — " + current + " of " + total); }
    }
    private void setStatus(String message) { status.setText(message); }
    private void appendLog(String message) {
        runOnUiThread(() -> { String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); log.append(time + "  " + message + "\n"); });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String jsQuote(String value) { return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " "); }
    private static String txtName(String value) {
        int dot = value.lastIndexOf('.');
        return (dot > 0 ? value.substring(0, dot) : value) + ".txt";
    }
    private static String stem(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
    private void resetReportDeadline() {
        int minutes = 30;
        try { minutes = Math.max(1, Integer.parseInt(maxWait.getText().toString())); } catch (Exception ignored) {}
        reportDeadline = System.currentTimeMillis() + minutes * 60_000L;
    }

    private static class Observation {
        final String name; final Uri uri;
        Observation(String name, Uri uri) { this.name = name; this.uri = uri; }
    }
    private enum Phase { IDLE, UPLOAD, DOWNLOAD }
}
