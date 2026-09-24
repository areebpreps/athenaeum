package com.areebpreps.athenaeum;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exposes the phone's own text-to-speech engine to the page as
 * window.AndroidTTS. Android WebView has no window.speechSynthesis, so
 * index.html contains a small shim that rebuilds it on top of this class.
 */
public class TtsBridge {
    private final Activity activity;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> waiting = new ArrayList<>();
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean failed = false;

    public TtsBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        main.post(this::init);
    }

    private void init() {
        tts = new TextToSpeech(activity.getApplicationContext(), status -> main.post(() -> {
            if (status == TextToSpeech.SUCCESS) {
                ready = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String uid) {
                        String[] p = uid.split("\\|");
                        if (p.length == 3 && "0".equals(p[1])) emit("start", p[0]);
                    }
                    @Override public void onDone(String uid) {
                        String[] p = uid.split("\\|");
                        if (p.length == 3 && Integer.parseInt(p[1]) == Integer.parseInt(p[2]) - 1) emit("end", p[0]);
                    }
                    @Override public void onError(String uid) {
                        String[] p = uid.split("\\|");
                        emit("error", p[0]);
                    }
                });
            } else {
                failed = true;
            }
            List<Runnable> run = new ArrayList<>(waiting);
            waiting.clear();
            for (Runnable r : run) r.run();
            if (ready) emit("voices", "");
        }));
    }

    private void runWhenReady(Runnable r) {
        if (ready || failed) r.run(); else waiting.add(r);
    }

    private void emit(String kind, String id) {
        final String js = "window.__athenaeumTts&&window.__athenaeumTts('" + kind + "','" + id + "')";
        main.post(() -> webView.evaluateJavascript(js, null));
    }

    /** JSON array of {name, lang, local} for the installed voices. */
    @JavascriptInterface
    public String voices() {
        JSONArray arr = new JSONArray();
        try {
            if (ready && tts != null) {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) {
                    for (Voice v : vs) {
                        if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("lang", v.getLocale().toLanguageTag());
                        o.put("local", !v.isNetworkConnectionRequired());
                        arr.put(o);
                    }
                }
            }
        } catch (Exception e) { /* return what we have */ }
        return arr.toString();
    }

    @JavascriptInterface
    public void speak(final String id, final String text, final String lang, final String voiceName,
                      final double rate, final double pitch, final double volume) {
        main.post(() -> runWhenReady(() -> doSpeak(id, text, lang, voiceName, rate, pitch, volume)));
    }

    @JavascriptInterface
    public void cancel() {
        main.post(() -> { if (tts != null && ready) tts.stop(); });
    }

    private void doSpeak(String id, String text, String lang, String voiceName,
                         double rate, double pitch, double volume) {
        if (failed || tts == null) { emit("error", id); return; }
        try {
            if (text == null || text.trim().isEmpty()) { emit("end", id); return; }
            Voice chosen = null;
            if (voiceName != null && !voiceName.isEmpty()) {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) if (voiceName.equals(v.getName())) { chosen = v; break; }
            }
            if (chosen != null) tts.setVoice(chosen);
            else if (lang != null && !lang.isEmpty()) tts.setLanguage(Locale.forLanguageTag(lang));
            tts.setSpeechRate((float) clamp(rate, 0.1, 4.0));
            tts.setPitch((float) clamp(pitch, 0.1, 2.0));

            List<String> chunks = split(text, 3000);
            int n = chunks.size();
            for (int i = 0; i < n; i++) {
                Bundle p = new Bundle();
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (float) clamp(volume, 0.0, 1.0));
                int r = tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, p, id + "|" + i + "|" + n);
                if (r != TextToSpeech.SUCCESS) { emit("error", id); return; }
            }
        } catch (Exception e) {
            emit("error", id);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // The engine refuses very long inputs, so long text is queued in sentence-sized pieces.
    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : text.split("(?<=[.!?\u0964\\n])\\s+")) {
            while (p.length() > max) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                out.add(p.substring(0, max));
                p = p.substring(max);
            }
            if (cur.length() > 0 && cur.length() + p.length() + 1 > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(p);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(text);
        return out;
    }

    public void shutdown() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
