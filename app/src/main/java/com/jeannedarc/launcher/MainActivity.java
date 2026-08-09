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
                    if (found == null) continue; // nothing startable in this package
                    seen.add(pkg);

                    String name;
                    try { name = pm.getApplicationLabel(ai).toString(); }
                    catch (Exception e) { name = pkg; }
                    android.graphics.drawable.Drawable icon;
                    try { icon = pm.getApplicationIcon(ai); }
                    catch (Exception e) { continue; }

                    addApp(result, pkg + "/" + found.getClassName(), name, icon);
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
