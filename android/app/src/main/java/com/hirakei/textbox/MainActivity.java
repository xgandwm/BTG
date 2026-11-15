package com.hirakei.textbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 100;   // WebView 原生 file chooser（网页兜底用）
    private static final int REQ_APP_PICK = 101;       // 自建选文件通道（绕开 WebView 回传兼容性问题）

    private WebView web;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String pickJs = null;                      // 自建通道回传时要调用的 JS 函数名

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        // WebView 自身 setPadding 不可靠（内容常不缩进），包进容器、padding 加在容器上
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // targetSdk 35 在 Android 15 强制 edge-to-edge：容器加系统栏避让内边距，
        // 否则页面顶部导航会和手机状态栏（消息/时间栏）重叠
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.navigationBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);      // localStorage：主题记忆需要
        st.setAllowFileAccess(true);
        st.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://")) return false;   // 本地资源自己加载
                try {   // 外部链接交给系统浏览器
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent i = params.createIntent();
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);   // 确保 WebView 能读取所选 content:// 文件
                    startActivityForResult(i, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_APP_PICK) {
            final String jsFunc = pickJs;
            pickJs = null;
            if (jsFunc == null) return;
            ArrayList<Uri> uris = new ArrayList<>();
            if (data != null) {
                ClipData cd = data.getClipData();           // 多选
                if (cd != null) {
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (data.getData() != null) {        // 单选
                    uris.add(data.getData());
                }
            }
            if (uris.isEmpty()) { evaluate(jsFunc + "([])"); return; }
            final ArrayList<Uri> list = uris;
            new Thread(new Runnable() {                     // 读文件放后台，避免卡 UI
                @Override
                public void run() {
                    StringBuilder arr = new StringBuilder("[");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) arr.append(",");
                        arr.append("\"").append(uriToDataUrl(list.get(i))).append("\"");
                    }
                    arr.append("]");
                    final String payload = jsFunc + "(" + arr + ")";
                    web.post(new Runnable() {
                        @Override
                        public void run() { web.evaluateJavascript(payload, null); }
                    });
                }
            }).start();
            return;
        }
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            } else {
                toast("选择结果丢失，请重试");   // Activity 在选择器期间被回收时 callback 会失效
            }
            fileChooserCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** content:// → dataURL（魔数推断 MIME，选字体的系统类型常为空） */
    private String uriToDataUrl(Uri uri) {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int b1 = is.read(), b2 = is.read();
            String mime = getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty()) {
                if (b1 == 0x89 && b2 == 0x50) mime = "image/png";
                else if (b1 == 0xFF && b2 == 0xD8) mime = "image/jpeg";
                else if (b1 == 0x47 && b2 == 0x49) mime = "image/gif";
                else if (b1 == 0x00 && b2 == 0x01) mime = "font/ttf";
                else if (b1 == 0x77 && b2 == 0x4F) mime = "font/woff";
                else mime = "application/octet-stream";
            }
            bos.write(b1);
            if (b2 >= 0) bos.write(b2);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return "data:" + mime + ";base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    private void evaluate(final String js) {
        web.post(new Runnable() {
            @Override
            public void run() { web.evaluateJavascript(js, null); }
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** 暴露给页面 JS：window.AndroidBridge.savePng(base64, filename) */
    private class Bridge {
        @JavascriptInterface
        public String ver() { return "2"; }   // 通道版本，页面据此确认装的是新包

        /** 自建选文件通道：系统选择器 → 读文件转 dataURL → 回调页面 JS（多选） */
        @JavascriptInterface
        public void pick(final String mime, final String jsFunc) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pickJs = jsFunc;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_APP_PICK);
                    } catch (Exception e) {
                        toast("无法打开文件选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void savePng(String base64, String name) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/TextBox");

                Uri uri = getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("无法创建图片文件");

                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("无法打开输出流");
                os.write(bytes);
                os.close();
                toast("已保存到相册 Pictures/TextBox");
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
