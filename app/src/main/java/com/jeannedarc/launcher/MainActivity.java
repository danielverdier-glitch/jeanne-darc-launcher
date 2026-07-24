package com.jeannedarc.launcher;

import android.app.Activity;
import android.app.AlertDialog;
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
         * Launch an app by package name.
         * If not installed, opens Play Store on that app's page.
         */
        @JavascriptInterface
        public void launchApp(String packageName) {
            try {
                Intent intent = getPackageManager()
                    .getLaunchIntentForPackage(packageName);
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
         * Returns JSON array of installed apps with name, packageName, and icon.
         * Icons are returned as base64 PNG data URIs.
         */
        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
                JSONArray result = new JSONArray();

                for (ResolveInfo info : apps) {
                    try {
                        String pkg = info.activityInfo.packageName;
                        // Skip ourselves
                        if (pkg.equals(getPackageName())) continue;

                        String name = info.loadLabel(pm).toString();

                        // Get icon as base64
                        android.graphics.drawable.Drawable icon = info.loadIcon(pm);
                        android.graphics.Bitmap bitmap = drawableToBitmap(icon);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, baos);
                        String iconB64 = "data:image/png;base64," +
                            android.util.Base64.encodeToString(baos.toByteArray(),
                                android.util.Base64.NO_WRAP);

                        JSONObject obj = new JSONObject();
                        obj.put("name", name);
                        obj.put("pkg", pkg);
                        obj.put("iconUrl", iconB64);
                        result.put(obj);
                    } catch (Exception ignored) {}
                }

                return result.toString();
            } catch (Exception e) {
                return "[]";
            }
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
