package com.areebpreps.athenaeum;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Phase 5.2 (hybrid): the app bundles index.html inside the APK as a
 * fallback, but always runs from a writable copy in internal storage.
 * Every time the app comes to the foreground it quietly checks the live
 * GitHub repo for a newer index.html and swaps it in, so editing the site
 * on GitHub updates the installed app without a rebuild in most cases.
 * Camera uploads and blob downloads go through native bridges since a
 * file:// page can't do either on its own.
 */
public class MainActivity extends Activity {

    // Update this if the repo is ever renamed/moved.
    private static final String REPO_RAW_BASE =
            "https://raw.githubusercontent.com/areebpreps/athenaeum/main/";

    private static final int FILE_CHOOSER_REQUEST_CODE = 51;

    private WebView webView;
    private File localIndexFile;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupLocalSite();
        configureWebView();

        webView.loadUrl("file://" + localIndexFile.getAbsolutePath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForUpdateInBackground();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- local hosting ----------

    private void setupLocalSite() {
        File wwwDir = new File(getFilesDir(), "www");
        if (!wwwDir.exists()) wwwDir.mkdirs();
        localIndexFile = new File(wwwDir, "index.html");
        if (!localIndexFile.exists()) {
            copyAssetToFile("www/index.html", localIndexFile);
        }
    }

    private void copyAssetToFile(String assetPath, File outFile) {
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (IOException e) {
            // The bundled asset ships with the APK, so this should not fail.
        }
    }

    // ---------- GitHub sync ----------

    private void checkForUpdateInBackground() {
        new Thread(() -> {
            try {
                String latest = httpGet(REPO_RAW_BASE + "index.html");
                if (latest == null || latest.isEmpty()) return;
                String current = readFile(localIndexFile);
                if (!latest.equals(current)) {
                    writeFile(localIndexFile, latest);
                    runOnUiThread(() -> {
                        webView.loadUrl("file://" + localIndexFile.getAbsolutePath());
                        Toast.makeText(MainActivity.this, "Athenaeum updated.", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                // No internet or GitHub unreachable: keep using the local copy silently.
            }
        }).start();
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = conn.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private void writeFile(File f, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ---------- WebView setup ----------

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new AndroidDownloaderBridge(this), "AndroidDownloader");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                openExternally(uri);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView offscreen = new WebView(MainActivity.this);
                offscreen.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openExternally(request.getUrl());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openExternally(Uri.parse(url));
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(offscreen);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                pickIntent.setType("image/*");

                Intent cameraIntent = null;
                cameraPhotoUri = null;
                try {
                    File photoFile = createCaptureFile();
                    cameraPhotoUri = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fileprovider", photoFile);
                    cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (IOException e) {
                    cameraIntent = null;
                }

                Intent chooser = Intent.createChooser(pickIntent, "Add a photo");
                if (cameraIntent != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                }
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that link.", Toast.LENGTH_SHORT).show();
        }
    }

    private File createCaptureFile() throws IOException {
        File dir = new File(getExternalFilesDir(null), "captures");
        if (!dir.exists()) dir.mkdirs();
        String name = "IMG_" + System.currentTimeMillis() + ".jpg";
        return new File(dir, name);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return;
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.getDataString() == null) {
                if (cameraPhotoUri != null) {
                    results = new Uri[]{cameraPhotoUri};
                }
            } else if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else {
                results = new Uri[]{Uri.parse(data.getDataString())};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // ---------- blob download bridge ----------

    private static class AndroidDownloaderBridge {
        private final Activity activity;

        AndroidDownloaderBridge(Activity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void saveFile(String filename, String base64Data, String mimeType) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    String safeName = (filename == null || filename.trim().isEmpty())
                            ? "athenaeum-download" : filename.trim();
                    String type = (mimeType == null || mimeType.isEmpty())
                            ? "application/octet-stream" : mimeType;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveViaMediaStore(safeName, type, bytes);
                    } else {
                        saveViaLegacyPath(safeName, bytes);
                    }
                    toast("Saved to Downloads: " + filename);
                } catch (Exception e) {
                    toast("Couldn't save the file.");
                }
            }).start();
        }

        private void saveViaMediaStore(String name, String type, byte[] bytes) throws IOException {
            ContentResolver resolver = activity.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, type);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new IOException("MediaStore insert failed");

            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new IOException("Could not open output stream");
                out.write(bytes);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(item, values, null, null);
        }

        private void saveViaLegacyPath(String name, byte[] bytes) throws IOException {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
            }
        }

        private void toast(String msg) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
        }
    }
}
