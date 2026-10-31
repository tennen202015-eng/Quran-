package com.duwaini.mushaf;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String HOST = "app.local";
    private static final int REQ_LOC = 2, REQ_NOTIFY = 3;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingOpen = null;

    // audio queue
    private MediaPlayer mp;
    private JSONArray qCodes, qIdx;
    private String qRec = "";
    private int qPos = 0, repeat = 1, repLeft = 0;
    private boolean playingNow = false;
    private AudioManager am;
    private AudioFocusRequest focus;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            try {
                if (mp != null && mp.isPlaying() && mp.getDuration() > 0)
                    emit("N_audio({t:'prog',v:" + ((double) mp.getCurrentPosition() / mp.getDuration()) + "})");
            } catch (Exception ignored) { }
            ui.postDelayed(this, 500);
        }
    };

    // downloads
    private final ExecutorService dl = Executors.newSingleThreadExecutor();

    // compass
    private SensorManager sm;
    private SensorEventListener sl;
    private long lastHead = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Alarms.channels(this);
        setBarColors(Color.parseColor("#0E4D40"), false);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E4D40"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    WebResourceResponse r = new WebResourceResponse(mime(path), "UTF-8", in);
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    r.setResponseHeaders(h);
                    return r;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingOpen != null) {
                    final String o = pendingOpen;
                    pendingOpen = null;
                    ui.postDelayed(() -> emit("window.N_open&&N_open('" + o + "')"), 1600);
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        pendingOpen = getIntent() != null ? getIntent().getStringExtra("open") : null;
        web.loadUrl("https://" + HOST + "/index.html");
        ui.post(progress);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String o = intent.getStringExtra("open");
        if (o != null && !o.isEmpty()) emit("window.N_open&&N_open('" + o.replace("'", "") + "')");
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void emit(String js) { ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); }); }

    private void setBarColors(int color, boolean lightIcons) {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (lightIcons) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        d.setSystemUiVisibility(f);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'", v -> {
            if (!"\"1\"".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        compassOff();
        ui.removeCallbacks(progress);
        super.onDestroy();
    }

    // ---------------- audio ----------------
    private File audioFile(String rec, String code) {
        return new File(new File(getFilesDir(), "audio/" + rec.replaceAll("[^A-Za-z0-9_\\-]", "")), code + ".mp3");
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        playingNow = false;
    }

    private boolean takeFocus() {
        if (am == null) return true;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(ch -> {
                    if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pauseAudio();
                })
                .build();
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void startCurrent() {
        releasePlayer();
        try {
            String code = qCodes.getString(qPos);
            int idx = qIdx.getInt(qPos);
            repLeft = Math.max(0, repeat - 1);
            emit("N_audio({t:'ayah',i:" + idx + "})");
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            File f = audioFile(qRec, code);
            if (f.exists() && f.length() > 0) mp.setDataSource(f.getPath());
            else mp.setDataSource("https://everyayah.com/data/" + qRec + "/" + code + ".mp3");
            mp.setOnPreparedListener(p -> { p.start(); playingNow = true; emit("N_audio({t:'state',p:true})"); });
            mp.setOnCompletionListener(p -> {
                if (repLeft > 0) { repLeft--; p.seekTo(0); p.start(); return; }
                qPos++;
                if (qCodes != null && qPos < qCodes.length()) startCurrent();
                else { releasePlayer(); emit("N_audio({t:'done'})"); }
            });
            mp.setOnErrorListener((p, w, e) -> { releasePlayer(); emit("N_audio({t:'error'})"); return true; });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            emit("N_audio({t:'error'})");
        }
    }

    private void pauseAudio() {
        try { if (mp != null && mp.isPlaying()) { mp.pause(); playingNow = false; emit("N_audio({t:'state',p:false})"); } } catch (Exception ignored) { }
    }

    // ---------------- location ----------------
    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void doLocate() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { emit("N_loc({err:'off'})"); return; }
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60 * 1000L) { deliver(best); return; }
            String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (prov == null) { if (best != null) deliver(best); else emit("N_loc({err:'off'})"); return; }
            final Location fallback = best;
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(prov, null, getMainExecutor(), l -> {
                    if (l != null) deliver(l); else if (fallback != null) deliver(fallback); else emit("N_loc({err:'fail'})");
                });
            } else {
                lm.requestSingleUpdate(prov, new LocationListener() {
                    @Override public void onLocationChanged(Location l) { deliver(l); }
                    @Override public void onStatusChanged(String p, int s, Bundle b) { }
                    @Override public void onProviderEnabled(String p) { }
                    @Override public void onProviderDisabled(String p) { }
                }, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            emit("N_loc({err:'denied'})");
        }
    }

    private void deliver(Location l) {
        final double lat = l.getLatitude(), lng = l.getLongitude();
        new Thread(() -> {
            String name = "";
            try {
                List<Address> a = new Geocoder(this, new Locale("ar")).getFromLocation(lat, lng, 1);
                if (a != null && !a.isEmpty()) {
                    Address x = a.get(0);
                    String city = x.getLocality() != null ? x.getLocality() : x.getSubAdminArea() != null ? x.getSubAdminArea() : x.getAdminArea();
                    name = city == null ? "" : city + (x.getCountryName() != null ? "، " + x.getCountryName() : "");
                }
            } catch (Exception ignored) { }
            emit("N_loc({lat:" + lat + ",lng:" + lng + ",name:" + JSONObject.quote(name) + "})");
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_LOC) {
            if (hasLoc()) doLocate(); else emit("N_loc({err:'denied'})");
        }
    }

    // ---------------- compass ----------------
    private void compassOn() {
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) return;
        final float[] r = new float[9], o = new float[3];
        sl = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                long now = System.currentTimeMillis();
                if (now - lastHead < 80) return;
                lastHead = now;
                SensorManager.getRotationMatrixFromVector(r, e.values);
                SensorManager.getOrientation(r, o);
                double az = (Math.toDegrees(o[0]) + 360) % 360;
                emit("N_head(" + az + ")");
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        sm.registerListener(sl, rv, SensorManager.SENSOR_DELAY_UI);
    }

    private void compassOff() {
        if (sm != null && sl != null) sm.unregisterListener(sl);
        sl = null;
    }

    // ---------------- bridge ----------------
    private class Bridge {
        @JavascriptInterface public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("آية", text));
        }

        @JavascriptInterface public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            ui.post(() -> startActivity(Intent.createChooser(i, "مشاركة الآية")));
        }

        @JavascriptInterface public void keepAwake(boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void setBars(String color, boolean lightIcons) {
            ui.post(() -> { try { setBarColors(Color.parseColor(color), lightIcons); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public boolean vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return false;
            v.vibrate(VibrationEffect.createOneShot(Math.max(10, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            return true;
        }

        // audio
        @JavascriptInterface public void audioPlay(String json) {
            ui.post(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    qRec = o.getString("rec");
                    qCodes = o.getJSONArray("codes");
                    qIdx = o.getJSONArray("idx");
                    repeat = Math.max(1, o.optInt("repeat", 1));
                    qPos = 0;
                    takeFocus();
                    startCurrent();
                } catch (Exception e) { emit("N_audio({t:'error'})"); }
            });
        }

        @JavascriptInterface public void audioToggle() {
            ui.post(() -> {
                try {
                    if (mp == null) return;
                    if (mp.isPlaying()) pauseAudio();
                    else { takeFocus(); mp.start(); playingNow = true; emit("N_audio({t:'state',p:true})"); }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void audioStop() {
            ui.post(() -> { releasePlayer(); qCodes = null; if (am != null && focus != null) am.abandonAudioFocusRequest(focus); });
        }

        @JavascriptInterface public void audioRepeat(int n) {
            ui.post(() -> { repeat = Math.max(1, n); repLeft = Math.min(repLeft, repeat - 1); });
        }

        // downloads
        @JavascriptInterface public int dlCount(String rec, String codesJson) {
            int n = 0;
            try {
                JSONArray c = new JSONArray(codesJson);
                for (int k = 0; k < c.length(); k++) { File f = audioFile(rec, c.getString(k)); if (f.exists() && f.length() > 0) n++; }
            } catch (Exception ignored) { }
            return n;
        }

        @JavascriptInterface public void dlDelete(String rec, String codesJson) {
            try {
                JSONArray c = new JSONArray(codesJson);
                for (int k = 0; k < c.length(); k++) { File f = audioFile(rec, c.getString(k)); if (f.exists()) f.delete(); }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void dlStart(String rec, String surah, String codesJson) {
            dl.submit(() -> {
                int done = 0, total = 0;
                boolean err = false;
                try {
                    JSONArray c = new JSONArray(codesJson);
                    total = c.length();
                    for (int k = 0; k < total; k++) {
                        File f = audioFile(rec, c.getString(k));
                        if (!(f.exists() && f.length() > 0)) {
                            f.getParentFile().mkdirs();
                            File tmp = new File(f.getPath() + ".part");
                            HttpURLConnection con = (HttpURLConnection) new URL("https://everyayah.com/data/" + rec + "/" + c.getString(k) + ".mp3").openConnection();
                            con.setConnectTimeout(15000);
                            con.setReadTimeout(30000);
                            con.setInstanceFollowRedirects(true);
                            if (con.getResponseCode() != 200) throw new IOException("http " + con.getResponseCode());
                            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                                byte[] b = new byte[16384];
                                int r;
                                while ((r = in.read(b)) > 0) out.write(b, 0, r);
                            }
                            con.disconnect();
                            if (!tmp.renameTo(f)) throw new IOException("rename");
                        }
                        done++;
                        if (done % 3 == 0 || done == total) emit("N_dl({s:" + surah + ",done:" + done + ",total:" + total + "})");
                    }
                } catch (Exception e) { err = true; }
                if (err) emit("N_dl({s:" + surah + ",done:" + done + ",total:" + total + ",err:1})");
            });
        }

        // notifications
        @JavascriptInterface public void schedule(String json) { Alarms.schedule(MainActivity.this, json); }

        @JavascriptInterface public void setWird(String hhmm) { Alarms.setWird(MainActivity.this, hhmm); }

        @JavascriptInterface public void setDaily(int id, String hhmm, String title, String body, String open) {
            Alarms.setDaily(MainActivity.this, id, hhmm, title, body, open);
        }

        @JavascriptInterface public void dailyDone(String open, String date) {
            Alarms.prefs(MainActivity.this).edit().putString("done_" + open, date).apply();
        }

        @JavascriptInterface public void wirdDone(String date) { Alarms.prefs(MainActivity.this).edit().putString("wirdDone", date).apply(); }

        @JavascriptInterface public void askNotify() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                ui.post(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY));
        }

        // location & compass
        @JavascriptInterface public void locate() {
            ui.post(() -> {
                if (hasLoc()) doLocate();
                else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            });
        }

        @JavascriptInterface public void compass(boolean on) { ui.post(() -> { compassOff(); if (on) compassOn(); }); }

        // widget
        @JavascriptInterface public void widget(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Alarms.prefs(MainActivity.this).edit()
                        .putString("w_ayah", o.optString("ayah")).putString("w_ref", o.optString("ref")).putString("w_wird", o.optString("wird")).apply();
                MushafWidget.update(MainActivity.this);
            } catch (Exception ignored) { }
        }
    }
}
