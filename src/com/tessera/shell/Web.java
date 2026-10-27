package com.tessera.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;

/** 统一配置 WebView：设计稿是 200×200 的逻辑屏，按屏宽整体缩放（480 宽时 1 设计像素 = 2.4 物理像素）。 */
final class Web {
    private Web() {}

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static WebView create(Context context, Bridge bridge, String page, boolean transparent) {
        WebView web = new WebView(context);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setBackgroundColor(transparent ? Color.TRANSPARENT : Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setWebViewClient(new WebViewClient() {
            // bundle.css 会去 Google Fonts 拉 Noto Sans SC；字体已经打包在 noto/ 里，直接拦掉，离线也一样
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && (host.endsWith("googleapis.com") || host.endsWith("gstatic.com"))) {
                    return new WebResourceResponse("text/css", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(bridge, "TesseraHost");
        web.loadUrl("file:///android_asset/" + page);
        return web;
    }

    /** 把原生事件转给页面里的 TS.emit(name, data)。 */
    static void emit(WebView web, String name, String json) {
        if (web == null) return;
        String js = "window.TS&&TS.emit(" + JSONObject.quote(name) + "," + (json == null ? "null" : json) + ")";
        web.evaluateJavascript(js, null);
    }
}
