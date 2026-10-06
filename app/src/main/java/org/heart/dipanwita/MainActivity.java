package org.heart.dipanwita;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;

/** WebView shell: shows the local chat UI served by the Python bot. */
public class MainActivity extends Activity {
    private static final int PORT = 8765;
    private static final int REQ_PICK = 7731;

    private WebView wv;
    private String token = "";
    private volatile String purpose = "file";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#121214"));
        getWindow().setNavigationBarColor(Color.parseColor("#121214"));

        wv = new WebView(this);
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        WebView.setWebContentsDebuggingEnabled(false);
        wv.setBackgroundColor(Color.parseColor("#121214"));
        setContentView(wv);
        wv.loadData("<body style='background:#121214;color:#999;font-family:sans-serif;text-align:center;padding-top:40vh'>"
                + getString(R.string.loading) + "</body>", "text/html; charset=utf-8", "UTF-8");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        try {
            token = ensureToken();
            copyIndexHtml();
        } catch (Exception e) {
            e.printStackTrace();
        }

        startForegroundService(new Intent(this, BotService.class));
        waitAndLoad();
        ui.postDelayed(pickPoller, 500);
    }

    private String ensureToken() throws IOException {
        File tp = new File(getFilesDir(), "ui_token.txt");
        if (!tp.exists()) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            FileOutputStream o = new FileOutputStream(tp);
            o.write(sb.toString().getBytes("UTF-8"));
            o.close();
        }
        return new String(readAll(new FileInputStream(tp), 1000), "UTF-8").trim();
    }

    private void copyIndexHtml() throws IOException {
        File dir = new File(getFilesDir(), "web");
        dir.mkdirs();
        InputStream in = getAssets().open("index.html");
        OutputStream out = new FileOutputStream(new File(dir, "index.html"));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (bo.size() < max && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    // ---- wait until Python's local server is up, then open the UI
    private void waitAndLoad() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                for (int i = 0; i < 160 && !destroyed; i++) {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                        ok = true;
                        s.close();
                        break;
                    } catch (IOException e) {
                        try { s.close(); } catch (IOException ignored) { }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    }
                }
                final boolean fok = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (fok) {
                            wv.loadUrl("http://127.0.0.1:" + PORT + "/?t=" + token);
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() { askBattery(); }
                            }, 3000);
                        } else {
                            wv.loadData("<body style='background:#121214;color:#ccc;font-family:sans-serif;text-align:center;padding:40vh 20px 0'>"
                                    + getString(R.string.start_failed) + "</body>", "text/html; charset=utf-8", "UTF-8");
                        }
                    }
                });
            }
        }).start();
    }

    private void askBattery() {
        try {
            File flag = new File(getFilesDir(), "battery_asked");
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (flag.exists() || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            flag.createNewFile();
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) { }
    }

    // ---- the UI asks for a file via /api/pick -> Python writes pick_request -> we open the picker
    private final Runnable pickPoller = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                File q = new File(getFilesDir(), "quit_request");
                if (q.exists()) {
                    q.delete();
                    quitApp();
                    return;
                }
                File f = new File(getFilesDir(), "pick_request");
                if (f.exists()) {
                    String p = new String(readAll(new FileInputStream(f), 100), "UTF-8").trim();
                    f.delete();
                    purpose = p.isEmpty() ? "file" : p;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, getString(R.string.pick_title)), REQ_PICK);
                }
            } catch (Exception ignored) { }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() { saveUpload(uri); }
            }).start();
        }
    }

    private static byte[] jpeg(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private void saveUpload(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            File agent = new File(getFilesDir(), "agent");
            agent.mkdirs();

            if (purpose.equals("avatar")) {              // avatar: centered 256px square JPEG
                purpose = "file";
                Bitmap bmp = BitmapFactory.decodeStream(cr.openInputStream(uri));
                if (bmp == null) return;
                int w = bmp.getWidth(), h = bmp.getHeight(), s = Math.min(w, h);
                Bitmap sq = Bitmap.createBitmap(bmp, (w - s) / 2, (h - s) / 2, s, s);
                Bitmap sc = Bitmap.createScaledBitmap(sq, 256, 256, true);
                File tmp = new File(agent, "avatar.jpg.tmp");
                FileOutputStream o = new FileOutputStream(tmp);
                o.write(jpeg(sc, 85));
                o.close();
                tmp.renameTo(new File(agent, "avatar.jpg"));
                return;
            }

            String name = "";
            Cursor c = cr.query(uri, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (k >= 0) {
                        String v = c.getString(k);
                        if (v != null) name = v;
                    }
                }
                c.close();
            }
            File dir = new File(agent, "uploads");
            dir.mkdirs();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot).toLowerCase() : "";
            byte[] data;
            String type = cr.getType(uri);
            if (type != null && type.startsWith("image/")) {   // images: shrink to 1280px JPEG
                Bitmap bmp = BitmapFactory.decodeStream(cr.openInputStream(uri));
                if (bmp == null) return;
                int w = bmp.getWidth(), h = bmp.getHeight();
                double sc = Math.min(1.0, 1280.0 / Math.max(w, h));
                if (sc < 1) bmp = Bitmap.createScaledBitmap(bmp, (int) (w * sc), (int) (h * sc), true);
                data = jpeg(bmp, 80);
                int d2 = name.lastIndexOf('.');
                name = (d2 >= 0 ? name.substring(0, d2) : name) + ".jpg";
                ext = ".jpg";
            } else {
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                if (pfd == null) return;
                data = readAll(new FileInputStream(pfd.getFileDescriptor()), 8000001);
                pfd.close();
            }
            String safeExt = ext.matches("\\.\\w{1,6}") ? ext : "";
            File path = new File(dir, System.currentTimeMillis() + safeExt);
            FileOutputStream fo = new FileOutputStream(path);
            fo.write(data);
            fo.close();

            JSONObject body = new JSONObject();
            body.put("name", name);
            body.put("path", path.getAbsolutePath());
            HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/api/upload").openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setDoOutput(true);
            con.setRequestProperty("X-T", token);
            con.setRequestProperty("Content-Type", "application/json");
            OutputStream os = con.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            readAll(con.getInputStream(), 100000);
            con.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // সেটিংস → "অ্যাপ বন্ধ করো": সার্ভিস ও Python সহ পুরো অ্যাপ বন্ধ
    private void quitApp() {
        try {
            Intent i = new Intent(this, BotService.class);
            i.setAction(BotService.ACTION_STOP);
            startService(i);
        } catch (Exception e) {
            e.printStackTrace();
        }
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (wv != null) { wv.destroy(); wv = null; }
        super.onDestroy();
    }
}
