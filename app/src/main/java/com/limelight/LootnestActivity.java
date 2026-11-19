package com.limelight;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lootnest for TV's own screen, the one the TV's launcher opens.
 *
 * Lootnest on the PC installs this app over ADB and opens this screen with
 * the PC's address ("host") and a pairing PIN ("pairPin") that it also
 * hands the streaming engine. The screen adds that PC, pairs with the PIN,
 * and opens the host app named Lootnest, so nobody types anything on the
 * TV. Opened from the TV's launcher, it goes straight to the PC it paired
 * with, waking it first. After a stream it waits with Play, Settings and
 * Quit.
 *
 * Every step is logged with the tag LootnestTV, which Lootnest on the PC
 * reads back over ADB when something goes wrong.
 */
public class LootnestActivity extends Activity {
    static final String TAG = "LootnestTV";
    private static final String PREFS = "lootnest";
    private static final String APP_NAME = "Lootnest";
    private static final Pattern PIN = Pattern.compile("^[0-9]{4}$");
    private static final Pattern HOST = Pattern.compile("^[0-9A-Za-z.:\\-\\[\\]]{1,253}$");
    private static final long WAKE_MS = 90_000;

    // Lootnest's colours.
    private static final int C_GROUND = 0xFF111217, C_RAISED = 0xFF181A21, C_KEY = 0xFF1F222B;
    private static final int C_TEXT = 0xFFDFE5EB, C_TEXT_2 = 0xFFA6AEB9, C_TEXT_3 = 0xFF8C94A0;
    private static final int C_RED = 0xFFEF233C, C_ON_RED = 0xFF111217, C_BAD = 0xFFFF8A8A;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private String host, pin;           // from the latest intent
    private boolean startWhenReady = true;
    private volatile Thread worker;

    private TextView pcLine, status, detail;
    private ProgressBar spinner;
    private LinearLayout keys;
    private Typeface title, body, bold;

    static void log(String s) {
        Log.i(TAG, s);
        LimeLog.info(TAG + ": " + s);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder b) {
            final ComputerManagerService.ComputerManagerBinder binder = (ComputerManagerService.ComputerManagerBinder) b;
            new Thread(() -> {
                binder.waitForReady();
                managerBinder = binder;
                runOnUiThread(LootnestActivity.this::go);
            }).start();
        }

        public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        title = font("tilt-warp.ttf", Typeface.DEFAULT_BOLD);
        body = font("atkinson-next.ttf", Typeface.DEFAULT);
        bold = font("atkinson-next-bold.ttf", Typeface.DEFAULT_BOLD);
        setContentView(build());
        read(getIntent());
        bindService(new Intent(this, ComputerManagerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        read(intent);
        startWhenReady = true;
        go();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Back from a stream or from Settings: wait for the person.
        if (worker == null) {
            showReady(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (managerBinder != null) {
            managerBinder.stopPolling();
        }
        unbindService(connection);
    }

    private Typeface font(String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + name);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void read(Intent intent) {
        host = null;
        pin = null;
        if (intent == null) {
            return;
        }
        String h = intent.getStringExtra("host");
        String p = intent.getStringExtra("pairPin");
        if (h != null && HOST.matcher(h).matches()) {
            host = h;
            prefs().edit().putString("host", h).apply();
        }
        if (p != null && PIN.matcher(p).matches()) {
            pin = p;
        }
        log("Screen: opened" + (host != null ? " with host " + host : "") + (pin != null ? " and a pairing PIN" : ""));
    }

    private void go() {
        if (managerBinder == null || worker != null) {
            return;
        }
        final String h = host, p = pin;
        pin = null; // a PIN is used once
        worker = new Thread(() -> {
            try {
                run(h, p);
            } catch (InterruptedException e) {
                // Closing.
            } finally {
                worker = null;
            }
        });
        worker.start();
    }

    // The steps, off the screen's thread.
    private void run(String h, String p) throws InterruptedException {
        log("Screen: starting");
        busy("Looking for your PC", null);
        SharedPreferences sp = prefs();
        String uuid = sp.getString("uuid", null);
        if (h == null) {
            h = sp.getString("host", null);
        }
        ComputerDetails pc = null;
        if (h != null && (p != null || uuid == null || managerBinder.getComputer(uuid) == null)) {
            ComputerDetails d = new ComputerDetails();
            d.manualAddress = new ComputerDetails.AddressTuple(h, NvHTTP.DEFAULT_HTTP_PORT);
            if (managerBinder.addComputerBlocking(d)) {
                pc = d;
                sp.edit().putString("uuid", d.uuid).putString("name", d.name).apply();
                log("PC: " + d.name + " answers at " + h);
            } else {
                log("PC: nothing answers at " + h);
            }
        }
        if (pc == null && uuid != null) {
            pc = waitOnline(uuid);
        }
        if (pc == null) {
            if (h == null && uuid == null) {
                showReady("This TV isn't paired with a PC yet. On your PC, open Lootnest, then Settings, TV, and add this TV.");
            } else {
                problem("Your PC doesn't answer", "Check that it's on, with Lootnest's streaming turned on, and on the same network as this TV.");
            }
            return;
        }
        if (p != null && pc.pairState != PairingManager.PairState.PAIRED) {
            busy("Pairing with " + pc.name, null);
            if (!pair(pc, p)) {
                return;
            }
            pc = waitOnline(pc.uuid);
            if (pc == null) {
                problem("Your PC doesn't answer", "It paired, then stopped answering. Try again.");
                return;
            }
        }
        if (pc.pairState != PairingManager.PairState.PAIRED) {
            problem("This TV isn't paired with " + pc.name, "On your PC, open Lootnest, then Settings, TV, and add this TV again.");
            return;
        }
        if (startWhenReady) {
            startWhenReady = false;
            start(pc);
        } else {
            showReady(null);
        }
    }

    // Waits for a known PC to answer, waking it when it's asleep.
    private ComputerDetails waitOnline(final String uuid) throws InterruptedException {
        final ComputerDetails known = managerBinder.getComputer(uuid);
        if (known == null) {
            return null;
        }
        final Object lock = new Object();
        final ComputerDetails[] seen = new ComputerDetails[1];
        managerBinder.invalidateStateForComputer(uuid);
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(ComputerDetails d) {
                if (!uuid.equalsIgnoreCase(d.uuid) || d.state == ComputerDetails.State.UNKNOWN) {
                    return;
                }
                synchronized (lock) {
                    seen[0] = d;
                    lock.notifyAll();
                }
            }
        });
        long end = System.currentTimeMillis() + WAKE_MS, woke = 0;
        try {
            while (System.currentTimeMillis() < end) {
                ComputerDetails d;
                synchronized (lock) {
                    lock.wait(2000);
                    d = seen[0];
                    seen[0] = null;
                }
                if (d != null && d.state == ComputerDetails.State.ONLINE) {
                    log("PC: " + d.name + " is on");
                    return d;
                }
                if (d != null && d.state == ComputerDetails.State.OFFLINE) {
                    if (known.macAddress == null) {
                        log("PC: " + known.name + " is off and its address for waking isn't known");
                        return null;
                    }
                    if (System.currentTimeMillis() - woke > 5000) {
                        woke = System.currentTimeMillis();
                        busy("Waking " + known.name, "This can take up to a minute.");
                        try {
                            WakeOnLanSender.sendWolPacket(known);
                            log("PC: sent a wake-up to " + known.name);
                        } catch (java.io.IOException e) {
                            log("PC: the wake-up couldn't be sent: " + e.getMessage());
                        }
                        managerBinder.invalidateStateForComputer(uuid);
                    }
                }
            }
            log("PC: " + known.name + " didn't answer in " + WAKE_MS / 1000 + " s");
            return null;
        } finally {
            managerBinder.stopPolling();
        }
    }

    private boolean pair(ComputerDetails pc, String p) {
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort,
                    managerBinder.getUniqueId(), pc.serverCert, PlatformBinding.getCryptoProvider(this));
            if (http.getPairState() == PairingManager.PairState.PAIRED) {
                log("Pair: already paired with " + pc.name);
                return true;
            }
            PairingManager pm = http.getPairingManager();
            PairingManager.PairState st = pm.pair(http.getServerInfo(true), p);
            log("Pair: " + st);
            if (st == PairingManager.PairState.PAIRED) {
                managerBinder.getComputer(pc.uuid).serverCert = pm.getPairedCert();
                managerBinder.invalidateStateForComputer(pc.uuid);
                return true;
            }
            if (st == PairingManager.PairState.PIN_WRONG) {
                problem("The PIN wasn't accepted", "On your PC, add this TV again from Lootnest: Settings, TV.");
            } else if (st == PairingManager.PairState.ALREADY_IN_PROGRESS) {
                problem("Your PC is pairing with something else", "Wait a moment, then add this TV again from Lootnest.");
            } else {
                problem("Pairing didn't finish", "On your PC, add this TV again from Lootnest: Settings, TV.");
            }
        } catch (Exception e) {
            log("Pair: failed: " + e);
            problem("Pairing didn't finish", e.getMessage());
        }
        return false;
    }

    // Opens the host app named Lootnest.
    private void start(final ComputerDetails pc) {
        try {
            busy("Opening Lootnest on " + pc.name, null);
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort,
                    managerBinder.getUniqueId(), pc.serverCert, PlatformBinding.getCryptoProvider(this));
            NvApp app = null;
            List<NvApp> list = http.getAppList();
            for (NvApp a : list) {
                if (APP_NAME.equalsIgnoreCase(a.getAppName())) {
                    app = a;
                    break;
                }
            }
            if (app == null) {
                log("Lootnest: no app named Lootnest among " + list.size());
                problem("Lootnest isn't set up for streaming on " + pc.name, "On your PC, open Lootnest, then Settings, TV, and set up TV streaming again.");
                return;
            }
            log("Lootnest: starting the app");
            final Intent i = ServerHelper.createStartIntent(this, app, pc, managerBinder);
            runOnUiThread(() -> {
                showReady(null);
                startActivity(i);
            });
        } catch (Exception e) {
            log("Lootnest: couldn't start: " + e);
            problem("Lootnest didn't open on " + pc.name, e.getMessage());
        }
    }

    // ---- The screen ----

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View build() {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{C_RAISED, C_GROUND});
        frame.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        frame.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView gem = new ImageView(this);
        gem.setImageResource(R.drawable.lootnest_gem);
        col.addView(gem, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView name = text(title, 40, C_TEXT);
        name.setText(APP_NAME);
        col.addView(name);

        pcLine = text(body, 16, C_TEXT_2);
        col.addView(pcLine);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(C_TEXT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(32), dp(32));
        sl.topMargin = dp(24);
        col.addView(spinner, sl);

        status = text(bold, 20, C_TEXT);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stl.topMargin = dp(12);
        col.addView(status, stl);

        detail = text(body, 15, C_TEXT_3);
        detail.setMaxWidth(dp(520));
        col.addView(detail);

        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = dp(24);
        col.addView(keys, kl);

        TextView hint = text(body, 13, C_TEXT_3);
        hint.setText("To come back here while you play, press Start, Select, LB and RB together.");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = dp(24);
        frame.addView(hint, hl);

        TextView version = text(body, 12, C_TEXT_3);
        version.setText(BuildConfig.VERSION_NAME);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        vl.setMargins(0, 0, dp(24), dp(24));
        frame.addView(version, vl);
        return frame;
    }

    private Button key(String label, boolean main, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(main ? bold : body);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setPadding(dp(28), dp(10), dp(28), dp(10));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setStateListAnimator(null);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, shape(C_TEXT, main ? C_RED : 0));
        st.addState(new int[]{}, shape(main ? C_RED : C_KEY, 0));
        b.setBackground(st);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{C_GROUND, main ? C_ON_RED : C_TEXT}));
        b.setOnClickListener(on);
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(160).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable shape(int fill, int ring) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        if (ring != 0) {
            g.setStroke(dp(3), ring);
        }
        return g;
    }

    private void pcName() {
        String name = prefs().getString("name", null);
        pcLine.setText(name != null ? "Plays on " + name : "");
    }

    private void busy(final String what, final String more) {
        runOnUiThread(() -> {
            pcName();
            spinner.setVisibility(View.VISIBLE);
            status.setTextColor(C_TEXT);
            status.setText(what);
            detail.setText(more != null ? more : "");
            keys.removeAllViews();
            keys.addView(key("Cancel", false, v -> finish()));
        });
    }

    private void problem(final String what, final String more) {
        log("Screen: " + what + (more != null ? ": " + more : ""));
        runOnUiThread(() -> {
            pcName();
            spinner.setVisibility(View.GONE);
            status.setTextColor(C_BAD);
            status.setText(what);
            detail.setText(more != null ? more : "");
            keys.removeAllViews();
            Button retry = key("Try again", true, v -> {
                startWhenReady = true;
                go();
            });
            keys.addView(retry);
            keys.addView(key("Settings", false, v -> startActivity(new Intent(this, StreamSettings.class))));
            keys.addView(key("PCs", false, v -> startActivity(new Intent(this, PcView.class))));
            keys.addView(key("Quit", false, v -> finish()));
            retry.requestFocus();
        });
    }

    private void showReady(final String note) {
        log("Screen: ready");
        runOnUiThread(() -> {
            pcName();
            spinner.setVisibility(View.GONE);
            status.setTextColor(C_TEXT);
            boolean paired = prefs().getString("uuid", null) != null;
            status.setText(note != null ? "" : paired ? "Ready" : "");
            detail.setText(note != null ? note : "");
            keys.removeAllViews();
            Button play = null;
            if (paired) {
                play = key("Play", true, v -> {
                    startWhenReady = true;
                    go();
                });
                keys.addView(play);
            }
            keys.addView(key("Settings", play == null, v -> startActivity(new Intent(this, StreamSettings.class))));
            keys.addView(key("PCs", false, v -> startActivity(new Intent(this, PcView.class))));
            keys.addView(key("Quit", false, v -> finish()));
            (play != null ? play : keys.getChildAt(0)).requestFocus();
        });
    }
}
