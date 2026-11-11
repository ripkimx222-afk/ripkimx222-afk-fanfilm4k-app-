package app.fanfilm4k;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.mlm.adblock.AdblockEngine;
import org.mlm.adblock.BlockDecision;
import org.mlm.adblock.CosmeticResources;
import org.mlm.adblock.RequestTypeMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String START_URL = "https://v16.fanfilm4k.media/";
    private static final String HOME_DOMAIN = "fanfilm4k.media";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final String[] FILTER_URLS = {
            "https://easylist-downloads.adblockplus.org/ruadlist+easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt"
    };

    private FrameLayout root;
    private WebView web;
    private AdblockEngine adblock;
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean filtersReady = false;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);

        try {
            adblock = AdblockEngine.create();
            loadAdblockResources();
            downloadFilters();
        } catch (Throwable ignored) {
            // Ad blocking is fail-open: an engine error must not stop the site.
            adblock = null;
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                AdblockEngine engine = adblock;
                if (engine == null || !filtersReady) return null;

                try {
                    Uri uri = request.getUrl();
                    String source = view.getUrl();
                    if (source == null) source = START_URL;
                    String accept = request.getRequestHeaders().get("Accept");
                    String type;
                    if (request.isForMainFrame()) {
                        type = "document";
                    } else {
                        type = RequestTypeMapper.INSTANCE.from(uri, accept);
                    }

                    BlockDecision decision = engine.checkNetworkRequest(
                            uri.toString(), source, type
                    );
                    if (decision.getMatched() && !decision.getException()) {
                        return emptyResponse();
                    }
                } catch (Throwable ignored) {
                    // Fail open for unsupported requests or filter-engine errors.
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) && isHomeHost(uri.getHost())) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectCosmeticRules(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String html = "<body style='background:#000;color:#ccc;font:18px sans-serif;"
                            + "text-align:center;padding-top:30vh'>Нет соединения с сайтом.<br><br>"
                            + "<a style='color:#8ab4f8' href='" + START_URL + "'>Повторить</a></body>";
                    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
                applyImmersiveMode(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private void loadAdblockResources() {
        try (InputStream input = getAssets().open("adblock/resources.json")) {
            byte[] bytes = new byte[input.available()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            adblock.loadResources(new String(bytes, 0, offset, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Network blocking still works if optional redirect/scriptlet resources fail.
        }
    }

    private void downloadFilters() {
        filterExecutor.execute(() -> {
            StringBuilder combined = new StringBuilder();
            try {
                for (String filterUrl : FILTER_URLS) {
                    String rules = downloadText(filterUrl);
                    if (rules == null || rules.isEmpty()) return;
                    combined.append(rules).append('\n');
                }
                AdblockEngine engine = adblock;
                if (engine != null) {
                    filtersReady = engine.loadFilterList(combined.toString());
                }
            } catch (Exception ignored) {
                filtersReady = false;
            }
        });
    }

    private String downloadText(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "FanFilm4K-Android/1.0");
        try (InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private void injectCosmeticRules(WebView view, String pageUrl) {
        AdblockEngine engine = adblock;
        if (engine == null || !filtersReady) return;
        try {
            CosmeticResources cosmetics = engine.cosmeticResources(pageUrl);
            if (cosmetics == null) return;
            String css = cosmetics.css();
            String js = cosmetics.getJs();
            String script = "(function(){try{"
                    + "var css=" + org.json.JSONObject.quote(css) + ";"
                    + "if(css){var s=document.createElement('style');s.textContent=css;"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + "var extra=" + org.json.JSONObject.quote(js) + ";"
                    + "if(extra){(0,eval)(extra);}"
                    + "}catch(e){}})();";
            view.evaluateJavascript(script, null);
        } catch (Throwable ignored) {
            // Cosmetic rules are optional; never interfere with playback.
        }
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse(
                "text/plain", "UTF-8",
                new ByteArrayInputStream(new byte[0])
        );
    }

    private static boolean isHomeHost(String host) {
        return host != null && (host.equals(HOME_DOMAIN) || host.endsWith("." + HOME_DOMAIN));
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // No handler for this scheme; drop the navigation.
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
        web.setVisibility(View.VISIBLE);
        applyImmersiveMode(false);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveMode(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        filterExecutor.shutdownNow();
        if (adblock != null) adblock.close();
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
