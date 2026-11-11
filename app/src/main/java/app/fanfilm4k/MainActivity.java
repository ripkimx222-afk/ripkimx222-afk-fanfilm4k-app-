
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
import io.github.edsuns.adfilter.AdFilter;
import io.github.edsuns.adfilter.Filter;
import io.github.edsuns.adfilter.FilterViewModel;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    // FILL: if the site moves to another domain, change both constants.
    // START_URL is the first page; HOME_DOMAIN is the registrable domain kept inside the app.
    // Main-frame navigations to any other host are handed to the system browser.
    private static final String START_URL = "https://v16.fanfilm4k.media/";
    private static final String HOME_DOMAIN = "fanfilm4k.media";

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private FrameLayout root;
    private WebView web;
    private AdFilter adFilter;
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

        // Ad blocking engine (EasyList + RU AdList + privacy protection).
        adFilter = AdFilter.Companion.get(this);
        adFilter.setupWebView(web);
        installAdFiltersIfNeeded();

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
                try {
                    return adFilter.shouldIntercept(view, request).getResourceResponse();
                } catch (Exception ignored) {
                    // Fail open: a filter error must never break the site or video playback.
                    return null;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                try {
                    adFilter.performScript(view, url);
                } catch (Exception ignored) {
                    // Cosmetic/scriptlet filtering is optional; keep the page usable.
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) && isHomeHost(uri.getHost())) {
                    return false;
                }
                openExternal(uri);
                return true;
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

    private void installAdFiltersIfNeeded() {
        FilterViewModel filters = adFilter.getViewModel();

        // The engine keeps subscriptions locally and updates them through WorkManager.
        // These are standard public filter subscriptions; no site/player domains are blocked wholesale.
        if (!adFilter.getHasInstallation()) {
            Filter ruAndInternational = filters.addFilter(
                    "RU AdList + EasyList",
                    "https://easylist-downloads.adblockplus.org/ruadlist+easylist.txt"
            );
            Filter privacy = filters.addFilter(
                    "EasyPrivacy",
                    "https://easylist.to/easylist/easyprivacy.txt"
            );

            filters.getIsEnabled().setValue(true);
            filters.download(ruAndInternational.getId());
            filters.download(privacy.getId());
        }
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
        if (customView == null) {
            return;
        }
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
        if (customView != null) {
            hideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
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
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
