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
    private static final String PREFS_NAME = "JeanneDArcPrefs";
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] REQUIRED_PERMISSIONS = {
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
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

                // Fourth pass: every activity of the stock home launcher(s).
                // The firmware launcher is what shows the "Radio" card, so whatever
                // component that card starts is either declared there or named in a
                // recognisable way alongside it. Bounded to home packages so this
                // stays a short, readable list rather than every screen on the unit.
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo home : pm.queryIntentActivities(homeIntent, 0)) {
                    String pkg = home.activityInfo.packageName;
                    if (pkg.equals(selfPkg)) continue;

                    android.content.pm.ActivityInfo[] acts;
                    try {
                        android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                            PackageManager.GET_ACTIVITIES);
                        acts = pi.activities;
                    } catch (Exception e) { continue; }
                    if (acts == null) continue;

                    for (android.content.pm.ActivityInfo act : acts) {
                        String key = pkg + "/" + act.name;
                        if (!seen.add(key)) continue;

                        android.graphics.drawable.Drawable icon;
                        try { icon = act.loadIcon(pm); }
                        catch (Exception e) { continue; }

                        String shortCls = act.name.substring(act.name.lastIndexOf('.') + 1);
                        addApp(result, key, "\u2699 " + shortCls, icon);
                    }
                }

                return result.toString();
            } catch (Exception e) {
                return "[]";
            }
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
