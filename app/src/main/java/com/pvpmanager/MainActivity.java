package com.pvpmanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String PVP_URL = "https://demonicscans.org/pvp.php";

    public static WebView gameWebView;
    public static WebView uiWebView;

    private WebView     loginWebView;
    private FrameLayout loginContainer;
    private TextView    btnSaveConnect;
    private AndroidBridge bridge;
    private AccountStore  accountStore;

    // Whether we are in "add account" mode (vs. plain login)
    private boolean addingNewAccount = false;

    // ── Account chip & flyout (native overlay) ────────────────────────────────
    private FrameLayout accountChipContainer;
    private FrameLayout accountFlyoutOverlay;
    private TextView    chipNameText;
    private View        chipAvatarCircle;

    // ── Permission / file launchers ───────────────────────────────────────────
    private ActivityResultLauncher<String>   notificationPermissionLauncher;
    private ActivityResultLauncher<String[]> blsFilePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        accountStore = new AccountStore(this);

        notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> android.util.Log.d("PvPManager", "POST_NOTIFICATIONS granted=" + granted));

        blsFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                try {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception ignored) {}
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null && bridge != null) {
                        new Thread(() -> bridge.deliverBlsFileContent(is)).start();
                    }
                } catch (Exception e) {
                    android.util.Log.e("PvPManager", "BLS file open failed: " + e.getMessage());
                    if (bridge != null) bridge.appendLog("error", "BLS file open failed: " + e.getMessage());
                }
            });

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── Hidden game WebView ───────────────────────────────────────────────
        gameWebView = new WebView(this);
        gameWebView.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        applyWebViewSettings(gameWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(gameWebView, true);

        gameWebView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url)          { return false; }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) bridge.appendLog("debug", "gameWebView finished: " + url);
                injectUserScript(view);
                syncBlsToGameWebView();
                // Extract / refresh account identity after page load when connected
                if (bridge != null && bridge.isSessionVerified()
                        && url != null && url.contains("demonicscans.org")) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (bridge != null) bridge.extractCurrentAccount(null);
                    }, 2500);
                }
            }
        });
        root.addView(gameWebView);

        // ── Visible UI WebView ────────────────────────────────────────────────
        uiWebView = new WebView(this);
        uiWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyWebViewSettings(uiWebView, true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(uiWebView, true);

        bridge = new AndroidBridge(this, gameWebView, uiWebView, accountStore);
        uiWebView.addJavascriptInterface(bridge, "Android");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url)          { return false; }
        });
        uiWebView.loadUrl("file:///android_asset/main.html");
        root.addView(uiWebView);

        // ── Login Overlay ─────────────────────────────────────────────────────
        loginContainer = new FrameLayout(this);
        loginContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        loginContainer.setVisibility(View.INVISIBLE);

        LinearLayout navBar = buildLoginNavBar();
        FrameLayout.LayoutParams navP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        navP.gravity = Gravity.TOP;
        loginContainer.addView(navBar, navP);

        loginWebView = new WebView(this);
        FrameLayout.LayoutParams wvP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        wvP.topMargin = dp(56);
        applyWebViewSettings(loginWebView, false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                return handleLoginUrl(r.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handleLoginUrl(url); }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (bridge != null) {
                    if (bridge.isSessionVerified()) setConnectButtonState(ConnectState.SUCCESS);
                    else                            setConnectButtonState(ConnectState.IDLE);
                }
            }
        });
        loginWebView.loadUrl("about:blank");
        loginContainer.addView(loginWebView, wvP);
        root.addView(loginContainer);

        // ── Account chip (floating, top-left) ────────────────────────────────
        accountChipContainer = buildAccountChip();
        FrameLayout.LayoutParams chipP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        chipP.gravity = Gravity.TOP | Gravity.START;
        chipP.topMargin  = dp(8);
        chipP.leftMargin = dp(8);
        root.addView(accountChipContainer, chipP);

        // ── Account flyout overlay (initially hidden) ─────────────────────────
        accountFlyoutOverlay = new FrameLayout(this);
        accountFlyoutOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        accountFlyoutOverlay.setVisibility(View.GONE);
        // Semi-transparent background — tap outside to dismiss
        accountFlyoutOverlay.setBackgroundColor(Color.parseColor("#99000000"));
        accountFlyoutOverlay.setOnClickListener(v -> closeAccountFlyout());
        root.addView(accountFlyoutOverlay);

        setContentView(root);

        // ── Startup: restore active account cookies ───────────────────────────
        restoreActiveAccountOnStartup();

        gameWebView.loadUrl(PVP_URL);

        Intent svcIntent = new Intent(this, PvpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent);
        else startService(svcIntent);

        requestRuntimePermissions();

        // Startup cookie validation
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (bridge != null && bridge.isSessionVerified()) {
                String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
                boolean hasCookies = cookies != null && !cookies.trim().isEmpty();
                if (!hasCookies) {
                    bridge.setConnected(false);
                    bridge.appendLog("system", "Startup: session flag set but no cookies found — reset to disconnected");
                } else {
                    bridge.appendLog("system", "Startup: session resumed, cookies present (" + cookies.length() + " chars)");
                }
            }
        }, 1000);
    }

    // ── Startup account restore ───────────────────────────────────────────────

    private void restoreActiveAccountOnStartup() {
        String activeId = accountStore.getActiveAccountId();
        if (activeId == null) return;
        JSONObject account = accountStore.getAccount(activeId);
        if (account == null) return;

        String cookies = account.optString("cookies", "");
        if (!cookies.isEmpty()) {
            injectCookiesForAccount(cookies);
            bridge.appendLog("system", "Startup: injected cookies for account "
                    + account.optString("playerName", activeId));
        }
        updateAccountChip();
    }

    // ── Account switching ─────────────────────────────────────────────────────

    /**
     * Full account switch:
     * 1. Stop bot gracefully
     * 2. Save current account cookies + running state
     * 3. Clear cookies
     * 4. Inject target account cookies
     * 5. Set active account
     * 6. Reload gameWebView
     * 7. Restore target account state
     */
    public void switchToAccount(String playerId) {
        if (bridge == null) return;
        closeAccountFlyout();

        String currentId = accountStore.getActiveAccountId();

        // 1. Stop bot for current account
        bridge.evaluateInGameWebView("if(window.__pvpmSetRunning) window.__pvpmSetRunning(false);");
        if (currentId != null) bridge.putBoolScoped("running", false);

        // 2. Save current account cookies
        if (currentId != null && !currentId.equals(playerId)) {
            CookieManager.getInstance().flush();
            String curCookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
            if (curCookies != null && !curCookies.isEmpty()) {
                accountStore.updateCookies(currentId, curCookies);
            }
        }

        // If switching to "not logged in" / unknown
        if (playerId == null || playerId.isEmpty()) {
            CookieHelper.clearAll();
            accountStore.setActiveAccountId(null);
            bridge.setConnected(false);
            updateAccountChip();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (gameWebView != null) gameWebView.loadUrl(PVP_URL);
            }, 200);
            return;
        }

        JSONObject targetAccount = accountStore.getAccount(playerId);
        if (targetAccount == null) {
            bridge.appendLog("warning", "switchToAccount: account not found — " + playerId);
            return;
        }

        // 3. Clear cookies
        CookieHelper.clearAll();

        // 4. Inject target account cookies
        String targetCookies = targetAccount.optString("cookies", "");
        if (!targetCookies.isEmpty()) {
            injectCookiesForAccount(targetCookies);
        }

        // 5. Set active account
        accountStore.setActiveAccountId(playerId);
        bridge.setConnected(!targetCookies.isEmpty());

        // 6. Update chip immediately
        updateAccountChip();
        bridge.appendLog("system", "Switching to account: " + targetAccount.optString("playerName") + " (" + playerId + ")");

        // 7. Reload gameWebView — onPageFinished will inject pvp_manager.js and extract identity
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) {
                gameWebView.loadUrl(PVP_URL);
            }
        }, 300);

        // 8. Notify UI to reflect new account state
        new Handler(Looper.getMainLooper()).postDelayed(() -> bridge.notifyUiStateChanged(), 400);
    }

    /** Inject a cookie string (semicolon-separated key=value pairs) into CookieManager. */
    private void injectCookiesForAccount(String cookieString) {
        if (cookieString == null || cookieString.trim().isEmpty()) return;
        String[] pairs = cookieString.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            if (!pair.isEmpty()) {
                CookieManager.getInstance().setCookie("https://demonicscans.org", pair);
                CookieManager.getInstance().setCookie("demonicscans.org", pair);
            }
        }
        CookieManager.getInstance().flush();
    }

    /** Remove account and all its scoped data. */
    public void removeAccountWithData(String playerId) {
        if (playerId == null) return;
        bridge.removeAccountScopedData(playerId);
        accountStore.removeAccount(playerId);

        // If it was the active account, switch to another or clear
        if (playerId.equals(accountStore.getActiveAccountId())) {
            JSONArray remaining = accountStore.getAccounts();
            if (remaining.length() > 0) {
                try {
                    String nextId = remaining.getJSONObject(0).optString("playerId", "");
                    if (!nextId.isEmpty()) { switchToAccount(nextId); return; }
                } catch (JSONException ignored) {}
            }
            // No accounts left
            accountStore.setActiveAccountId(null);
            CookieHelper.clearAll();
            bridge.setConnected(false);
            updateAccountChip();
            bridge.notifyUiStateChanged();
        } else {
            updateAccountChip();
        }
        bridge.appendLog("system", "Account removed: " + playerId);
    }

    /** Opens login overlay in "add new account" mode. */
    public void startAddAccountFlow() {
        addingNewAccount = true;
        showLoginInternal("https://demonicscans.org");
    }

    // ── Account chip UI ───────────────────────────────────────────────────────

    private FrameLayout buildAccountChip() {
        FrameLayout chip = new FrameLayout(this);
        chip.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(10), dp(6));

        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(Color.parseColor("#CC1a1025"));
        chipBg.setCornerRadius(dp(20));
        chipBg.setStroke(dp(1), Color.parseColor("#44ffffff"));
        row.setBackground(chipBg);

        // Avatar circle
        chipAvatarCircle = buildAvatarCircle("?", Color.parseColor("#6a3fb5"), dp(28));
        LinearLayout.LayoutParams avP = new LinearLayout.LayoutParams(dp(28), dp(28));
        avP.setMarginEnd(dp(6));
        row.addView(chipAvatarCircle, avP);

        // Name
        chipNameText = new TextView(this);
        chipNameText.setText("Not Logged In");
        chipNameText.setTextColor(Color.parseColor("#cccccc"));
        chipNameText.setTextSize(12f);
        chipNameText.setMaxLines(1);
        chipNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(chipNameText, new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // Arrow
        TextView arrow = new TextView(this);
        arrow.setText(" ▼");
        arrow.setTextColor(Color.parseColor("#888888"));
        arrow.setTextSize(10f);
        row.addView(arrow);

        chip.addView(row);
        chip.setOnClickListener(v -> showAccountFlyout());
        return chip;
    }

    /** Updates the chip to reflect the currently active account. */
    public void updateAccountChip() {
        if (accountChipContainer == null || chipNameText == null) return;
        runOnUiThread(() -> {
            JSONObject account = accountStore.getActiveAccount();
            if (account != null) {
                String name = account.optString("playerName", "Unknown");
                chipNameText.setText(name);
                chipNameText.setTextColor(Color.WHITE);
                // Update avatar circle initial
                if (chipAvatarCircle instanceof TextView) {
                    String initial = name.length() > 0 ? String.valueOf(name.charAt(0)).toUpperCase() : "?";
                    ((TextView) chipAvatarCircle).setText(initial);
                    ((TextView) chipAvatarCircle).setBackground(
                        makeCircleDrawable(avatarColor(account.optString("playerId", name))));
                }
            } else {
                chipNameText.setText("Not Logged In");
                chipNameText.setTextColor(Color.parseColor("#888888"));
                if (chipAvatarCircle instanceof TextView) {
                    ((TextView) chipAvatarCircle).setText("●");
                    ((TextView) chipAvatarCircle).setBackground(
                        makeCircleDrawable(Color.parseColor("#444444")));
                }
            }
        });
    }

    // ── Account flyout UI ─────────────────────────────────────────────────────

    private void showAccountFlyout() {
        if (accountFlyoutOverlay == null) return;
        accountFlyoutOverlay.removeAllViews();

        // Flyout card — positioned top-left
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1e1830"));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.parseColor("#3d2e60"));
        card.setBackground(cardBg);
        card.setPadding(dp(6), dp(8), dp(6), dp(8));
        card.setMinimumWidth(dp(220));

        // Stop card from closing flyout when tapped
        card.setOnClickListener(v -> { /* consume */ });

        // ── Header ──────────────────────────────────────────────────────
        TextView header = new TextView(this);
        header.setText("Accounts");
        header.setTextColor(Color.parseColor("#c9a8ff"));
        header.setTextSize(13f);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setPadding(dp(12), dp(4), dp(12), dp(8));
        card.addView(header);

        // ── Divider ─────────────────────────────────────────────────────
        card.addView(makeDivider());

        // ── Accounts list ────────────────────────────────────────────────
        String activeId = accountStore.getActiveAccountId();
        JSONArray accounts = accountStore.getAccounts();

        if (accounts.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No saved accounts");
            empty.setTextColor(Color.parseColor("#666666"));
            empty.setTextSize(12f);
            empty.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.addView(empty);
        } else {
            for (int i = 0; i < accounts.length(); i++) {
                try {
                    JSONObject acc = accounts.getJSONObject(i);
                    card.addView(buildAccountRow(acc, acc.optString("playerId", "").equals(activeId)));
                } catch (JSONException ignored) {}
            }
        }

        // ── Divider ─────────────────────────────────────────────────────
        card.addView(makeDivider());

        // ── Add Account ──────────────────────────────────────────────────
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        addRow.setOnClickListener(v -> {
            closeAccountFlyout();
            startAddAccountFlow();
        });

        TextView addIcon = new TextView(this);
        addIcon.setText("+");
        addIcon.setTextColor(Color.parseColor("#22c55e"));
        addIcon.setTextSize(18f);
        addIcon.setTypeface(addIcon.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams addIconP = new LinearLayout.LayoutParams(dp(32), dp(32));
        addIconP.setMarginEnd(dp(10));
        addRow.addView(addIcon, addIconP);

        TextView addLabel = new TextView(this);
        addLabel.setText("Add Account");
        addLabel.setTextColor(Color.parseColor("#22c55e"));
        addLabel.setTextSize(13f);
        addRow.addView(addLabel);

        card.addView(addRow);

        // Position card near top-left
        FrameLayout.LayoutParams cardP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardP.gravity = Gravity.TOP | Gravity.START;
        cardP.topMargin  = dp(48);
        cardP.leftMargin = dp(8);

        accountFlyoutOverlay.addView(card, cardP);
        accountFlyoutOverlay.setVisibility(View.VISIBLE);
    }

    private View buildAccountRow(JSONObject account, boolean isActive) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        if (isActive) {
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(Color.parseColor("#2a1f45"));
            rowBg.setCornerRadius(dp(10));
            row.setBackground(rowBg);
        }

        String pid  = account.optString("playerId", "");
        String name = account.optString("playerName", "Unknown");
        int    lvl  = account.optInt("level", 0);

        // Avatar circle
        View ava = buildAvatarCircle(
            name.length() > 0 ? String.valueOf(name.charAt(0)).toUpperCase() : "?",
            avatarColor(pid), dp(32));
        LinearLayout.LayoutParams avaP = new LinearLayout.LayoutParams(dp(32), dp(32));
        avaP.setMarginEnd(dp(10));
        row.addView(ava, avaP);

        // Name + ID column
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(info, infoP);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(isActive ? Color.WHITE : Color.parseColor("#cccccc"));
        nameView.setTextSize(13f);
        if (isActive) nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
        info.addView(nameView);

        TextView idView = new TextView(this);
        idView.setText("ID: " + pid + (lvl > 0 ? " · Lv " + lvl : ""));
        idView.setTextColor(Color.parseColor("#888888"));
        idView.setTextSize(11f);
        info.addView(idView);

        // Status indicator
        TextView status = new TextView(this);
        status.setText(account.optString("cookies", "").isEmpty() ? "●" : "●");
        status.setTextColor(account.optString("cookies", "").isEmpty()
                ? Color.parseColor("#555555") : Color.parseColor("#22c55e"));
        status.setTextSize(10f);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusP.setMarginEnd(dp(8));
        row.addView(status, statusP);

        // Active check
        if (isActive) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextColor(Color.parseColor("#a855f7"));
            check.setTextSize(14f);
            row.addView(check);
        }

        // Tap to switch
        final String switchId = pid;
        row.setOnClickListener(v -> {
            if (!isActive) switchToAccount(switchId);
            else           closeAccountFlyout();
        });

        // Long-press to remove
        row.setOnLongClickListener(v -> {
            closeAccountFlyout();
            showRemoveAccountConfirm(account);
            return true;
        });

        return row;
    }

    private void showRemoveAccountConfirm(JSONObject account) {
        String name = account.optString("playerName", "Unknown");
        String pid  = account.optString("playerId", "");
        new android.app.AlertDialog.Builder(this)
            .setTitle("Remove Account")
            .setMessage("Remove " + name + " from account switcher?\n\nThis clears saved cookies and runtime data for this account.")
            .setPositiveButton("Remove", (d, w) -> removeAccountWithData(pid))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void closeAccountFlyout() {
        if (accountFlyoutOverlay != null) accountFlyoutOverlay.setVisibility(View.GONE);
    }

    // ── Avatar helpers ────────────────────────────────────────────────────────

    private View buildAvatarCircle(String initial, int bgColor, int sizePx) {
        TextView tv = new TextView(this);
        tv.setText(initial);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(makeCircleDrawable(bgColor));
        return tv;
    }

    private GradientDrawable makeCircleDrawable(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    /** Deterministic color from player ID string. */
    private int avatarColor(String seed) {
        int[] palette = {
            0xFF6a3fb5, 0xFF1565C0, 0xFF00897B, 0xFFD84315,
            0xFF6A1B9A, 0xFF00695C, 0xFF558B2F, 0xFF4527A0
        };
        int h = seed != null ? Math.abs(seed.hashCode()) : 0;
        return palette[h % palette.length];
    }

    private View makeDivider() {
        View d = new View(this);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        d.setBackgroundColor(Color.parseColor("#2d2040"));
        return d;
    }

    // ── BLS File Picker ───────────────────────────────────────────────────────
    public void requestBlsFilePicker() {
        try { blsFilePickerLauncher.launch(new String[]{"application/json", "*/*"}); }
        catch (Exception e) {
            android.util.Log.e("PvPManager", "Could not open file picker: " + e.getMessage());
            if (bridge != null) bridge.appendLog("error", "File picker failed: " + e.getMessage());
        }
    }

    private void syncBlsToGameWebView() {
        if (bridge == null || gameWebView == null) return;
        String blsJson = bridge.getBlsMemory();
        if (blsJson == null || blsJson.equals("{}")) return;
        final String escaped = blsJson.replace("\\", "\\\\").replace("'", "\\'");
        new Handler(Looper.getMainLooper()).post(() ->
            gameWebView.evaluateJavascript(
                "try{localStorage.setItem('bls_memory','" + escaped + "');}catch(e){}", null));
    }

    // ── Runtime permissions ───────────────────────────────────────────────────
    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                android.util.Log.w("PvPManager", "Battery opt request failed: " + e.getMessage());
            }
        }, 3000);
    }

    // ── Login overlay nav bar ─────────────────────────────────────────────────
    private enum ConnectState { IDLE, SUCCESS, FAILURE }

    private LinearLayout buildLoginNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1a1025"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView back = pillButton("← BACK", Color.parseColor("#2d2040"));
        back.setOnClickListener(v -> closeLoginOverlay());
        LinearLayout.LayoutParams backP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        backP.setMargins(0, 0, dp(6), 0);
        bar.addView(back, backP);

        TextView reload = pillButton("⟳ RELOAD", Color.parseColor("#1565C0"));
        reload.setOnClickListener(v -> { if (loginWebView != null) loginWebView.reload(); });
        LinearLayout.LayoutParams reloadP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        reloadP.setMargins(0, 0, dp(6), 0);
        bar.addView(reload, reloadP);

        btnSaveConnect = pillButton("SAVE & CONNECT", Color.parseColor("#00897B"));
        btnSaveConnect.setOnClickListener(v -> saveConnect());
        bar.addView(btnSaveConnect, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        return bar;
    }

    private void setConnectButtonState(ConnectState state) {
        if (btnSaveConnect == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        switch (state) {
            case SUCCESS:
                bg.setColor(Color.parseColor("#2E7D32"));
                btnSaveConnect.setText("✓ CONNECTED");
                btnSaveConnect.setEnabled(false);
                break;
            case FAILURE:
                bg.setColor(Color.parseColor("#B71C1C"));
                btnSaveConnect.setText("✗ NOT LOGGED IN");
                btnSaveConnect.setEnabled(true);
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> setConnectButtonState(ConnectState.IDLE), 2500);
                break;
            default:
                bg.setColor(Color.parseColor("#00897B"));
                btnSaveConnect.setText("SAVE & CONNECT");
                btnSaveConnect.setEnabled(true);
                break;
        }
        btnSaveConnect.setBackground(bg);
    }

    public void showLogin() {
        addingNewAccount = false;
        showLoginInternal(null);
    }

    private void showLoginInternal(String forceUrl) {
        if (loginWebView == null || loginContainer == null) return;
        if (!addingNewAccount && bridge != null && bridge.isSessionVerified()) {
            setConnectButtonState(ConnectState.SUCCESS);
        } else {
            setConnectButtonState(ConnectState.IDLE);
        }
        loginContainer.setVisibility(View.VISIBLE);
        loginWebView.requestFocus();
        String cur = loginWebView.getUrl();
        boolean onAuthPage = cur != null && (cur.contains("/sign.php") || cur.contains("/signin") ||
                cur.contains("/login") || cur.contains("/register") || cur.equals("about:blank") || cur.isEmpty());

        String target = forceUrl != null ? forceUrl : "https://demonicscans.org";
        if (cur == null || cur.isEmpty() || onAuthPage || forceUrl != null) {
            loginWebView.loadUrl(target);
        }
        if (bridge != null) bridge.appendLog("system",
            addingNewAccount ? "Login overlay opened (add account mode)" : "Login overlay opened");
    }

    // ── Save & Connect ────────────────────────────────────────────────────────
    public void saveConnect() {
        if (bridge == null || loginWebView == null) {
            Toast.makeText(this, "Not ready — try again", Toast.LENGTH_SHORT).show();
            return;
        }

        bridge.appendLog("system", "SAVE & CONNECT tapped" + (addingNewAccount ? " [add account mode]" : ""));
        CookieManager.getInstance().flush();

        String currentUrl = loginWebView.getUrl();

        boolean urlOk = currentUrl != null
                && currentUrl.contains("demonicscans.org")
                && !currentUrl.contains("sign.php")
                && !currentUrl.contains("signin")
                && !currentUrl.contains("login")
                && !currentUrl.contains("register")
                && !currentUrl.equals("about:blank");

        String cookies = CookieManager.getInstance().getCookie("https://demonicscans.org");
        boolean hasCookies = cookies != null && !cookies.trim().isEmpty();

        if (!urlOk || !hasCookies) {
            setConnectButtonState(ConnectState.FAILURE);
            String reason = !hasCookies ? "No cookies found — please log in first"
                                        : "Please log in on the site, then tap Save & Connect";
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            bridge.appendLog("warning", "saveConnect failed: urlOk=" + urlOk + " hasCookies=" + hasCookies);
            return;
        }

        // Mark connected (single session)
        bridge.setConnected(true);
        bridge.appendLog("system", "Session saved ✓ cookies=" + cookies.length() + " chars");
        setConnectButtonState(ConnectState.SUCCESS);

        boolean wasAddingNew = addingNewAccount;
        addingNewAccount = false;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            closeLoginOverlay();
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 600);

        // Load pvp.php in gameWebView — onPageFinished will extract account identity
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gameWebView != null) {
                CookieManager.getInstance().flush();
                bridge.appendLog("debug", "Loading " + PVP_URL + " in gameWebView");
                gameWebView.loadUrl(PVP_URL);
            }
        }, 800);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (uiWebView != null) {
                uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
            }
        }, 1200);
    }

    // ── Overlay helpers ───────────────────────────────────────────────────────
    private void closeLoginOverlay() {
        if (loginContainer == null) return;
        loginContainer.setVisibility(View.INVISIBLE);
        if (bridge == null || !bridge.isSessionVerified()) setConnectButtonState(ConnectState.IDLE);
        if (uiWebView != null) {
            uiWebView.evaluateJavascript("if(window.__pvpmUiRefresh) window.__pvpmUiRefresh();", null);
        }
    }

    private boolean handleLoginUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("https://") || url.startsWith("http://")) return false;
        if (url.startsWith("intent://") || url.startsWith("market://")) {
            try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)); } catch (Exception ignored) {}
            return true;
        }
        if (url.startsWith("tel:") || url.startsWith("mailto:")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            return true;
        }
        return true;
    }

    // ── WebView settings ──────────────────────────────────────────────────────
    private void applyWebViewSettings(WebView wv, boolean fileAccess) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccessFromFileURLs(fileAccess);
        s.setAllowUniversalAccessFromFileURLs(fileAccess);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
    }

    public void injectUserScript(WebView view) {
        try {
            InputStream is = getAssets().open("pvp_manager.js");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int n;
            while ((n = is.read(chunk)) != -1) baos.write(chunk, 0, n);
            is.close();
            String script = baos.toString(StandardCharsets.UTF_8.name());
            view.evaluateJavascript("(function(){\n" + script + "\n})();", null);
            android.util.Log.d("PvPManager", "Injected pvp_manager.js successfully");
        } catch (IOException e) {
            android.util.Log.e("PvPManager", "injectUserScript: " + e.getMessage());
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────
    private TextView pillButton(String text, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(24));
        tv.setBackground(bg);
        return tv;
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (accountFlyoutOverlay != null && accountFlyoutOverlay.getVisibility() == View.VISIBLE) {
            closeAccountFlyout(); return;
        }
        if (loginContainer != null && loginContainer.getVisibility() == View.VISIBLE) {
            closeLoginOverlay(); return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bridge != null) bridge.stopKeepalive();
        if (gameWebView  != null) { gameWebView.destroy();  gameWebView  = null; }
        if (uiWebView    != null) { uiWebView.destroy();    uiWebView    = null; }
        if (loginWebView != null) { loginWebView.destroy(); loginWebView = null; }
    }
}
