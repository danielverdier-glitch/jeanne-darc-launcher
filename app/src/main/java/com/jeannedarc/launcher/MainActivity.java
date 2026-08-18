package com.jeannedarc.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class MainActivity extends Activity {

    private WebView webView;
    /** Ranked radio candidates for the "buscar la radio" dialog, best first. */
    private java.util.List<String[]> radioCandidates = null;
    private static final String PREFS_NAME = "JeanneDArcPrefs";
    /** Launch key of the radio once the user confirms one, so it can be pinned. */
    private static final String KEY_RADIO = "confirmedRadio";
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] REQUIRED_PERMISSIONS = {
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        // Ungranted on API <= 29 is why the first report failed to save; above
        // that the system ignores it and MediaStore covers the write instead.
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissionsIfNeeded();

        // Fullscreen landscape
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        // WebView settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // JS Bridge
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        // WebView clients
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Let the WebView handle file:// URLs
                if (url.startsWith("file://")) return false;
                // Block everything else
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // Grant geolocation permission automatically
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        // Load the launcher
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestPermissionsIfNeeded() {
        boolean allGranted = true;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED
                        && !shouldShowRequestPermissionRationale(permissions[i])) {
                    // User ticked "Never ask again" — show dialog to go to Settings
                    new AlertDialog.Builder(this)
                        .setTitle("Permiso de ubicación necesario")
                        .setMessage("Esta app necesita acceso al GPS. Activalo en Configuración → Permisos.")
                        .setPositiveButton("Ir a Configuración", (d, w) -> {
                            Intent intent = new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())
                            );
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                    return;
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Swallow back button — this is the launcher
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // ── Android Bridge ────────────────────────────────────
    private class AndroidBridge {

        /**
         * Launch an app. Accepts either a plain package name (resolved via
         * getLaunchIntentForPackage, same as before) or a "pkg/className"
         * launch key as returned by getInstalledApps() for apps that were
         * only found via a non-standard entry point (see comment there) --
         * those are started with an explicit component so we don't have to
         * re-resolve them, since re-resolving is exactly what fails for
         * apps built into the stereo firmware (e.g. its Radio app) that
         * don't declare a normal CATEGORY_LAUNCHER activity.
         * If nothing can be launched, opens Play Store on that app's page.
         */
        @JavascriptInterface
        public void launchApp(String launchKey) {
            if (launchKey.startsWith("unlaunchable:")) {
                String rawPkg = launchKey.substring("unlaunchable:".length());
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("No se pudo abrir")
                    .setMessage("Esta app no declara ninguna pantalla iniciable:\n\n" + rawPkg
                        + "\n\nMandale este nombre a Claude para revisar cómo abrirla.")
                    .setPositiveButton("OK", null)
                    .show());
                return;
            }
            int slash = launchKey.indexOf('/');
            String packageName = slash >= 0 ? launchKey.substring(0, slash) : launchKey;
            try {
                Intent intent;
                if (slash >= 0) {
                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClassName(packageName, launchKey.substring(slash + 1));
                } else {
                    intent = getPackageManager().getLaunchIntentForPackage(packageName);
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else {
                    // Not installed → Play Store
                    openPlayStore(packageName);
                }
            } catch (Exception e) {
                openPlayStore(packageName);
            }
        }

        /**
         * Open Play Store on a specific app page.
         */
        @JavascriptInterface
        public void openPlayStore(String packageName) {
            try {
                Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName)
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                // Play Store not available — open browser fallback
                try {
                    Intent intent = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }

        /**
         * Open the native Android launcher / app drawer.
         */
        @JavascriptInterface
        public void openLauncher() {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Use chooser so user can pick another launcher if available
                startActivity(intent);
            } catch (Exception ignored) {}
        }

        /**
         * Save a string value to SharedPreferences.
         */
        @JavascriptInterface
        public void savePrefs(String key, String value) {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putString(key, value);
            editor.apply();
        }

        /**
         * Load a string value from SharedPreferences.
         * Returns empty string if not found.
         */
        @JavascriptInterface
        public String loadPrefs(String key) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return prefs.getString(key, "");
        }

        /**
         * Returns JSON array of installed apps with name, a launch key
         * ("pkg" or "pkg/className"), and icon. Icons are base64 PNG data URIs.
         *
         * Ordinary apps (Play Store installs, etc.) declare a normal
         * CATEGORY_LAUNCHER activity and are found by the first query below.
         * Aftermarket car-stereo firmware often bundles apps (the built-in
         * Radio, AV-IN, Bluetooth screen, etc.) that are real, resolvable
         * activities but do NOT declare CATEGORY_LAUNCHER -- they're meant to
         * be started only by the OEM's own home launcher through whatever
         * internal mechanism it uses. A plain "list launchable apps" query
         * skips them entirely, which is why the radio never showed up here.
         *
         * To catch those too, this also walks every installed application
         * (getInstalledApplications) and, for anything not already found,
         * tries progressively looser ways to find a startable activity in
         * that package: getLaunchIntentForPackage, then the leanback (TV)
         * launch intent, then a raw ACTION_MAIN query scoped to the package
         * with no category filter at all. Whatever activity is found that
         * way is encoded as an explicit "pkg/className" launch key, so
         * launchApp() can start that exact component directly instead of
         * re-resolving it (re-resolving is exactly what fails for these).
         */
        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                JSONArray result = new JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                String selfPkg = getPackageName();

                // Pin the radio first once findRadio() has confirmed one, so it
                // is not buried among the hundreds of firmware screens that made
                // it impossible to pick out by hand in the first place.
                String radioKey = loadPrefs(KEY_RADIO);
                if (radioKey != null && !radioKey.isEmpty() && seen.add(radioKey)) {
                    try {
                        int slash = radioKey.indexOf('/');
                        ComponentName cn = new ComponentName(
                            radioKey.substring(0, slash), radioKey.substring(slash + 1));
                        addApp(result, radioKey, "\ud83d\udcfb Radio",
                               pm.getActivityInfo(cn, 0).loadIcon(pm));
                    } catch (Exception ignored) {}
                }

                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo info : pm.queryIntentActivities(mainIntent, 0)) {
                    String pkg = info.activityInfo.packageName;
                    if (pkg.equals(selfPkg) || !seen.add(pkg)) continue;
                    addApp(result, pkg, info.loadLabel(pm).toString(), info.loadIcon(pm));
                }

                // Second pass: apps with no CATEGORY_LAUNCHER activity, including
                // system/firmware-bundled ones like the stereo's own Radio app.
                for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    String pkg = ai.packageName;
                    if (pkg.equals(selfPkg) || seen.contains(pkg)) continue;

                    ComponentName found = null;
                    Intent li = pm.getLaunchIntentForPackage(pkg);
                    if (li != null) found = li.getComponent();
                    if (found == null) {
                        Intent lb = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (lb != null) found = lb.getComponent();
                    }
                    if (found == null) {
                        Intent probe = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
                        List<ResolveInfo> matches = pm.queryIntentActivities(probe, 0);
                        if (!matches.isEmpty()) {
                            android.content.pm.ActivityInfo act = matches.get(0).activityInfo;
                            found = new ComponentName(act.packageName, act.name);
                        }
                    }
                    if (found == null) {
                        // Last resort: some OEM "internal" screens (a bundled
                        // Radio app is a common example) declare an Activity
                        // in the manifest with NO intent-filter at all -- they
                        // rely on something else in the firmware to start them
                        // via an explicit Intent, so no implicit-intent query
                        // above can ever find them. Read the manifest directly
                        // and just take the first declared activity, if any.
                        try {
                            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                                PackageManager.GET_ACTIVITIES);
                            if (pi.activities != null && pi.activities.length > 0) {
                                android.content.pm.ActivityInfo act = pi.activities[0];
                                found = new ComponentName(act.packageName, act.name);
                            }
                        } catch (Exception ignored) {}
                    }

                    String name;
                    try { name = pm.getApplicationLabel(ai).toString(); }
                    catch (Exception e) { name = pkg; }

                    if (found == null) {
                        // Truly nothing to launch (no activities declared at all --
                        // likely a service/receiver-only package, not a real app
                        // screen). Still list it, greyed out, tapping it reports the
                        // raw package name so we can figure out the right way to
                        // start it from outside if it does turn out to be relevant.
                        seen.add(pkg);
                        android.graphics.drawable.Drawable icon2;
                        try { icon2 = pm.getApplicationIcon(ai); }
                        catch (Exception e) { continue; }
                        addApp(result, "unlaunchable:" + pkg, name, icon2);
                        continue;
                    }
                    seen.add(pkg);

                    android.graphics.drawable.Drawable icon;
                    try { icon = pm.getApplicationIcon(ai); }
                    catch (Exception e) { continue; }

                    addApp(result, pkg + "/" + found.getClassName(), name, icon);
                }

                // Third pass: activity-level scan for the stereo's built-in Radio.
                // On this head unit (MTK / NXOS, product Y6) the radio is NOT a
                // package of its own -- Settings shows "Ajustes radio" as a section
                // of the firmware's own settings, and the stock launcher opens the
                // radio as an internal screen. So the second pass above, which only
                // ever exposes ONE activity per package (the first launchable one),
                // can never reach it. Here we walk every declared activity of every
                // package and surface the ones whose class name looks radio-related,
                // each as its own explicit "pkg/ClassName" entry.
                for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    String pkg = ai.packageName;
                    if (pkg.equals(selfPkg)) continue;

                    android.content.pm.ActivityInfo[] acts;
                    try {
                        android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                            PackageManager.GET_ACTIVITIES);
                        acts = pi.activities;
                    } catch (Exception e) { continue; }
                    if (acts == null) continue;

                    for (android.content.pm.ActivityInfo act : acts) {
                        String cls = act.name;
                        String lower = cls.toLowerCase();
                        // Match on the class name only. Matching the package would
                        // re-list dozens of unrelated screens from any package that
                        // happens to contain "fm" (e.g. "...confirm...").
                        // This is a Chinese head unit, so cover the Chinese terms
                        // too: \u6536\u97f3\u673a (radio set), \u7535\u53f0 (station),
                        // \u8c03\u9891 (FM/tuning), \u5e7f\u64ad (broadcast). Class names are
                        // usually ASCII, but the firmware's own screens are the
                        // exact case where that assumption tends to break.
                        boolean looksRadio = lower.contains("radio")
                                          || lower.contains("tuner")
                                          || lower.contains("fmradio")
                                          || lower.endsWith(".fm")
                                          || lower.contains(".fm.")
                                          || cls.contains("\u6536\u97f3\u673a")
                                          || cls.contains("\u7535\u53f0")
                                          || cls.contains("\u8c03\u9891")
                                          || cls.contains("\u5e7f\u64ad");
                        if (!looksRadio) {
                            // Also check the activity's own visible label -- an OEM
                            // screen class named e.g. ".ActivityMain" can still carry
                            // a "\u6536\u97f3\u673a" label, and that label is what the stock
                            // launcher shows on its Radio card.
                            String lbl;
                            try { lbl = act.loadLabel(pm).toString(); }
                            catch (Exception e) { continue; }
                            String ll = lbl.toLowerCase();
                            looksRadio = ll.contains("radio") || ll.contains("tuner")
                                      || lbl.contains("\u6536\u97f3\u673a") || lbl.contains("\u7535\u53f0")
                                      || lbl.contains("\u8c03\u9891") || lbl.contains("\u5e7f\u64ad");
                        }
                        if (!looksRadio) continue;

                        String key = pkg + "/" + cls;
                        if (!seen.add(key)) continue;

                        android.graphics.drawable.Drawable icon;
                        try { icon = pm.getApplicationIcon(ai); }
                        catch (Exception e) { continue; }

                        // Show the short class name so several candidates from the
                        // same package stay distinguishable in the picker.
                        String shortCls = cls.substring(cls.lastIndexOf('.') + 1);
                        addApp(result, key, "\u25B6 " + shortCls, icon);
                    }
                }

                return result.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /**
         * Rank every activity on the device by how likely it is to be the
         * stereo's built-in Radio screen, then show the best one in a dialog
         * the user can accept or skip.
         *
         * Listing the candidates in the picker turned out to be unusable --
         * a firmware like this declares hundreds of internal screens, and the
         * radio is not distinguishable by eye among them. So the ranking runs
         * here and the user only ever judges one candidate at a time, in the
         * only way that actually settles it: by opening it and seeing whether
         * the radio comes up.
         */
        @JavascriptInterface
        public void findRadio() {
            PackageManager pm = getPackageManager();
            java.util.List<String[]> found = new java.util.ArrayList<>();

            java.util.Set<String> homePkgs = new java.util.HashSet<>();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(homeIntent, 0)) {
                homePkgs.add(ri.activityInfo.packageName);
            }

            String selfPkg = getPackageName();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String pkg = ai.packageName;
                if (pkg.equals(selfPkg)) continue;

                android.content.pm.ActivityInfo[] acts;
                try {
                    acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities;
                } catch (Exception e) { continue; }
                if (acts == null) continue;

                for (android.content.pm.ActivityInfo act : acts) {
                    String label;
                    try { label = act.loadLabel(pm).toString(); }
                    catch (Exception e) { label = act.name; }

                    int score = scoreRadio(pkg, act.name, label,
                                           act.exported, homePkgs.contains(pkg));
                    if (score <= 0) continue;
                    found.add(new String[]{
                        String.valueOf(score), pkg + "/" + act.name, label });
                }
            }

            java.util.Collections.sort(found, (a, b) ->
                Integer.parseInt(b[0]) - Integer.parseInt(a[0]));
            // The tail of the ranking is noise the user would tap through for
            // nothing; if the radio is not in the top of it, the ranking is
            // wrong and needs fixing rather than more taps.
            if (found.size() > 40) found = found.subList(0, 40);
            radioCandidates = found;

            runOnUiThread(() -> showRadioCandidate(0));
        }

        /**
         * How much a given activity looks like the radio. Negative or zero
         * means "not a candidate".
         */
        private int scoreRadio(String pkg, String cls, String label,
                               boolean exported, boolean isHome) {
            String lc = cls.toLowerCase(), lp = pkg.toLowerCase(), ll = label.toLowerCase();

            // Google and AOSP packages hold nothing of ours, and they are an
            // active source of false positives: com.android.settings declares
            // RadioInfo, where "radio" means the cellular modem.
            if (lp.startsWith("com.google.") || lp.startsWith("com.android.")
                || lp.equals("android")) return 0;

            int score = 0;
            String[] strong = {"radio", "tuner", "fmradio",
                               "\u6536\u97f3\u673a", "\u7535\u53f0", "\u8c03\u9891", "\u5e7f\u64ad"};
            for (String k : strong) {
                if (lc.contains(k)) score += 60;
                if (ll.contains(k)) score += 50;
            }
            if (lc.matches(".*[.$_]fm([.$_].*|)") || ll.matches("(.*\\W|)fm(\\W.*|)")) score += 30;

            // The radio turned out to carry no radio word at all in either its
            // class name or its label, so keyword matching alone cannot find
            // it. Score the media-ish screens of the firmware too and let the
            // user walk the ranking -- on a unit like this the radio lives in
            // whatever app owns audio sources (media centre, AV, sound).
            String[] mediaish = {"media", "music", "audio", "player", "sound",
                                 "\u97f3\u4e50", "\u5a92\u4f53", "av"};
            for (String k : mediaish) { if (lc.contains(k) || ll.contains(k)) { score += 12; break; } }

            String[] oem = {"com.nx", ".car", "auto", "mtk", "mediatek",
                            "hct", "syu", "hzbhd", "wits", "zhonghong", "autochips"};
            for (String k : oem) { if (lp.contains(k)) { score += 15; break; } }
            if (isHome) score += 30; // the stock launcher draws the Radio card

            // The CAN-bus screens address the *vehicle's own* head unit (the
            // first candidate found this way was "\u539f\u8f66FM", the original car's
            // FM, a Nissan bus test screen). There is no original car here.
            if (lp.contains("canbus") || lc.contains("carinfo")) score -= 60;

            String[] weak = {"setting", "config", "\u8bbe\u7f6e",
                             "test", "factory", "engineer", "debug"};
            for (String k : weak) {
                if (lc.contains(k) || ll.contains(k)) { score -= 40; break; }
            }

            // Not a hard disqualifier any more: whether a component can really
            // be started is settled by starting it, and this pass now has to
            // reach screens that carry no obvious marking at all.
            score += exported ? 10 : -5;

            return score >= 20 ? score : 0;
        }

        /**
         * Capture which component was recently in the foreground, so the stock
         * home's Radio button can be identified and copied.
         *
         * Android records every move-to-foreground with a timestamp. If the
         * user opens the radio and comes back, that record still names the
         * exact activity the Radio button started -- which is precisely the
         * thing no amount of static inspection could reveal, because the radio
         * is an internal screen with no launcher entry. Reading it needs the
         * "usage access" special grant, so the first tap sends the user to
         * enable it and returns.
         */
        @JavascriptInterface
        public void captureRadio() {
            if (!hasUsageAccess()) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Falta un permiso (una sola vez)")
                        .setMessage("Para leer qué pantalla abre el botón Radio, activá "
                            + "el acceso de uso para JEANNE D'ARC.\n\n"
                            + "1) Te llevo a esa pantalla\n2) Buscá JEANNE D'ARC y activalo\n"
                            + "3) Volvé y tocá otra vez \"Capturar la radio\"")
                        .setPositiveButton("Ir a activarlo", (d, w) -> {
                            try {
                                startActivity(new Intent(
                                    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                            } catch (Exception e) {
                                android.widget.Toast.makeText(MainActivity.this,
                                    "No encontré esa pantalla: " + e,
                                    android.widget.Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                });
                return;
            }

            new Thread(() -> {
                String body;
                try { body = buildCapture(); }
                catch (Exception e) { body = "ERROR en la captura: " + e; }
                String where;
                try { where = writeNamed("jeanne-darc-captura-radio.txt", body); }
                catch (Exception e) { where = null; }
                final String path = where;
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle(path != null ? "Captura lista" : "No se pudo guardar")
                    .setMessage(path != null
                        ? "Guardé la captura en:\n\n" + path
                          + "\n\nMandásela a Claude: adentro está el nombre de la "
                          + "pantalla que abriste."
                        : "No pude escribir el archivo.")
                    .setPositiveButton("OK", null)
                    .show());
            }).start();
        }

        private boolean hasUsageAccess() {
            try {
                android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
                int mode = ops.checkOpNoThrow("android:get_usage_stats",
                    android.os.Process.myUid(), getPackageName());
                return mode == android.app.AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) { return false; }
        }

        private String buildCapture() {
            StringBuilder b = new StringBuilder();
            b.append("== JEANNE D'ARC - captura de la radio ==\n");
            long now = System.currentTimeMillis();
            b.append("ahora=").append(now).append('\n');

            // The heart of it: every foreground/background transition in the
            // last 10 minutes, newest last. The activity on screen while the
            // radio played is named here.
            b.append("\n== EVENTOS DE PANTALLA (ultimos 10 min) ==\n");
            try {
                android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                android.app.usage.UsageEvents ev = usm.queryEvents(now - 10 * 60 * 1000, now);
                android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                int n = 0;
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    int t = e.getEventType();
                    String tn = t == 1 ? "AL_FRENTE" : t == 2 ? "al_fondo"
                              : t == 7 ? "interaccion" : "tipo" + t;
                    // Only the transitions that matter, to keep it readable.
                    if (t == 1 || t == 2) {
                        b.append(tn).append("  ").append(e.getPackageName())
                         .append(" / ").append(e.getClassName())
                         .append("  (+").append((e.getTimeStamp() - (now - 600000)) / 1000)
                         .append("s)\n");
                        n++;
                    }
                }
                if (n == 0) b.append("(sin eventos -- ¿se activó el acceso de uso?)\n");
            } catch (Exception ex) {
                b.append("ERROR leyendo eventos: ").append(ex).append('\n');
            }

            b.append("\n== PROCESOS EN PRIMER PLANO ==\n");
            try {
                android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps =
                    am.getRunningAppProcesses();
                if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo pi : ps) {
                    if (pi.importance <= 125) { // FOREGROUND(100)/VISIBLE(125)
                        b.append("imp=").append(pi.importance).append("  ").append(pi.processName);
                        if (pi.pkgList != null) for (String pk : pi.pkgList) b.append(' ').append(pk);
                        b.append('\n');
                    }
                }
            } catch (Exception ex) { b.append("ERROR: ").append(ex).append('\n'); }

            b.append("\n== SERVICIOS DEL FIRMWARE CORRIENDO ==\n");
            try {
                android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                for (android.app.ActivityManager.RunningServiceInfo si :
                        am.getRunningServices(500)) {
                    String pk = si.service.getPackageName();
                    if (pk.contains("yunovo") || pk.contains("car") || pk.contains("radio")
                            || pk.contains("mtk") || pk.contains("media")) {
                        b.append(si.service.getClassName())
                         .append(si.foreground ? "  [foreground]" : "").append('\n');
                    }
                }
            } catch (Exception ex) { b.append("ERROR: ").append(ex).append('\n'); }

            // Diff bait: the same settings tables as the baseline report, so a
            // source/band/frequency key the radio flipped shows up by comparison.
            dumpSettings(b, "System", android.provider.Settings.System.CONTENT_URI);
            dumpSettings(b, "Global", android.provider.Settings.Global.CONTENT_URI);
            return b.toString();
        }

        private String writeNamed(String name, String body) throws Exception {
            byte[] data = body.getBytes("UTF-8");
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            return writeAndIndex(new java.io.File(dir, name), data);
        }

                /**
         * Write a full report of what this firmware actually contains to the
         * device's Downloads folder, for offline analysis.
         *
         * Every heuristic tried so far guessed at the radio from the outside
         * and guessed wrong, because nothing about it is marked in a way an
         * outsider recognises. This dumps the raw material instead -- every
         * component of every non-Google package, the settings tables, and the
         * shared-user ids that explain what the stock launcher is allowed to
         * start that we are not -- so the answer can be read off the firmware
         * rather than inferred from its naming.
         */
        @JavascriptInterface
        public void dumpSystemInfo() {
            new Thread(() -> {
                String report;
                try { report = buildReport(); }
                catch (Exception e) { report = "ERROR generando el informe: " + e; }
                final String body = report;

                String where;
                try { where = writeReport(body); }
                catch (Exception e) { where = null; }

                final String path = where;
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle(path != null ? "Informe generado" : "No se pudo guardar")
                    .setMessage(path != null
                        ? "Generé el informe en:\n\n" + path
                          + "\n\nBuscalo con la app Archivos (carpeta Descargas) y "
                          + "mandáselo a Claude."
                        : "No pude escribir el archivo. Probá darle permiso de "
                          + "almacenamiento a la app en Configuración.")
                    .setPositiveButton("OK", null)
                    .show());
            }).start();
        }

        /**
         * Write the file and tell the media scanner about it.
         *
         * Without the scan the report is invisible: a direct filesystem write
         * leaves the media index untouched, and the stock file manager lists
         * from that index, so the folder came up empty even though the file
         * was sitting in it.
         */
        private String writeAndIndex(java.io.File out, byte[] data) throws Exception {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(data);
            fos.close();
            try {
                android.media.MediaScannerConnection.scanFile(MainActivity.this,
                    new String[]{out.getAbsolutePath()}, new String[]{"text/plain"}, null);
            } catch (Exception ignored) {}
            return out.getAbsolutePath();
        }

        /**
         * Save the report, returning the human-readable path it landed in.
         *
         * Tries several destinations because which ones are writable varies by
         * Android version and by how the OEM configured storage, and the first
         * attempt at this failed on the unit with nothing to explain why. The
         * app-private external directory is last and effectively cannot fail:
         * it needs no permission on any version.
         */
        private String writeReport(String body) throws Exception {
            String name = "jeanne-darc-informe.txt";
            byte[] data = body.getBytes("UTF-8");
            StringBuilder problems = new StringBuilder();

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                           android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("insert devolvió null");
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.close();
                    return "Descargas/" + name;
                } catch (Exception e) { problems.append("MediaStore: ").append(e).append('\n'); }
            }

            String[] dirs = {android.os.Environment.DIRECTORY_DOWNLOADS,
                             android.os.Environment.DIRECTORY_DOCUMENTS};
            for (String d : dirs) {
                try {
                    java.io.File dir =
                        android.os.Environment.getExternalStoragePublicDirectory(d);
                    dir.mkdirs();
                    return writeAndIndex(new java.io.File(dir, name), data);
                } catch (Exception e) { problems.append(d).append(": ").append(e).append('\n'); }
            }

            try {
                // Root of the shared storage: the one place the stock file
                // manager reliably shows, unlike Android/data below.
                java.io.File dir = android.os.Environment.getExternalStorageDirectory();
                return writeAndIndex(new java.io.File(dir, name), data);
            } catch (Exception e) { problems.append("sdcard: ").append(e).append('\n'); }

            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    java.io.File out = new java.io.File(dir, name);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    fos.write(data);
                    fos.close();
                    return out.getAbsolutePath();
                }
            } catch (Exception e) { problems.append("externalFiles: ").append(e).append('\n'); }

            java.io.File out = new java.io.File(getFilesDir(), name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(data);
            fos.close();
            return out.getAbsolutePath() + "\n\n(los otros destinos fallaron:\n"
                 + problems + ")";
        }

        private String buildReport() {
            PackageManager pm = getPackageManager();
            StringBuilder b = new StringBuilder();

            b.append("== JEANNE D'ARC - informe del sistema ==\n");
            b.append("android.sdk=").append(android.os.Build.VERSION.SDK_INT)
             .append(" release=").append(android.os.Build.VERSION.RELEASE).append('\n');
            String[][] props = {
                {"manufacturer", android.os.Build.MANUFACTURER}, {"brand", android.os.Build.BRAND},
                {"model", android.os.Build.MODEL}, {"device", android.os.Build.DEVICE},
                {"product", android.os.Build.PRODUCT}, {"board", android.os.Build.BOARD},
                {"hardware", android.os.Build.HARDWARE}, {"display", android.os.Build.DISPLAY},
                {"fingerprint", android.os.Build.FINGERPRINT},
            };
            for (String[] kv : props) b.append(kv[0]).append('=').append(kv[1]).append('\n');

            // Which launcher draws the Radio card -- its package gets full
            // detail below even if it would otherwise be filtered out.
            java.util.Set<String> homePkgs = new java.util.HashSet<>();
            Intent hi = new Intent(Intent.ACTION_MAIN);
            hi.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(hi, 0)) {
                homePkgs.add(ri.activityInfo.packageName);
            }
            b.append("\n== LAUNCHERS (CATEGORY_HOME) ==\n");
            for (String h : homePkgs) b.append(h).append('\n');

            // The settings tables are where a firmware records things like the
            // current band or the last tuned frequency, which names the owning
            // component far more reliably than any class name does.
            dumpSettings(b, "System", android.provider.Settings.System.CONTENT_URI);
            dumpSettings(b, "Secure", android.provider.Settings.Secure.CONTENT_URI);
            dumpSettings(b, "Global", android.provider.Settings.Global.CONTENT_URI);

            // The firmware keeps its own configuration in dot-directories at
            // the root of shared storage, and there is a "forbid.apps" file
            // there that by its name lists what the stock launcher refuses to
            // show -- which is exactly the shape of the thing we are missing.
            b.append("\n== ARCHIVOS DE CONFIGURACION DEL FIRMWARE ==\n");
            java.io.File root = android.os.Environment.getExternalStorageDirectory();
            String[] interesting = {"forbid.apps", ".nxos", ".custom", "custom_media",
                                    "lyra", "carnet", "log", "ErrorReport"};
            for (String nm : interesting) {
                dumpPath(b, new java.io.File(root, nm), 0);
            }

            // The home is a launcher theme (skin.path=/custom/etc/nxos/theme/...)
            // and its Radio card came down to cn.yunovo.nxos.player. The card's
            // intent is defined in the theme's own config files, so read the
            // theme tree and the firmware's config partitions directly.
            b.append("\n== TEMA DEL HOME Y CONFIG DEL FIRMWARE (rutas absolutas) ==\n");
            String[] abs = {"/custom/etc/nxos/theme/20363/res", "/custom/etc/nxos/theme",
                            "/custom/etc/nxos", "/custom/etc", "/system/etc/nxos",
                            "/data/nxos", "/custom/app"};
            for (String a : abs) {
                dumpPath(b, new java.io.File(a), 0);
            }

            // There is no radio activity anywhere on this unit: the stock home
            // is a launcher theme and the radio is a view inside it, driven by
            // the MCU. So the way in, if there is one, is not a component but
            // whatever the firmware's own providers expose -- these are its
            // configuration and car-service databases, and they are exported.
            b.append("\n== PERMISOS DEL FIRMWARE QUE PEDIMOS ==\n");
            String[] wanted = {"cn.yunovo.config.permission.YUNOVO_READ",
                               "cn.yunovo.config.permission.YUNOVO_WRITE",
                               "com.yunovo.permission.READ_BLUETOOTH_STATE"};
            for (String w : wanted) {
                b.append(w).append(" -> ")
                 .append(checkSelfPermission(w) == PackageManager.PERMISSION_GRANTED
                         ? "CONCEDIDO" : "denegado").append('\n');
            }

            b.append("\n== CONFIG PROVIDER (NxSettingsProvider) A FONDO ==\n");
            b.append("(YUNOVO_READ/WRITE concedidos, asi que aca deberia salir algo)\n");
            dumpConfigDeep(b);

            b.append("\n== OTROS PROVIDERS DEL FIRMWARE ==\n");
            String[] auths = {"cn.yunovo.nxos.carservice.provider",
                              "cn.yunovo.nxos.platformservice",
                              "cn.yunovo.nxos.data.collector",
                              "cn.yunovo.nxos.usercenter.server"};
            String[] paths = {"", "settings", "config", "system", "car", "radio", "fm",
                              "source", "media", "audio", "key", "value", "data", "info"};
            for (String auth : auths) {
                for (String path : paths) {
                    dumpProvider(b, "content://" + auth + (path.isEmpty() ? "" : "/" + path), true);
                }
            }

            b.append("\n== PAQUETES ==\n");
            b.append("(Google/AOSP se listan sin detalle; el resto va completo)\n");
            java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_UNINSTALLED_PACKAGES);
            java.util.Collections.sort(apps, (x, y) -> x.packageName.compareTo(y.packageName));

            for (ApplicationInfo ai : apps) {
                String pkg = ai.packageName;
                String label;
                try { label = pm.getApplicationLabel(ai).toString(); }
                catch (Exception e) { label = "?"; }
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                boolean terse = (pkg.startsWith("com.google.") || pkg.startsWith("com.android.")
                                 || pkg.equals("android")) && !homePkgs.contains(pkg);

                b.append("\n--- ").append(pkg).append(" | \"").append(label).append('"')
                 .append(sys ? " [system]" : "").append(ai.enabled ? "" : " [disabled]")
                 .append(homePkgs.contains(pkg) ? " [HOME]" : "").append('\n');

                android.content.pm.PackageInfo pi;
                try {
                    // MATCH_DISABLED_COMPONENTS matters: a component the OEM
                    // ships disabled and enables on demand is invisible without
                    // it, and that is exactly how an optional tuner would ship.
                    pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES
                        | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                        | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_PERMISSIONS
                        | PackageManager.MATCH_DISABLED_COMPONENTS);
                } catch (Exception e) { b.append("  (no legible)\n"); continue; }

                if (pi.sharedUserId != null) {
                    // Components of a shared uid can start each other's private
                    // screens; that is the likeliest reason the stock launcher
                    // reaches something we cannot.
                    b.append("  sharedUserId=").append(pi.sharedUserId).append('\n');
                }
                if (terse) {
                    b.append("  (resumen) activities=")
                     .append(pi.activities == null ? 0 : pi.activities.length)
                     .append(" services=").append(pi.services == null ? 0 : pi.services.length)
                     .append('\n');
                    continue;
                }

                if (pi.activities != null) for (android.content.pm.ActivityInfo a : pi.activities) {
                    String al;
                    try { al = a.loadLabel(pm).toString(); } catch (Exception e) { al = ""; }
                    b.append("  ACT ").append(a.name)
                     .append(a.exported ? " exported" : " private")
                     .append(a.enabled ? "" : " disabled")
                     .append(a.permission != null ? " perm=" + a.permission : "")
                     .append(al.isEmpty() ? "" : " \"" + al + "\"").append('\n');
                }
                if (pi.services != null) for (android.content.pm.ServiceInfo sv : pi.services) {
                    b.append("  SVC ").append(sv.name)
                     .append(sv.exported ? " exported" : " private")
                     .append(sv.permission != null ? " perm=" + sv.permission : "").append('\n');
                }
                if (pi.receivers != null) for (android.content.pm.ActivityInfo r : pi.receivers) {
                    b.append("  RCV ").append(r.name)
                     .append(r.exported ? " exported" : " private").append('\n');
                }
                // Protection level decides whether a permission the firmware
                // guards its data with is one we can simply ask for, or one
                // only the OEM's signing key can obtain.
                if (pi.permissions != null) {
                    for (android.content.pm.PermissionInfo pe : pi.permissions) {
                        int base = pe.protectionLevel & 0xf;
                        String lvl = base == 0 ? "normal" : base == 1 ? "dangerous"
                                   : base == 2 ? "signature" : base == 3 ? "signatureOrSystem"
                                   : "nivel:" + base;
                        b.append("  PERM ").append(pe.name).append(' ').append(lvl)
                         .append(" (raw=0x").append(Integer.toHexString(pe.protectionLevel))
                         .append(")\n");
                    }
                }
                if (pi.providers != null) for (android.content.pm.ProviderInfo pr : pi.providers) {
                    b.append("  PRV ").append(pr.name)
                     .append(" auth=").append(pr.authority)
                     .append(pr.exported ? " exported" : " private").append('\n');
                }
            }
            return b.toString();
        }

        /**
         * List a path, and print the contents of small text files under it.
         * Depth-limited and size-capped: this is meant to surface a handful of
         * config files, not to mirror the storage into the report.
         */
        private void dumpPath(StringBuilder b, java.io.File f, int depth) {
            if (f == null || !f.exists() || depth > 3) return;
            StringBuilder pad = new StringBuilder();
            for (int i = 0; i < depth; i++) pad.append("  ");

            if (f.isDirectory()) {
                b.append(pad).append("[dir] ").append(f.getName()).append('\n');
                java.io.File[] kids = f.listFiles();
                if (kids == null) return;
                java.util.Arrays.sort(kids, (x, y) -> x.getName().compareTo(y.getName()));
                int n = 0;
                for (java.io.File k : kids) {
                    if (++n > 60) { b.append(pad).append("  ...\n"); return; }
                    dumpPath(b, k, depth + 1);
                }
                return;
            }

            b.append(pad).append(f.getName()).append(" (").append(f.length()).append(" B)\n");
            String lower = f.getName().toLowerCase();
            boolean textish = lower.endsWith(".txt") || lower.endsWith(".ini")
                || lower.endsWith(".conf") || lower.endsWith(".cfg") || lower.endsWith(".xml")
                || lower.endsWith(".prop") || lower.endsWith(".json") || lower.endsWith(".apps")
                || lower.endsWith(".list") || !lower.contains(".");
            if (!textish || f.length() > 65536) return;
            try {
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int read = in.read(buf);
                in.close();
                if (read > 0) {
                    b.append(pad).append("  >>> ").append(new String(buf, 0, read, "UTF-8"))
                     .append("\n  <<<\n");
                }
            } catch (Exception e) {
                b.append(pad).append("  (no legible: ").append(e).append(")\n");
            }
        }

        /** Query one provider path and print whatever comes back. */
        /**
         * Probe cn.yunovo.config every way that a name/value config provider on
         * these units is known to answer. We hold YUNOVO_READ/WRITE now, so the
         * question is no longer permission but shape: which URI paths it serves,
         * and whether it exposes its store through query() or through call().
         * Everything is reported, empty results included, so the schema can be
         * read off the output instead of guessed at again.
         */
        private void dumpConfigDeep(StringBuilder b) {
            String auth = "cn.yunovo.config";

            // Far wider path list than before, including the ones an audio
            // source / tuner setting tends to hide behind on NXOS.
            String[] paths = {"", "settings", "config", "configs", "setting", "system",
                "global", "secure", "car", "carinfo", "radio", "fm", "tuner", "band",
                "freq", "frequency", "source", "audiosource", "media", "audio", "sound",
                "amp", "dsp", "mcu", "canbus", "can", "key", "keys", "value", "values",
                "kv", "data", "info", "state", "status", "all", "nxpal", "pal", "zen",
                "factory", "password", "pwd", "engineer", "app", "apps", "table"};
            for (String path : paths) {
                dumpProvider(b, "content://" + auth + (path.isEmpty() ? "" : "/" + path), true);
            }

            // Some of these providers key a single settings table by row, like
            // Android's own Settings: content://auth/settings/<name>. Try to
            // read back the audio source / radio keys directly.
            String[] keys = {"source", "audio_source", "current_source", "last_source",
                "radio", "fm", "band", "freq", "frequency", "media_source"};
            for (String k : keys) {
                dumpProvider(b, "content://" + auth + "/settings/" + k, true);
            }

            // The other doorway: ContentResolver.call(). NX config providers
            // commonly expose their store through a method rather than a table.
            // Try the usual method names with and without a key argument.
            String[] methods = {"getAll", "get_all", "getall", "list", "dump", "query",
                "get", "getString", "getInt", "getConfig", "get_config", "readAll",
                "getKeys", "keys", "select"};
            String[] callArgs = {null, "", "source", "radio", "fm", "audio_source"};
            Uri authUri = Uri.parse("content://" + auth);
            for (String m : methods) {
                for (String arg : callArgs) {
                    try {
                        android.os.Bundle res = getContentResolver().call(authUri, m, arg, null);
                        if (res == null) continue;
                        b.append("-- call(\"").append(m).append("\", arg=")
                         .append(arg == null ? "null" : "\"" + arg + "\"").append(") -> ");
                        java.util.Set<String> ks = res.keySet();
                        if (ks.isEmpty()) { b.append("(bundle vacio)\n"); continue; }
                        b.append('\n');
                        for (String bk : ks) {
                            Object v = res.get(bk);
                            b.append("     ").append(bk).append('=').append(v).append('\n');
                        }
                    } catch (Exception e) {
                        // Only surface a method that exists but rejected the call.
                        String msg = String.valueOf(e);
                        if (!msg.contains("NullPointerException")
                                && !msg.toLowerCase().contains("unknown")
                                && !msg.contains("IllegalArgument")) {
                            b.append("-- call(\"").append(m).append("\") ERROR: ")
                             .append(msg).append('\n');
                        }
                    }
                }
            }
        }

        private void dumpProvider(StringBuilder b, String uriStr) { dumpProvider(b, uriStr, false); }

        private void dumpProvider(StringBuilder b, String uriStr, boolean verbose) {
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(Uri.parse(uriStr), null, null, null, null);
                if (c == null) { if (verbose) b.append("-- ").append(uriStr).append(" -> null\n"); return; }
                b.append("\n-- ").append(uriStr)
                 .append(" (").append(c.getCount()).append(" filas)\n");
                String[] cols = c.getColumnNames();
                b.append("   cols: ");
                for (String col : cols) b.append(col).append(' ');
                b.append('\n');
                int rows = 0;
                while (c.moveToNext() && rows++ < 400) {
                    b.append("   ");
                    for (int i = 0; i < cols.length; i++) {
                        String v;
                        try { v = c.getString(i); } catch (Exception e) { v = "?"; }
                        b.append(cols[i]).append('=').append(v).append(" | ");
                    }
                    b.append('\n');
                }
                if (rows >= 400) b.append("   ...\n");
            } catch (Exception e) {
                // Only worth reporting when the path exists but refuses us --
                // a permission denial names something real.
                String msg = String.valueOf(e);
                if (msg.contains("ermission")) {
                    b.append("\n-- ").append(uriStr).append(" DENEGADO: ").append(msg).append('\n');
                }
            } finally {
                if (c != null) c.close();
            }
        }

        private void dumpSettings(StringBuilder b, String title, Uri uri) {
            b.append("\n== SETTINGS.").append(title.toUpperCase()).append(" ==\n");
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[]{"name", "value"},
                                               null, null, "name");
                if (c == null) { b.append("(no legible)\n"); return; }
                while (c.moveToNext()) {
                    b.append(c.getString(0)).append('=').append(c.getString(1)).append('\n');
                }
            } catch (Exception e) {
                b.append("(no legible: ").append(e).append(")\n");
            } finally {
                if (c != null) c.close();
            }
        }

        /**
         * Open the firmware media app, where the radio lives as a source.
         *
         * The foreground capture showed the home's Radio button bringing
         * cn.yunovo.nxos.player.MainActivity to the front, so the radio is a
         * source inside that app rather than an app of its own. It is exported,
         * so we can start it. Plain start lands on whatever source was last
         * used; the extra variants below are guesses at how the theme's Radio
         * card asks for FM specifically, offered one at a time until one lands
         * on the radio -- a small space now that the app is known.
         */
        @JavascriptInterface
        public void openRadioSource() {
            final String pkg = "cn.yunovo.nxos.player";
            final String cls = "cn.yunovo.nxos.player.MainActivity";
            // {label, extraKey, extraType, extraValue, action}. A null key means
            // no extra; a null action means ACTION_MAIN.
            final String[][] tries = {
                {"Multimedia tal cual (última fuente)", null, null, null, null},
                {"extra source=fm (texto)", "source", "s", "fm", null},
                {"extra source=radio (texto)", "source", "s", "radio", null},
                {"extra source=1 (número)", "source", "i", "1", null},
                {"extra source=2 (número)", "source", "i", "2", null},
                {"extra source=0 (número)", "source", "i", "0", null},
                {"extra mode=fm", "mode", "s", "fm", null},
                {"extra type=radio", "type", "s", "radio", null},
                {"extra openType=fm", "openType", "s", "fm", null},
                {"extra media_type=radio", "media_type", "s", "radio", null},
                {"acción ...player.FM", null, null, null, "cn.yunovo.nxos.player.FM"},
                {"acción ...player.RADIO", null, null, null, "cn.yunovo.nxos.player.RADIO"},
                {"acción ...action.FM", null, null, null, "cn.yunovo.nxos.player.action.FM"},
                {"acción ...action.RADIO", null, null, null, "cn.yunovo.nxos.player.action.RADIO"},
            };
            String[] names = new String[tries.length];
            for (int i = 0; i < tries.length; i++) names[i] = tries[i][0];

            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("Abrir la radio (probá una por una)")
                .setItems(names, (d, which) -> {
                    String[] t = tries[which];
                    try {
                        Intent in = new Intent(t[4] == null ? Intent.ACTION_MAIN : t[4]);
                        in.setClassName(pkg, cls);
                        in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (t[1] != null) {
                            if ("i".equals(t[2])) in.putExtra(t[1], Integer.parseInt(t[3]));
                            else in.putExtra(t[1], t[3]);
                        }
                        startActivity(in);
                        // Remember the last one tried, so a working combo can be
                        // pinned to a button afterwards without guessing again.
                        savePrefs("lastRadioTry", String.valueOf(which));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(MainActivity.this,
                            "Falló: " + e, android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show());
        }

        /**
         * Offer the firmware's factory screens directly.
         *
         * These are exported, so they start without the factory-menu password
         * -- the password gates the menu that leads to them, not the screens
         * themselves. They are also unreachable through the app picker, which
         * only ever surfaces one activity per package.
         *
         * The CAN model selection is the one that matters for a unit wired
         * into something other than the car it shipped for.
         */
        @JavascriptInterface
        public void openFactoryScreens() {
            final String[][] screens = {
                {"Modelo / protocolo CAN",
                 "cn.yunovo.car.settings/cn.yunovo.car.settings.canbus.CarModelSelectActivity"},
                {"Ajustes del auto (lista)",
                 "cn.yunovo.car.settings/cn.yunovo.car.settings.SettingsListActivity"},
                {"Shell",
                 "cn.yunovo.car.settings/cn.yunovo.car.settings.ShellActivity"},
                {"Ruta de navegación",
                 "cn.yunovo.car.settings/cn.yunovo.car.settings.navipath.NaviPathSettings"},
                {"FactoryTest (principal)",
                 "cn.yunovo.nxos.factorytest/cn.yunovo.nxos.factorytest.view.MainActivity"},
                {"FactoryTest (índice)",
                 "cn.yunovo.nxos.factorytest/cn.yunovo.nxos.factorytest.view.IndexActivity"},
                {"FactoryTest (pruebas)",
                 "cn.yunovo.nxos.factorytest/cn.yunovo.nxos.factorytest.view.TestActivity"},
                {"EngineerMode (MTK)",
                 "com.mediatek.engineermode/com.mediatek.engineermode.EngineerMode"},
                {"Pantalla de inicio de fábrica",
                 "cn.yunovo.nxos.themestore/cn.yunovo.nxos.themestore.ui.activity.HomeActivity"},
            };
            String[] names = new String[screens.length];
            for (int i = 0; i < screens.length; i++) names[i] = screens[i][0];

            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("Pantallas de fábrica")
                .setItems(names, (d, which) -> {
                    if (!tryLaunchComponent(screens[which][1])) {
                        android.widget.Toast.makeText(MainActivity.this,
                            "El sistema rechazó esa pantalla",
                            android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show());
        }

        /** Start "pkg/Class" explicitly. False if the system refused it. */
        private boolean tryLaunchComponent(String key) {
            try {
                int slash = key.indexOf('/');
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(key.substring(0, slash), key.substring(slash + 1));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Show candidate i, with the option to open it or skip to the next. */
        private void showRadioCandidate(int i) {
            if (radioCandidates == null || radioCandidates.isEmpty()) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("No encontré la radio")
                    .setMessage("Ninguna pantalla del sistema parece ser la radio.\n\n"
                        + "Contale esto a Claude para probar otro camino.")
                    .setPositiveButton("OK", null)
                    .show();
                return;
            }
            if (i >= radioCandidates.size()) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Se acabaron los candidatos")
                    .setMessage("Probé las " + radioCandidates.size()
                        + " pantallas que parecían la radio y ninguna era.\n\n"
                        + "Contale esto a Claude para probar otro camino.")
                    .setPositiveButton("OK", null)
                    .show();
                return;
            }

            String[] c = radioCandidates.get(i);
            final String key = c[1], label = c[2];

            new AlertDialog.Builder(MainActivity.this)
                .setTitle("\u00bfEsta es la radio?")
                .setMessage("Candidato " + (i + 1) + " de " + radioCandidates.size()
                    + "\n\n" + label + "\n" + key
                    + "\n\nSi al abrirla aparece la radio, qued\u00f3 guardada y la vas a "
                    + "encontrar como \u00ab\ud83d\udcfb Radio\u00bb arriba de todo en la lista de apps.")
                .setPositiveButton("Abrir esta", (d, w) -> {
                    // A component that refuses to start tells us nothing and
                    // costs the user a tap, so move straight on to the next one
                    // instead of leaving them on a dialog that did nothing.
                    if (tryLaunchComponent(key)) {
                        savePrefs(KEY_RADIO, key);
                    } else {
                        android.widget.Toast.makeText(MainActivity.this,
                            "Esa no abre, paso a la siguiente",
                            android.widget.Toast.LENGTH_SHORT).show();
                        showRadioCandidate(i + 1);
                    }
                })
                .setNeutralButton("Probar la siguiente", (d, w) -> showRadioCandidate(i + 1))
                .setNegativeButton("Cancelar", null)
                .show();
        }

        private void addApp(JSONArray result, String launchKey,
                             String name, android.graphics.drawable.Drawable icon) {
            try {
                android.graphics.Bitmap bitmap = drawableToBitmap(icon);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, baos);
                String iconB64 = "data:image/png;base64," +
                    android.util.Base64.encodeToString(baos.toByteArray(),
                        android.util.Base64.NO_WRAP);

                JSONObject obj = new JSONObject();
                obj.put("name", name);
                obj.put("pkg", launchKey);
                obj.put("iconUrl", iconB64);
                result.put(obj);
            } catch (Exception ignored) {}
        }

        private android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable drawable) {
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            }
            int w = Math.max(drawable.getIntrinsicWidth(), 48);
            int h = Math.max(drawable.getIntrinsicHeight(), 48);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        }
    }
}
