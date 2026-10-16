package ir.scooljavanrood.madreseyar.teacher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.print.PrintManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.ScriptHandler;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import javax.crypto.Cipher;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;

/**
 * Test-only Android container for the EXISTING unified /app/ staff web portal.
 * This app does not copy, rewrite or alter server-side teacher permissions.
 * The web frontend remains authoritative; Android-native edge cases require device QA.
 */
public final class MainActivity extends FragmentActivity {
    private static final String HOME = "https://scooljavanrood.ir/app/";
    private static final String SITE = "scooljavanrood.ir";
    private static final int FILE_REQUEST = 7301;
    private static final String STAFF_ME = HOME + "api/staff/me";
    private static final String TOKEN_KEY = "madreseyar.staff.token.v14";
    private static final String SESSION_KEY = "madreseyar.session.v14";
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final Handler offerHandler = new Handler(Looper.getMainLooper());
    private final Runnable offerCheck = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && !isDestroyed()) {
                maybeOfferFirstBiometricSetup();
                offerHandler.postDelayed(this, 4000);
            }
        }
    };
    private BiometricVault vault;
    private FrameLayout root;
    private View lockOverlay;
    private Button fingerBtn;
    private boolean unlocked = false;
    private boolean biometricDialogOpen = false;
    private boolean browserLoaded = false;
    private boolean injectOnFirstPage = false;
    private boolean injectionWasApplied = false;
    private String pendingScript;
    private ScriptHandler sessionInjector;
    private String activeToken;

    private FrameLayout content;
    private WebView browser;
    private WebView popup;
    private ProgressBar progress;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private Button backBtn;
    private Button forwardBtn;

    private boolean isTrusted(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return host != null && (SITE.equalsIgnoreCase(host) || ("www." + SITE).equalsIgnoreCase(host));
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        vault = new BiometricVault(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.rgb(13, 67, 58));
        getWindow().setNavigationBarColor(Color.rgb(13, 67, 58));
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        shell.setBackgroundColor(Color.rgb(249, 248, 242));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),dp(4),dp(8),dp(4));
        bar.setBackgroundColor(Color.rgb(15, 83, 71));
        TextView title = new TextView(this);
        title.setText("مدرسه‌یار | معلم"); title.setTextColor(Color.WHITE); title.setTextSize(16);
        title.setSingleLine(true);
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
        fingerBtn = toolbarButton("👆");
        fingerBtn.setContentDescription("فعال‌سازی یا غیرفعال‌سازی ورود با اثر انگشت");
        fingerBtn.setOnClickListener(v -> toggleBiometric());
        bar.addView(fingerBtn);
        Button reload = toolbarButton("↻"); reload.setContentDescription("بارگذاری دوباره");
        reload.setOnClickListener(v -> browser.reload()); bar.addView(reload);
        Button print = toolbarButton("چاپ"); print.setContentDescription("چاپ صفحه فعلی");
        print.setOnClickListener(v -> printCurrentPage()); bar.addView(print);
        backBtn=toolbarButton("→"); backBtn.setContentDescription("بازگشت");
        backBtn.setOnClickListener(v -> back()); bar.addView(backBtn);
        forwardBtn=toolbarButton("←"); forwardBtn.setContentDescription("صفحه بعد");
        forwardBtn.setOnClickListener(v -> { if(browser.canGoForward()) browser.goForward(); }); bar.addView(forwardBtn);
        shell.addView(bar);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgress(0);
        shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        content=new FrameLayout(this);
        shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        root = new FrameLayout(this);
        root.addView(shell, new FrameLayout.LayoutParams(-1,-1));
        lockOverlay = makeLockOverlay();
        root.addView(lockOverlay, new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);

        browser = new WebView(this);
        prepareWebView(browser,false);
        content.addView(browser,new FrameLayout.LayoutParams(-1,-1));
        browser.setDownloadListener(this::download);
        // Never restore a previously visible WebView before biometric unlock.
        // State is intentionally not restored across activity recreation.
        if (vault.isEnabled()) {
            lockNow();
        } else {
            unlocked = true;
            lockOverlay.setVisibility(View.GONE);
            loadHome();
        }
        updateButtons();
        offerHandler.postDelayed(offerCheck, 4000);
    }
    private void loadHome() {
        browserLoaded = true;
        browser.loadUrl(HOME);
    }

    private View makeLockOverlay() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER);
        panel.setPadding(dp(26), dp(24), dp(26), dp(24));
        panel.setBackgroundColor(Color.rgb(13, 67, 58));
        TextView heading = new TextView(this);
        heading.setText("مدرسه‌یار معلم\nورود امن");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(25);
        heading.setGravity(android.view.Gravity.CENTER);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(118)));
        TextView description = new TextView(this);
        description.setText("برای مشاهده اطلاعات دانش‌آموزان، اثر انگشت ثبت‌شده در گوشی را تأیید کنید.");
        description.setTextColor(Color.WHITE);
        description.setTextSize(15);
        description.setGravity(android.view.Gravity.CENTER);
        panel.addView(description, new LinearLayout.LayoutParams(-1, dp(95)));
        Button retry = new Button(this);
        retry.setText("ورود با اثر انگشت");
        retry.setOnClickListener(v -> beginBiometricUnlock());
        panel.addView(retry, new LinearLayout.LayoutParams(-1, dp(56)));
        Button password = new Button(this);
        password.setText("ورود با نام کاربری و رمز");
        password.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("نشست ذخیره‌شده برای اثر انگشت پاک شود و با رمز وارد شوید؟")
                .setNegativeButton("انصراف", (d, w) -> {})
                .setPositiveButton("ادامه", (d, w) -> loginFresh()).show());
        panel.addView(password, new LinearLayout.LayoutParams(-1, dp(56)));
        return panel;
    }

    private void lockNow() {
        unlocked = false;
        if (lockOverlay != null) lockOverlay.setVisibility(View.VISIBLE);
    }

    private boolean canUseStrongBiometric() {
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS) return true;
        toast("اثر انگشت یا حسگر بیومتریک قوی تنظیم نشده است. ابتدا آن را در تنظیمات گوشی فعال کنید.");
        return false;
    }

    private void askBiometric(String title, Cipher cipher, Consumer<Cipher> onAuthenticated) {
        if (biometricDialogOpen) return;
        if (!canUseStrongBiometric()) return;
        biometricDialogOpen = true;
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        biometricDialogOpen = false;
                        Cipher verified = result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher();
                        if (verified == null) {
                            toast("اعتبارسنجی رمزنگاری اثر انگشت انجام نشد.");
                            return;
                        }
                        onAuthenticated.accept(verified);
                    }
                    @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                        biometricDialogOpen = false;
                        // A cancelled prompt never grants access.
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("مدرسه‌یار معلم")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("انصراف")
                .build();
        prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    private interface TeacherAuthResult {
        void finished(JSONObject user, boolean unauthorized);
    }

    /** Server role + token validation is authoritative; all network work stays off UI thread. */
    private void verifyTeacherSession(String token, TeacherAuthResult callback) {
        authExecutor.execute(() -> {
            HttpURLConnection conn = null;
            JSONObject verified = null;
            boolean unauthorized = false;
            try {
                conn = (HttpURLConnection) new URL(STAFF_ME).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Cache-Control", "no-store");
                int status = conn.getResponseCode();
                if (status == 200) {
                    try (InputStream stream = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[4096]; int n;
                        while ((n = stream.read(buf)) != -1 && out.size() < 65536) out.write(buf,0,n);
                        JSONObject json = new JSONObject(out.toString("UTF-8"));
                        JSONObject user = json.optJSONObject("user");
                        if (user != null && "teacher".equals(user.optString("role"))
                                && !user.optBoolean("mustChangePassword", false)) verified = user;
                        else unauthorized = true;
                    }
                } else if (status == 401 || status == 403 || status == 428) {
                    unauthorized = true;
                }
            } catch (Exception ignored) {
                // Offline/TLS failure: keep the encrypted token, never unlock offline.
            } finally {
                if (conn != null) conn.disconnect();
            }
            JSONObject result = verified;
            boolean invalid = unauthorized;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) callback.finished(result, invalid); });
        });
    }

    private void maybeOfferFirstBiometricSetup() {
        if (!unlocked || !browserLoaded || vault.isEnabled() || biometricDialogOpen) return;
        if (getSharedPreferences("msy_teacher_bio_onboarding", MODE_PRIVATE)
                .getBoolean("asked", false)) return;
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) return;
        browser.evaluateJavascript("(function(){try{var s=JSON.parse(sessionStorage.getItem('" + SESSION_KEY
                + "')||'null');return !!(s&&s.authUser&&s.authUser.role==='teacher'"
                + "&&sessionStorage.getItem('" + TOKEN_KEY + "'));}catch(e){return false;}})();", value -> {
            if (!"true".equals(value) || vault.isEnabled() || isFinishing() || isDestroyed()) return;
            getSharedPreferences("msy_teacher_bio_onboarding", MODE_PRIVATE)
                    .edit().putBoolean("asked", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("ورود سریع با اثر انگشت")
                    .setMessage("می‌خواهید از دفعات بعد با اثر انگشت وارد پنل معلم شوید؟ رمز مدرسه ذخیره نمی‌شود؛ نشست شما رمزگذاری و هر بار با سرور بررسی می‌شود.")
                    .setNegativeButton("فعلاً نه", (d, w) -> {})
                    .setPositiveButton("فعال‌سازی", (d, w) -> toggleBiometric())
                    .show();
        });
    }

    private void toggleBiometric() {
        if (!unlocked) return;
        if (vault.isEnabled()) {
            new AlertDialog.Builder(this).setMessage("ورود با اثر انگشت در این گوشی غیرفعال شود؟")
                    .setNegativeButton("انصراف", (d, w) -> {})
                    .setPositiveButton("غیرفعال‌سازی", (d, w) -> {
                        vault.clear();
                        activeToken = null;
                        toast("ورود با اثر انگشت غیرفعال شد.");
                    }).show();
            return;
        }
        if (!canUseStrongBiometric()) return;
        // Read only the existing staff session from the official HTTPS origin.
        // Do not save password or cache a fabricated native login.
        browser.evaluateJavascript("(function(){try{var s=JSON.parse(sessionStorage.getItem('" + SESSION_KEY
                + "')||'null');var t=sessionStorage.getItem('" + TOKEN_KEY
                + "');if(!t||!s||!s.authUser||s.authUser.role!=='teacher')return null;"
                + "return JSON.stringify({token:t,userId:s.userId});}catch(e){return null;}})();", answer -> {
            try {
                Object parsed = new JSONTokener(answer).nextValue();
                if (!(parsed instanceof String)) throw new Exception("no session");
                JSONObject obj = new JSONObject((String) parsed);
                String token = obj.optString("token", "");
                String userId = obj.optString("userId", "");
                if (token.isEmpty() || userId.isEmpty()) throw new Exception("not logged in");
                verifyTeacherSession(token, (teacher, invalid) -> {
                    if (teacher == null || !userId.equals(teacher.optString("id"))) {
                        toast("ابتدا با حساب معتبر معلم وارد شوید و رمز اولیه را تغییر دهید.");
                        return;
                    }
                    try {
                        Cipher cipher = vault.encryptionCipher();
                        askBiometric("فعال‌سازی ورود با اثر انگشت", cipher, authenticated -> {
                            try {
                                vault.saveAfterBiometric(authenticated, token);
                                activeToken = token;
                                toast("ورود با اثر انگشت فعال شد.");
                            } catch (Exception e) {
                                vault.clear();
                                toast("ذخیره امن نشست انجام نشد.");
                            }
                        });
                    } catch (Exception e) {
                        vault.clear();
                        toast("حسگر یا حافظه امن دستگاه در دسترس نیست.");
                    }
                });
            } catch (Exception e) {
                toast("ابتدا از مسیر رسمی با حساب معلم وارد سامانه شوید.");
            }
        });
    }

    private void beginBiometricUnlock() {
        if (unlocked || biometricDialogOpen || !vault.isEnabled()) return;
        try {
            Cipher cipher = vault.decryptionCipher();
            askBiometric("ورود با اثر انگشت", cipher, verifiedCipher -> {
                try {
                    String token = vault.readAfterBiometric(verifiedCipher);
                    if (token.isEmpty()) throw new Exception("empty token");
                    verifyTeacherSession(token, (teacher, unauthorized) -> {
                        if (teacher == null) {
                            if (unauthorized) {
                                toast("نشست معلم منقضی یا لغو شده است؛ ورود با رمز لازم است.");
                                loginFresh();
                            } else {
                                toast("ارتباط با سرور برقرار نشد. اینترنت را بررسی و دوباره تلاش کنید.");
                            }
                            return;
                        }
                        activeToken = token;
                        unlocked = true;
                        if (!browserLoaded) {
                            restoreSessionAndLoad(token, teacher);
                        } else {
                            // On resume, reject changed/cleared web sessions rather than
                            // reusing a fingerprint binding from a different account.
                            browser.evaluateJavascript("sessionStorage.getItem('" + TOKEN_KEY + "')", value -> {
                                try {
                                    Object old = new JSONTokener(value).nextValue();
                                    if (token.equals(old)) {
                                        lockOverlay.setVisibility(View.GONE);
                                    } else {
                                        vault.clear();
                                        lockOverlay.setVisibility(View.GONE);
                                        toast("حساب وب تغییر کرده است؛ اثر انگشت را دوباره فعال کنید.");
                                    }
                                } catch (Exception e) {
                                    vault.clear();
                                    lockOverlay.setVisibility(View.GONE);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    vault.clear();
                    toast("کلید بیومتریک دیگر معتبر نیست. ورود با رمز لازم است.");
                    loginFresh();
                }
            });
        } catch (GeneralSecurityException e) {
            vault.clear();
            toast("کلید بیومتریک گوشی تغییر کرده است؛ ورود دوباره لازم است.");
            loginFresh();
        }
    }

    private void restoreSessionAndLoad(String token, JSONObject user) {
        // The server user object, not cached personal details, defines the role.
        JSONObject session = new JSONObject();
        try {
            session.put("userId", user.getString("id"));
            session.put("authUser", user);
            session.put("loginAt", System.currentTimeMillis());
        } catch (Exception e) {
            loginFresh();
            return;
        }
        String script = "(function(){if(location.pathname.indexOf('/app')!==0)return;"
                + "sessionStorage.setItem('" + TOKEN_KEY + "'," + JSONObject.quote(token) + ");"
                + "sessionStorage.setItem('" + SESSION_KEY + "'," + JSONObject.quote(session.toString()) + ");"
                + "})();";
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Only the official origin receives the session restoration script.
            sessionInjector = WebViewCompat.addDocumentStartJavaScript(browser, script,
                    Collections.singleton("https://scooljavanrood.ir"));
            lockOverlay.setVisibility(View.GONE);
            browserLoaded = true;
            // The existing web source intentionally resumes an authenticated
            // teacher only on this return route, after its own server validation.
            browser.loadUrl(HOME + "?p635_return=1");
        } else {
            pendingScript = script;
            injectOnFirstPage = true;
            injectionWasApplied = false;
            browserLoaded = true;
            browser.loadUrl(HOME + "?p635_return=1");
        }
    }

    private void removeSessionInjector() {
        if (sessionInjector != null) {
            sessionInjector.remove();
            sessionInjector = null;
        }
    }

    private void loginFresh() {
        removeSessionInjector();
        vault.clear();
        activeToken = null;
        unlocked = true;
        lockOverlay.setVisibility(View.GONE);
        if (browserLoaded) {
            browser.evaluateJavascript("(function(){try{sessionStorage.removeItem('" + TOKEN_KEY
                    + "');sessionStorage.removeItem('" + SESSION_KEY
                    + "');}catch(e){}location.replace('" + HOME + "');})();", result -> {});
        } else {
            loadHome();
        }
    }

    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private Button toolbarButton(String s){
        Button button=new Button(this);button.setText(s);button.setAllCaps(false);
        button.setTextSize(13);button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(15,83,71)));
        button.setMinWidth(0);button.setMinimumWidth(0);
        button.setPadding(dp(5),0,dp(5),0);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(46)));
        return button;
    }
    private void updateButtons(){
        if(backBtn!=null){backBtn.setEnabled((popup!=null)||browser!=null&&browser.canGoBack());backBtn.setAlpha(backBtn.isEnabled()?1f:.45f);}
        if(forwardBtn!=null){forwardBtn.setEnabled(browser!=null&&browser.canGoForward());forwardBtn.setAlpha(forwardBtn.isEnabled()?1f:.45f);}
    }
    private void prepareWebView(WebView view, boolean isPopup){
        view.setBackgroundColor(Color.WHITE);
        WebSettings cfg=view.getSettings();
        cfg.setJavaScriptEnabled(true); // Required by existing /app/ application
        cfg.setDomStorageEnabled(true);
        cfg.setAllowFileAccess(false);
        cfg.setAllowContentAccess(true); // Document picker / camera content URIs
        cfg.setAllowFileAccessFromFileURLs(false);
        cfg.setAllowUniversalAccessFromFileURLs(false);
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        cfg.setSupportZoom(true); cfg.setBuiltInZoomControls(true);cfg.setDisplayZoomControls(false);
        cfg.setJavaScriptCanOpenWindowsAutomatically(true);
        cfg.setSupportMultipleWindows(true);
        if(Build.VERSION.SDK_INT>=26)cfg.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request){
                Uri uri=request.getUrl();
                if ("about".equalsIgnoreCase(uri.getScheme())||"blob".equalsIgnoreCase(uri.getScheme()))return false;
                if(isTrusted(uri))return false;
                openExternal(uri);return true;
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error){
                handler.cancel(); // Never bypass invalid certificates.
                toast("ارتباط امن تأیید نشد؛ اتصال متوقف شد.");
            }
            @Override public void onPageFinished(WebView v, String url){
                if (!isPopup) {
                    // One-shot restoration: never re-inject an old token after logout.
                    if (isTrusted(Uri.parse(url))) removeSessionInjector();
                    if (injectOnFirstPage && !injectionWasApplied && isTrusted(Uri.parse(url))) {
                        injectionWasApplied = true;
                        // Fallback for older system WebView builds lacking document-start scripts.
                        // Keep the lock overlay visible until the session has been restored.
                        String inject = pendingScript;
                        if (inject != null) {
                            v.evaluateJavascript(inject, result -> v.reload());
                            return;
                        }
                    }
                    if (injectOnFirstPage && injectionWasApplied) {
                        injectOnFirstPage = false;
                        pendingScript = null;
                        lockOverlay.setVisibility(View.GONE);
                    }
                    progress.setVisibility(View.GONE);
                    updateButtons();
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (!isPopup && request.isForMainFrame() && injectOnFirstPage) {
                    injectOnFirstPage = false;
                    pendingScript = null;
                    lockOverlay.setVisibility(View.VISIBLE);
                    toast("صفحه اصلی بارگذاری نشد. اتصال اینترنت را بررسی کنید.");
                }
            }
        });
        view.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int progressValue){
                if(!isPopup){progress.setVisibility(progressValue>=100?android.view.View.GONE:android.view.View.VISIBLE);progress.setProgress(progressValue);}
            }
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params){
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;
                openFilePicker(params);
                return true;
            }
            @Override public boolean onCreateWindow(WebView v,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){
                // Existing print / PDF flows use window.open. Show a secondary WebView
                // with a native Print button rather than silently dropping the popup.
                if(!isUserGesture || popup!=null) return false;
                popup=new WebView(MainActivity.this);
                prepareWebView(popup,true);
                popup.setDownloadListener(MainActivity.this::download);
                FrameLayout frame=new FrameLayout(MainActivity.this);
                frame.setBackgroundColor(Color.WHITE);
                frame.addView(popup,new FrameLayout.LayoutParams(-1,-1));
                LinearLayout controls=new LinearLayout(MainActivity.this);
                controls.setBackgroundColor(Color.rgb(15,83,71));
                Button close=toolbarButton("بستن");close.setOnClickListener(x->closePopup());
                Button print=toolbarButton("چاپ / PDF");print.setOnClickListener(x->printCurrentPage());
                controls.addView(close);controls.addView(print);
                FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(50),android.view.Gravity.TOP);
                frame.addView(controls,lp);
                content.addView(frame,new FrameLayout.LayoutParams(-1,-1));
                frame.setTag("popup");
                popup.setTag(frame);
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(popup);resultMsg.sendToTarget();updateButtons();
                return true;
            }
            @Override public void onCloseWindow(WebView window){if(window==popup)closePopup();}
        });
    }
    private void openExternal(Uri uri){
        try{
            String scheme=uri.getScheme();
            if(scheme==null || !("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme)||"tel".equalsIgnoreCase(scheme)))return;
            startActivity(new Intent(Intent.ACTION_VIEW,uri));
        }catch(ActivityNotFoundException e){toast("برنامه‌ای برای باز کردن این پیوند پیدا نشد.");}
    }
    private void closePopup(){
        if(popup==null)return;
        android.view.View frame=(android.view.View)popup.getTag();
        if(frame!=null)content.removeView(frame);
        popup.stopLoading();popup.destroy();popup=null;updateButtons();
    }
    private void printCurrentPage(){
        WebView selected=popup!=null?popup:browser;
        PrintManager pm=(PrintManager)getSystemService(Context.PRINT_SERVICE);
        if(pm==null){toast("چاپ روی این دستگاه پشتیبانی نمی‌شود.");return;}
        pm.print("مدرسه‌یار - سند معلم",selected.createPrintDocumentAdapter("MadreseyarTeacher"),null);
    }
    private void download(String url,String agent,String disposition,String mimeType,long length){
        try{
            Uri uri=Uri.parse(url);
            if(!isTrusted(uri)){toast("دریافت فقط از سرور رسمی مجاز است.");return;}
            String name=android.webkit.URLUtil.guessFileName(url,disposition,mimeType);
            DownloadManager.Request req=new DownloadManager.Request(uri);
            req.setTitle(name);req.setDescription("فایل مدرسه‌یار");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            String cookies=CookieManager.getInstance().getCookie(url);
            if(cookies!=null)req.addRequestHeader("Cookie",cookies);
            if(agent!=null)req.addRequestHeader("User-Agent",agent);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            toast("دریافت فایل شروع شد.");
        }catch(Exception e){toast("دریافت فایل ناموفق بود: "+e.getMessage());}
    }
    private void openFilePicker(WebChromeClient.FileChooserParams params){
        try{
            Intent pick=params.createIntent();
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            String[] types=params.getAcceptTypes();
            boolean acceptPhoto=types.length==0;
            for(String type:types)if(type!=null&&(type.startsWith("image/")||type.isBlank()))acceptPhoto=true;
            Intent chooser=Intent.createChooser(pick,"انتخاب فایل برای مدرسه‌یار");
            cameraUri=null;
            if(acceptPhoto){
                Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if(camera.resolveActivity(getPackageManager())!=null){
                    File dir=new File(getCacheDir(),"camera");if(!dir.exists())dir.mkdirs();
                    File file=File.createTempFile("teacher_",".jpg",dir);
                    cameraUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT,cameraUri);
                    camera.setClipData(ClipData.newUri(getContentResolver(),"photo",cameraUri));
                    camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,new Intent[]{camera});
                }
            }
            startActivityForResult(chooser,FILE_REQUEST);
        }catch(Exception e){
            if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
            toast("باز کردن انتخاب‌گر فایل ناموفق بود.");
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=FILE_REQUEST)return;
        Uri[] output=null;
        if(resultCode==Activity.RESULT_OK){
            if(data!=null){output=WebChromeClient.FileChooserParams.parseResult(resultCode,data);}
            else if(cameraUri!=null){output=new Uri[]{cameraUri};}
            if((output==null||output.length==0)&&cameraUri!=null)output=new Uri[]{cameraUri};
        }
        if(fileCallback!=null){fileCallback.onReceiveValue(output);fileCallback=null;}
        cameraUri=null;
    }
    private void back(){
        if (lockOverlay.getVisibility() == View.VISIBLE) { finish(); return; }
        if(popup!=null){closePopup();return;}
        if(browser.canGoBack()){browser.goBack();updateButtons();return;}
        new AlertDialog.Builder(this).setMessage("از اپلیکیشن معلم خارج شوید؟")
           .setNegativeButton("انصراف",(x,y)->{})
           .setPositiveButton("خروج",(x,y)->finish()).show();
    }
    @Override public void onBackPressed(){back();}
    @Override protected void onResume() {
        super.onResume();
        if (vault != null && vault.isEnabled() && !unlocked && !biometricDialogOpen) {
            lockNow();
            beginBiometricUnlock();
        }
    }
    @Override protected void onStop() {
        // Fully opaque cover before the background activity may be restored.
        if (vault != null && vault.isEnabled() && unlocked && !isChangingConfigurations()) lockNow();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); }
    @Override protected void onDestroy(){
        removeSessionInjector();
        offerHandler.removeCallbacks(offerCheck);
        authExecutor.shutdownNow();
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        closePopup();
        if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}
        super.onDestroy();
    }
}
