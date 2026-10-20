package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View btnRemote;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;

    // STB & LCN Channels Engine
    private LcnDatabaseHelper dbHelper;
    private SensyIrManager irManager;
    private RemoteSidebarDialog sidebarDialog;
    private RailsAdapter railsAdapter;
    private RailCardsAdapter favAppsAdapter;
    private RailCardsAdapter channelsAdapter;
    private RailCardsAdapter inputsAdapter;
    private RailCardsAdapter allAppsAdapter;
    private Config cfg;
    private int activeProviderId = 43; // Default: Kerala Vision
    private String activeProviderTitle = "Kerala Vision Digital";
    private int activeRemoteId = 43;
    private int activeRemoteBitLength = 32;
    private final Map<String, String> keyCodes = new HashMap<>();
    private final List<LcnDatabaseHelper.Channel> currentChannels = new ArrayList<>();
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (glass != null) glass.setVisibility(View.GONE);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        this.cfg = cfg;

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);

        favAppsAdapter = new RailCardsAdapter();
        favAppsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        favAppsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                if (btnWifi != null) btnWifi.requestFocus();
                return true;
            }

            @Override
            public boolean onNavigateDown(int position) {
                focusRail(RailsAdapter.RAIL_CHANNELS);
                return true;
            }
        });

        channelsAdapter = new RailCardsAdapter();
        channelsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        channelsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                focusRail(RailsAdapter.RAIL_APPS);
                return true;
            }

            @Override
            public boolean onNavigateDown(int position) {
                focusRail(RailsAdapter.RAIL_INPUTS);
                return true;
            }
        });

        inputsAdapter = new RailCardsAdapter();
        inputsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        inputsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                focusRail(RailsAdapter.RAIL_CHANNELS);
                return true;
            }

            @Override
            public boolean onNavigateDown(int position) {
                focusRail(RailsAdapter.RAIL_ALL_APPS);
                return true;
            }
        });

        allAppsAdapter = new RailCardsAdapter();
        allAppsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        allAppsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                focusRail(RailsAdapter.RAIL_INPUTS);
                return true;
            }

            @Override
            public boolean onNavigateDown(int position) {
                return true;
            }
        });

        railsAdapter = new RailsAdapter(this, railType -> {
            if (railType == RailsAdapter.RAIL_CHANNELS) {
                openRemoteSidebar();
            } else if (railType == RailsAdapter.RAIL_INPUTS) {
                showInputs();
            }
        });

        List<RailsAdapter.RailConfig> configs = new ArrayList<>();
        configs.add(new RailsAdapter.RailConfig(RailsAdapter.RAIL_APPS, "Favorite Apps", R.drawable.ic_apps, 0xFFE11D48, favAppsAdapter));
        configs.add(new RailsAdapter.RailConfig(RailsAdapter.RAIL_CHANNELS, "Live Channels", R.drawable.ic_livetv, 0xFF0EA5E9, channelsAdapter));
        configs.add(new RailsAdapter.RailConfig(RailsAdapter.RAIL_INPUTS, "Inputs", R.drawable.ic_input, 0xFF10B981, inputsAdapter));
        configs.add(new RailsAdapter.RailConfig(RailsAdapter.RAIL_ALL_APPS, "All Apps", R.drawable.ic_apps, 0xFF8B5CF6, allAppsAdapter));
        railsAdapter.setRails(configs);

        grid.setAdapter(railsAdapter);
        reloadApps();
        reloadInputs();
        grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 380f : 300f)
                        .setDampingRatio(advancedEffects ? 0.80f : 0.65f));
            }
        }

        btnRemote = findViewById(R.id.btnRemote);
        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        if (btnRemote != null) {
            btnRemote.setOnClickListener(v -> openRemoteSidebar());
        }
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnSettings.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_SETTINGS));
            return true;
        });

        // Initialize STB & Live Channels Engine
        dbHelper = LcnDatabaseHelper.getInstance(this);
        irManager = SensyIrManager.getInstance(this);
        irManager.requestPermission(null);

        TvCommandServer server = TvCommandServer.getInstance(this);
        server.setListener(this::loadStbChannels);
        server.start(TvCommandServer.DEFAULT_PORT);

        loadStbChannels();

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusRail(RailsAdapter.RAIL_APPS);
                return true;
            }
            int firstButton = (btnRemote != null && btnRemote.getVisibility() == View.VISIBLE) ? R.id.btnRemote
                    : (btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : R.id.btnWifi);
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        if (btnRemote != null) btnRemote.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnRemote, btnInputs, btnWifi, btnSettings}) {
                if (button == null) continue;
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && (newF.getId() == R.id.card || newF.getId() == R.id.channel_card || newF.getId() == R.id.input_card);
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (grid != null) {
            grid.scrollToPosition(0);
        }
        lastFocused = 0;
        lastFocusedKey = (adapter != null && adapter.getItemCount() > 0) ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        promptsPaused = false;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        sky.update();
        loadStbChannels();
        if (getCurrentFocus() == null && grid != null) {
            grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));
        }
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    private final Handler keyHandler = new Handler(Looper.getMainLooper());
    private boolean isOkLongPressed = false;
    private final Runnable okLongPressRunnable = () -> {
        isOkLongPressed = true;
        openRemoteSidebar();
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.isLongPress()) {
                    keyHandler.removeCallbacks(okLongPressRunnable);
                    isOkLongPressed = true;
                    openRemoteSidebar();
                    return true;
                }
                if (event.getRepeatCount() == 0) {
                    isOkLongPressed = false;
                    keyHandler.removeCallbacks(okLongPressRunnable);
                    keyHandler.postDelayed(okLongPressRunnable, 350);
                } else if (event.getRepeatCount() >= 1 && !isOkLongPressed) {
                    isOkLongPressed = true;
                    keyHandler.removeCallbacks(okLongPressRunnable);
                    openRemoteSidebar();
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(okLongPressRunnable);
                if (isOkLongPressed) {
                    isOkLongPressed = false;
                    return true; // Consume UP to suppress click on card/button
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_UP) {
                openRemoteSidebar();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void loadStbChannels() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::loadStbChannels);
            return;
        }
        android.content.SharedPreferences sp = getSharedPreferences("stb_settings", MODE_PRIVATE);
        activeProviderId = sp.getInt("provider_id", 43);
        activeProviderTitle = sp.getString("provider_title", "Kerala Vision Digital");
        activeRemoteId = sp.getInt("remote_id", 43);
        activeRemoteBitLength = sp.getInt("bit_length", 32);

        new Thread(() -> {
            Map<String, String> codes = dbHelper.getKeyCodes(activeRemoteId);
            String customJson = sp.getString("custom_keys_json", null);
            if (customJson != null) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(customJson);
                    if (obj.has("keys")) {
                        org.json.JSONObject kObj = obj.getJSONObject("keys");
                        java.util.Iterator<String> it = kObj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            codes.put(k.toUpperCase(Locale.ROOT), kObj.getString(k));
                        }
                    }
                } catch (Exception ignored) {}
            }
            List<LcnDatabaseHelper.Channel> chList = dbHelper.getChannelsForProvider(activeProviderId, 100);
            handler.post(() -> {
                keyCodes.clear();
                keyCodes.putAll(codes);
                currentChannels.clear();
                if (chList != null) {
                    currentChannels.addAll(chList);
                }
                updateChannelsRail();
            });
        }).start();
    }

    private int getChannelLogoRes(int channelId) {
        if (channelId <= 0) return 0;
        return getResources().getIdentifier("channel_" + channelId, "drawable", getPackageName());
    }

    private void updateChannelsRail() {
        if (channelsAdapter == null) return;
        List<RailCard> cards = new ArrayList<>();
        for (LcnDatabaseHelper.Channel ch : currentChannels) {
            int logoRes = getChannelLogoRes(ch.channelId);
            String badge = String.valueOf(ch.lcn);
            cards.add(new RailCard(
                    "ch_" + ch.lcn,
                    ch.name,
                    badge,
                    null,
                    logoRes,
                    RailCard.ArtworkStyle.LOGO_CENTER,
                    0xFF0284C7,
                    ch,
                    new RailCard.ActionListener() {
                        @Override
                        public void onClick(RailCard card) {
                            onChannelSelected((LcnDatabaseHelper.Channel) card.payload);
                        }

                        @Override
                        public boolean onLongClick(RailCard card) {
                            openRemoteSidebar();
                            return true;
                        }
                    }
            ));
        }
        channelsAdapter.setItems(cards);
    }

    private void onChannelSelected(LcnDatabaseHelper.Channel ch) {
        if (ch == null) return;
        android.content.SharedPreferences sp = getSharedPreferences("stb_settings", MODE_PRIVATE);
        String prefKey = "ch_use_" + activeProviderId + "_" + ch.lcn;
        int current = sp.getInt(prefKey, 0);
        sp.edit().putInt(prefKey, current + 1).apply();

        Toast.makeText(this, "Tuning " + ch.lcn + " " + ch.name + "...", Toast.LENGTH_SHORT).show();
        irManager.tuneChannelAsync(String.valueOf(ch.lcn), keyCodes, activeRemoteBitLength, null);

        Intent intent = new Intent(this, TvPlayerActivity.class);
        intent.putExtra(TvPlayerActivity.EXTRA_INITIAL_LCN, ch.lcn);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        handler.postDelayed(this::loadStbChannels, 1500);
    }

    private void onChannelCardFocused(LcnDatabaseHelper.Channel ch, View card) {
        hideHero();
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;
        float scale = 1.08f;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density);
        }
        glassTargetX = x;
        glassTargetY = y;
        springX.animateToFinalPosition(x);
        springY.animateToFinalPosition(y);
        if (!glassShown) {
            glass.setAlpha(0f);
            glass.animate().alpha(1f).setDuration(160).start();
            glassShown = true;
        }

        if (advancedEffects) {
            transitionAmbient(0xFF0284C7);
        } else {
            setAmbient(0xFF0284C7);
            ambient.animate().alpha(1f).setDuration(300).start();
        }
    }

    private void focusChannelsShelf() {
        int shelfPos = adapter.getChannelsShelfPosition();
        if (shelfPos < 0) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(shelfPos);
        if (vh instanceof AppAdapter.ChannelsShelfHolder) {
            ((AppAdapter.ChannelsShelfHolder) vh).focusActiveChannel();
        } else {
            grid.smoothScrollToPosition(shelfPos);
            grid.postDelayed(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(shelfPos);
                if (h instanceof AppAdapter.ChannelsShelfHolder) {
                    ((AppAdapter.ChannelsShelfHolder) h).focusActiveChannel();
                }
            }, 100);
        }
    }

    private void tuneChannel(LcnDatabaseHelper.Channel channel) {
        if (channel == null) return;
        Toast.makeText(this, "Tuning " + channel.lcn + " " + channel.name + "...", Toast.LENGTH_SHORT).show();
        irManager.tuneChannelAsync(channel.lcn, keyCodes, activeRemoteBitLength, null);
        Intent intent = new Intent(this, TvPlayerActivity.class);
        intent.putExtra(TvPlayerActivity.EXTRA_INITIAL_LCN, channel.lcn);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openAvInput() {
        Intent intent = new Intent(this, TvPlayerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public void openRemoteSidebar() {
        Intent intent = new Intent(this, RemoteSidebarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        reloadApps();
        reloadInputs();
        loadStbChannels();
    }

    private RailCard createAppCard(AppsRowAdapter.AppItem item) {
        RailCard.ArtworkStyle style = (item.banner != null)
                ? RailCard.ArtworkStyle.BANNER_FILL
                : RailCard.ArtworkStyle.APP_ICON;
        Drawable art = (item.banner != null) ? item.banner : item.icon;

        return new RailCard(
                item.pkg,
                item.label,
                null,
                art,
                0,
                style,
                item.accentColor,
                item,
                new RailCard.ActionListener() {
                    @Override
                    public void onClick(RailCard card) {
                        onAppSelected((AppsRowAdapter.AppItem) card.payload);
                    }

                    @Override
                    public boolean onLongClick(RailCard card) {
                        AppsRowAdapter.AppItem ai = (AppsRowAdapter.AppItem) card.payload;
                        if (ai != null && !ai.pkg.isEmpty()) {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ai.pkg));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                startActivity(i);
                            } catch (ActivityNotFoundException ignored) {}
                            return true;
                        }
                        return false;
                    }
                }
        );
    }

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<AppsRowAdapter.AppItem> favs = new ArrayList<>();
        List<AppsRowAdapter.AppItem> others = new ArrayList<>();
        List<String> dock = cfg != null ? cfg.dockApps : Collections.emptyList();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hiddenApps.contains(pkg)) continue;
            ComponentName comp = new ComponentName(pkg, ri.activityInfo.name);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null && ri.activityInfo.applicationInfo != null) {
                banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            Drawable icon = ri.loadIcon(pm);
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            int accent = 0xFF444444;
            AppsRowAdapter.AppItem item = new AppsRowAdapter.AppItem(label, comp, banner, icon, accent);
            if (dock.contains(pkg)) {
                favs.add(item);
            } else {
                others.add(item);
            }
        }
        Collections.sort(favs, (a, b) -> Integer.compare(dock.indexOf(a.pkg), dock.indexOf(b.pkg)));
        Collections.sort(others, (a, b) -> a.label.compareToIgnoreCase(b.label));

        List<RailCard> favCards = new ArrayList<>();
        for (AppsRowAdapter.AppItem it : favs) {
            favCards.add(createAppCard(it));
        }
        List<RailCard> otherCards = new ArrayList<>();
        for (AppsRowAdapter.AppItem it : others) {
            otherCards.add(createAppCard(it));
        }
        if (favAppsAdapter != null) favAppsAdapter.setItems(favCards);
        if (allAppsAdapter != null) allAppsAdapter.setItems(otherCards);
    }

    private void reloadInputs() {
        List<InputsAdapter.InputItem> list = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                CharSequence label = info.loadLabel(this);
                String labelStr = label != null ? label.toString() : info.getId();
                String upper = labelStr.toUpperCase(Locale.ROOT);
                int iconRes = R.drawable.ic_input;
                int color = 0xFF10B981;
                String badge = "READY";
                if (upper.contains("AV") || info.getId().toLowerCase(Locale.ROOT).contains("composite") || info.getId().endsWith("/HW3")) {
                    labelStr = "AV1 (Kerala Vision)";
                    iconRes = R.drawable.ic_av;
                    badge = "STB";
                } else if (upper.contains("HDMI")) {
                    iconRes = R.drawable.ic_input;
                    badge = "HDMI";
                } else if (upper.contains("TUNER") || upper.contains("TV")) {
                    iconRes = R.drawable.ic_livetv;
                    badge = "TV";
                }
                list.add(new InputsAdapter.InputItem(info.getId(), labelStr, badge, iconRes, color));
            }
        }
        if (list.isEmpty()) {
            list.add(new InputsAdapter.InputItem("av1", "AV1 (Kerala Vision STB)", "STB", R.drawable.ic_av, 0xFF0284C7));
            list.add(new InputsAdapter.InputItem("hdmi1", "HDMI 1", "HDMI", R.drawable.ic_input, 0xFF10B981));
            list.add(new InputsAdapter.InputItem("hdmi2", "HDMI 2", "HDMI", R.drawable.ic_input, 0xFF10B981));
        }

        List<RailCard> inputCards = new ArrayList<>();
        for (InputsAdapter.InputItem it : list) {
            inputCards.add(new RailCard(
                    it.id,
                    it.title,
                    it.badge,
                    null,
                    it.iconRes,
                    RailCard.ArtworkStyle.ICON_CENTER,
                    it.accentColor,
                    it,
                    new RailCard.ActionListener() {
                        @Override
                        public void onClick(RailCard card) {
                            onInputSelected((InputsAdapter.InputItem) card.payload);
                        }

                        @Override
                        public boolean onLongClick(RailCard card) {
                            return false;
                        }
                    }
            ));
        }
        if (inputsAdapter != null) inputsAdapter.setItems(inputCards);
    }

    private void onAppSelected(AppsRowAdapter.AppItem item) {
        if (item.component == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(item.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot launch " + item.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void onInputSelected(InputsAdapter.InputItem item) {
        if ("av1".equals(item.id)) {
            openAvInput();
        } else {
            openInput(item.id);
        }
    }

    private void focusRail(int railIndex) {
        if (railsAdapter == null) return;
        int target = Math.max(0, Math.min(railIndex, railsAdapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(target);
        if (vh instanceof RailsAdapter.RailViewHolder) {
            ((RailsAdapter.RailViewHolder) vh).focusFirstItem();
        } else {
            grid.smoothScrollToPosition(target);
            grid.postDelayed(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(target);
                if (h instanceof RailsAdapter.RailViewHolder) {
                    ((RailsAdapter.RailViewHolder) h).focusFirstItem();
                }
            }, 100);
        }
    }

    private void onCardFocused(View card, int accentColor) {
        hideHero();
        if (advancedEffects) {
            transitionAmbient(accentColor);
        } else {
            setAmbient(accentColor);
            ambient.animate().alpha(1f).setDuration(300).start();
        }
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TvCommandServer.getInstance(this).stop();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        if (count == 0) return false;
        int shelfPos = adapter.getChannelsShelfPosition();

        if (shelfPos >= 0 && position < shelfPos) {
            // In the Dock row:
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return false; // dock row: let focus move to the top bar
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusChannelsShelf();
                return true;
            }
            return GridNavigation.atHorizontalEdge(position, shelfPos, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        } else if (shelfPos >= 0 && position > shelfPos) {
            // In the App grid below the shelf:
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return GridNavigation.atHorizontalEdge(position - (shelfPos + 1), count - (shelfPos + 1), COLUMNS,
                        rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (position <= shelfPos + COLUMNS) {
                    focusChannelsShelf();
                    return true;
                }
                int target = position - COLUMNS;
                focusTile(target);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int lastRowStart = shelfPos + 1 + (((count - 1 - (shelfPos + 1)) / COLUMNS) * COLUMNS);
                if (position >= lastRowStart) return true; // already on the last row: stay put
                int target = Math.min(position + COLUMNS, count - 1);
                focusTile(target);
                return true;
            }
        }
        return false;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Intent intent = new Intent(this, TvPlayerActivity.class);
        intent.putExtra(TvPlayerActivity.EXTRA_INPUT_ID, inputId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (240 * density));

        addSettingsMenuRow(rows, popup, "STB & Remote Pairing", () -> {
            Intent intent = new Intent(this, StbSetupActivity.class);
            intent.putExtra(StbSetupActivity.EXTRA_START_PAIRING, false);
            startActivityForResult(intent, LAUNCHER_SETTINGS_REQUEST);
        });

        addSettingsMenuRow(rows, popup, "TV Home Settings", () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));

        addSettingsMenuRow(rows, popup, "Android TV Settings", () -> open(systemIntent(Settings.ACTION_SETTINGS)));

        if (moduleCards) addSettingsMenuRow(rows, popup, getString(R.string.module_cards), this::showModuleCards);

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (240 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
