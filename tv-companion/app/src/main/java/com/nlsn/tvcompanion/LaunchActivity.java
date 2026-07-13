package com.nlsn.tvcompanion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LaunchActivity extends AppCompatActivity {

    private static final String TAG = "TV_INSTALL_ASSISTANT";
    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final long DISCOVERY_WINDOW_MS = 9000L;
    private static final String PLAY_SEARCH_QUERY = "confluencetv";

    private static final int COLOR_NAVY = Color.rgb(15, 42, 68);
    private static final int COLOR_BLUE = Color.rgb(32, 103, 227);
    private static final int COLOR_BLUE_DARK = Color.rgb(21, 78, 170);
    private static final int COLOR_LIGHT_BLUE = Color.rgb(235, 243, 255);
    private static final int COLOR_BACKGROUND = Color.rgb(246, 248, 252);
    private static final int COLOR_TEXT = Color.rgb(31, 41, 55);
    private static final int COLOR_MUTED = Color.rgb(102, 112, 133);
    private static final int COLOR_BORDER = Color.rgb(208, 213, 221);

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?im)^LOCATION:\\s*(https?://[^\\r\\n]+)");
    private static final Pattern APPLICATION_URL_PATTERN = Pattern.compile(
            "(?im)^APPLICATION-URL:\\s*(https?://[^\\r\\n]+)");
    private static final Pattern SERVER_PATTERN = Pattern.compile(
            "(?im)^SERVER:\\s*([^\\r\\n]+)");
    private static final Pattern FRIENDLY_NAME_PATTERN = Pattern.compile(
            "(?is)<friendlyName[^>]*>(.*?)</friendlyName>");
    private static final Pattern MODEL_NAME_PATTERN = Pattern.compile(
            "(?is)<modelName[^>]*>(.*?)</modelName>");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object deviceLock = new Object();
    private final Map<String, Device> devices = new LinkedHashMap<>();

    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener castDiscoveryListener;

    private TextView statusText;
    private TextView emptyStateText;
    private ProgressBar progress;
    private Button discoverButton;
    private Button installButton;
    private Button installedButton;
    private RadioGroup deviceGroup;

    private volatile int scanGeneration = 0;
    private volatile boolean scanning = false;
    private String selectedDeviceKey;
    private boolean playStoreOpened = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);

        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        setupUi();

        if (checkDiscoveryPermissions()) {
            discoverDevices();
        }
    }

    private void setupUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(30), dp(24), dp(28));
        header.setBackgroundColor(COLOR_NAVY);

        TextView brand = text("NIELSEN STREAMING PANEL", 12, Color.rgb(183, 211, 255));
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand);

        TextView title = text("Install ConfluenceTV", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, 0);
        header.addView(title);

        TextView subtitle = text(
                "Find your television, then continue in Google Play with the search already prepared.",
                16,
                Color.rgb(225, 234, 246));
        subtitle.setLineSpacing(0, 1.15f);
        subtitle.setPadding(0, dp(10), 0, 0);
        header.addView(subtitle);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(32));

        LinearLayout statusCard = card(COLOR_LIGHT_BLUE, COLOR_LIGHT_BLUE);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminate(true);
        statusCard.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));

        statusText = text("Preparing TV discovery…", 15, COLOR_TEXT);
        statusText.setPadding(dp(12), 0, 0, 0);
        statusText.setLineSpacing(0, 1.12f);
        statusCard.addView(statusText, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));

        content.addView(statusCard, spacedParams(0, 0, 0, 22));

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout sectionText = new LinearLayout(this);
        sectionText.setOrientation(LinearLayout.VERTICAL);

        TextView chooseTitle = text("1. Choose your TV", 20, COLOR_TEXT);
        chooseTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sectionText.addView(chooseTitle);

        TextView chooseSubtitle = text("Keep the phone and TV on the same Wi-Fi.", 14, COLOR_MUTED);
        chooseSubtitle.setPadding(0, dp(3), 0, 0);
        sectionText.addView(chooseSubtitle);

        sectionHeader.addView(sectionText, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));

        discoverButton = new Button(this);
        discoverButton.setAllCaps(false);
        discoverButton.setText("Scan again");
        discoverButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        discoverButton.setTextColor(COLOR_BLUE);
        discoverButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        discoverButton.setBackground(rounded(Color.WHITE, 14, COLOR_BORDER));
        discoverButton.setMinHeight(dp(44));
        discoverButton.setPadding(dp(15), 0, dp(15), 0);
        discoverButton.setOnClickListener(v -> {
            if (checkDiscoveryPermissions()) {
                discoverDevices();
            }
        });
        sectionHeader.addView(discoverButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)));

        content.addView(sectionHeader);

        deviceGroup = new RadioGroup(this);
        deviceGroup.setOrientation(RadioGroup.VERTICAL);
        deviceGroup.setPadding(0, dp(12), 0, 0);
        deviceGroup.setOnCheckedChangeListener((group, checkedId) -> onDeviceSelected(group, checkedId));
        content.addView(deviceGroup, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        emptyStateText = text(
                "No television selected yet. Discovery may take a few seconds.",
                14,
                COLOR_MUTED);
        emptyStateText.setGravity(Gravity.CENTER);
        emptyStateText.setPadding(dp(16), dp(18), dp(16), dp(18));
        emptyStateText.setBackground(rounded(Color.WHITE, 16, COLOR_BORDER));
        content.addView(emptyStateText, spacedParams(0, 12, 0, 22));

        LinearLayout guideCard = card(Color.WHITE, COLOR_BORDER);
        guideCard.setOrientation(LinearLayout.VERTICAL);
        guideCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView installTitle = text("2. Install from Google Play", 20, COLOR_TEXT);
        installTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        guideCard.addView(installTitle);

        TextView installSubtitle = text(
                "We will search for ConfluenceTV automatically. Google Play controls the final device selection.",
                14,
                COLOR_MUTED);
        installSubtitle.setLineSpacing(0, 1.12f);
        installSubtitle.setPadding(0, dp(6), 0, dp(14));
        guideCard.addView(installSubtitle);

        guideCard.addView(step("1", "Tap the Android TV filter in Google Play."));
        guideCard.addView(step("2", "Open ConfluenceTV by Nielsen Streaming Panel."));
        guideCard.addView(step("3", "Tap Install, choose your TV, and confirm."));

        content.addView(guideCard, spacedParams(0, 4, 0, 18));

        installButton = new Button(this);
        installButton.setAllCaps(false);
        installButton.setText("Find ConfluenceTV in Google Play");
        installButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        installButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        installButton.setTextColor(Color.WHITE);
        installButton.setBackground(rounded(COLOR_BLUE, 16, COLOR_BLUE));
        installButton.setMinHeight(dp(56));
        installButton.setEnabled(false);
        installButton.setOnClickListener(v -> confirmAndOpenPlayStore());
        content.addView(installButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)));

        installedButton = new Button(this);
        installedButton.setAllCaps(false);
        installedButton.setText("I installed it — what next?");
        installedButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        installedButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        installedButton.setTextColor(COLOR_BLUE_DARK);
        installedButton.setBackground(rounded(Color.WHITE, 16, COLOR_BORDER));
        installedButton.setMinHeight(dp(52));
        installedButton.setVisibility(View.GONE);
        installedButton.setOnClickListener(v -> showAfterInstallDialog());
        content.addView(installedButton, spacedParams(0, 12, 0, 0));

        TextView privacy = text(
                "The assistant only uses local-network discovery to identify nearby TV devices. Installation is completed securely by Google Play.",
                12,
                COLOR_MUTED);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(0, 1.12f);
        content.addView(privacy, spacedParams(12, 18, 12, 0));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private LinearLayout step(String number, String copy) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView badge = text(number, 13, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setBackground(rounded(COLOR_BLUE, 18, COLOR_BLUE));
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView body = text(copy, 15, COLOR_TEXT);
        body.setPadding(dp(12), dp(3), 0, 0);
        body.setLineSpacing(0, 1.1f);
        row.addView(body, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));
        return row;
    }

    private boolean checkDiscoveryPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!missing.isEmpty()) {
            statusText.setText("Allow nearby-device access so the assistant can find your TV.");
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted = granted && result == PackageManager.PERMISSION_GRANTED;
        }
        if (!granted) {
            Toast.makeText(
                    this,
                    "Permission was denied. You can still continue to Google Play, but TV discovery may be incomplete.",
                    Toast.LENGTH_LONG).show();
        }
        discoverDevices();
    }

    private void discoverDevices() {
        final int generation = ++scanGeneration;
        scanning = true;
        stopCastDiscoveryQuietly();

        synchronized (deviceLock) {
            devices.clear();
        }
        selectedDeviceKey = null;
        renderDeviceList();

        statusText.setText("Looking for Android TVs and Google TV devices on your Wi-Fi…");
        progress.setVisibility(View.VISIBLE);
        discoverButton.setEnabled(false);
        installButton.setEnabled(false);
        acquireMulticastLock();

        AtomicInteger pending = new AtomicInteger(2);
        startSsdpDiscovery(generation, pending);
        startCastDiscovery(generation, pending);
    }

    private void acquireMulticastLock() {
        releaseMulticastLock();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("TV_INSTALL_DISCOVERY");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void startSsdpDiscovery(int generation, AtomicInteger pending) {
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                InetAddress group = InetAddress.getByName("239.255.255.250");
                socket = new DatagramSocket();
                socket.setSoTimeout(750);

                String[] targets = {
                        "urn:dial-multiscreen-org:service:dial:1",
                        "upnp:rootdevice",
                        "ssdp:all"
                };
                for (String target : targets) {
                    String query = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 3\r\n"
                            + "ST: " + target + "\r\n\r\n";
                    byte[] data = query.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket packet = new DatagramPacket(data, data.length, group, 1900);
                    socket.send(packet);
                    Thread.sleep(120);
                    socket.send(packet);
                }

                byte[] buffer = new byte[8192];
                long startedAt = System.currentTimeMillis();
                Set<String> processed = new HashSet<>();
                while (generation == scanGeneration
                        && System.currentTimeMillis() - startedAt < DISCOVERY_WINDOW_MS) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.io.InterruptedIOException timeout) {
                        continue;
                    }

                    String response = new String(
                            packet.getData(),
                            0,
                            packet.getLength(),
                            StandardCharsets.UTF_8);
                    Matcher locationMatcher = LOCATION_PATTERN.matcher(response);
                    if (!response.toUpperCase(Locale.US).contains("200 OK")
                            || !locationMatcher.find()) {
                        continue;
                    }

                    String location = locationMatcher.group(1).trim();
                    String uniqueness = packet.getAddress().getHostAddress() + "|" + location;
                    if (processed.add(uniqueness)) {
                        processSsdpResponse(generation, response, location, packet.getAddress());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "SSDP discovery failed", e);
            } finally {
                if (socket != null) {
                    socket.close();
                }
                completeDiscoveryTask(generation, pending);
            }
        }, "tv-ssdp-discovery").start();
    }

    private void processSsdpResponse(
            int generation,
            String response,
            String location,
            InetAddress packetAddress) {
        if (generation != scanGeneration) {
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL locationUrl = new URL(location);
            String host = locationUrl.getHost();
            if (host == null || host.isEmpty()) {
                host = packetAddress.getHostAddress();
            }
            int locationPort = locationUrl.getPort() >= 0
                    ? locationUrl.getPort()
                    : locationUrl.getDefaultPort();

            String server = extractHeader(SERVER_PATTERN, response);
            String applicationUrl = extractHeader(APPLICATION_URL_PATTERN, response);
            String name = null;
            String model = null;

            try {
                connection = (HttpURLConnection) locationUrl.openConnection();
                connection.setConnectTimeout(1800);
                connection.setReadTimeout(1800);
                connection.setRequestProperty("Connection", "close");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 400) {
                    String headerApplicationUrl = connection.getHeaderField("Application-URL");
                    if (headerApplicationUrl != null && !headerApplicationUrl.trim().isEmpty()) {
                        applicationUrl = headerApplicationUrl.trim();
                    }
                    String xml = readLimited(connection.getInputStream(), 65536);
                    name = extractXmlValue(FRIENDLY_NAME_PATTERN, xml);
                    model = extractXmlValue(MODEL_NAME_PATTERN, xml);
                }
            } catch (Exception ignored) {
                Log.d(TAG, "Could not read device description: " + location);
            }

            if (name == null || name.isEmpty()) {
                name = model;
            }
            if (name == null || name.isEmpty()) {
                name = server == null ? "Android TV device" : server;
            }

            boolean dial = response.toLowerCase(Locale.US).contains("dial")
                    || applicationUrl != null;
            boolean cast = (server != null
                    && server.toLowerCase(Locale.US).contains("chromecast"))
                    || locationPort == 8008;

            if (!isLikelyTv(name, model, dial, cast)) {
                return;
            }

            addOrUpdateDevice(new Device(
                    host,
                    cleanDeviceName(name, model),
                    host,
                    cast ? 8008 : locationPort,
                    dial ? "Android TV / DIAL" : "Google Cast TV",
                    applicationUrl));
        } catch (Exception e) {
            Log.w(TAG, "Invalid SSDP response", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void startCastDiscovery(int generation, AtomicInteger pending) {
        if (nsdManager == null) {
            completeDiscoveryTask(generation, pending);
            return;
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Cast discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (generation != scanGeneration
                        || serviceInfo.getServiceType() == null
                        || !serviceInfo.getServiceType().toLowerCase(Locale.US).contains("googlecast")) {
                    return;
                }

                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            Log.d(TAG, "Cast resolve failed: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            if (generation != scanGeneration || resolved.getHost() == null) {
                                return;
                            }

                            String host = resolved.getHost().getHostAddress();
                            String friendlyName = readTxtAttribute(resolved, "fn");
                            String model = readTxtAttribute(resolved, "md");
                            String name = firstNonBlank(
                                    friendlyName,
                                    resolved.getServiceName(),
                                    model,
                                    "Google Cast TV");

                            if (!isLikelyTv(name, model, false, true)) {
                                return;
                            }

                            addOrUpdateDevice(new Device(
                                    host,
                                    cleanDeviceName(name, model),
                                    host,
                                    resolved.getPort() > 0 ? resolved.getPort() : 8008,
                                    "Google Cast TV",
                                    "http://" + host + ":8008/apps/"));
                        }
                    });
                } catch (Exception e) {
                    Log.d(TAG, "Could not resolve Cast service", e);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Cast service lost: " + serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                finishCastTaskOnce(generation, pending, completed);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Cast discovery start failed: " + errorCode);
                stopCastDiscoveryQuietly();
                finishCastTaskOnce(generation, pending, completed);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Cast discovery stop failed: " + errorCode);
                finishCastTaskOnce(generation, pending, completed);
            }
        };

        castDiscoveryListener = listener;
        try {
            nsdManager.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            mainHandler.postDelayed(() -> {
                if (generation == scanGeneration && castDiscoveryListener == listener) {
                    stopCastDiscoveryQuietly();
                }
                finishCastTaskOnce(generation, pending, completed);
            }, DISCOVERY_WINDOW_MS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start Cast discovery", e);
            finishCastTaskOnce(generation, pending, completed);
        }
    }

    private static String readTxtAttribute(NsdServiceInfo info, String key) {
        try {
            Map<String, byte[]> attributes = info.getAttributes();
            byte[] value = attributes.get(key);
            if (value == null || value.length == 0) {
                return null;
            }
            return new String(value, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isLikelyTv(String name, String model, boolean dial, boolean cast) {
        if (dial) {
            return true;
        }

        String haystack = ((name == null ? "" : name) + " "
                + (model == null ? "" : model)).toLowerCase(Locale.US);

        String[] audioOnly = {
                "home mini", "nest mini", "nest audio", "speaker", "soundbar", "audio"
        };
        for (String token : audioOnly) {
            if (haystack.contains(token)) {
                return false;
            }
        }

        String[] tvSignals = {
                "tv", "bravia", "mitv", "mi box", "android", "google tv",
                "chromecast", "shield", "binge", "tata sky", "fire"
        };
        for (String token : tvSignals) {
            if (haystack.contains(token)) {
                return true;
            }
        }

        return cast && !haystack.contains("home");
    }

    private static String cleanDeviceName(String name, String model) {
        String value = firstNonBlank(name, model, "Android TV");
        if (value == null) {
            return "Android TV";
        }

        value = value.replaceAll("[_-]{8,}[A-Fa-f0-9]{8,}$", "");
        value = value.replaceAll("\\s+", " ").trim();

        if (model != null
                && !model.trim().isEmpty()
                && !value.toLowerCase(Locale.US).contains(model.toLowerCase(Locale.US))) {
            value = value + " — " + model.trim();
        }

        if (value.length() > 54) {
            value = value.substring(0, 51).trim() + "…";
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private void finishCastTaskOnce(
            int generation,
            AtomicInteger pending,
            AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            completeDiscoveryTask(generation, pending);
        }
    }

    private void completeDiscoveryTask(int generation, AtomicInteger pending) {
        if (generation != scanGeneration || pending.decrementAndGet() != 0) {
            return;
        }

        mainHandler.post(() -> {
            if (generation != scanGeneration) {
                return;
            }

            scanning = false;
            releaseMulticastLock();
            progress.setVisibility(View.GONE);
            discoverButton.setEnabled(true);
            renderDeviceList();

            int count;
            synchronized (deviceLock) {
                count = devices.size();
            }

            if (count == 0) {
                statusText.setText("No TV responded. You can rescan or continue to Google Play manually.");
            } else {
                statusText.setText(
                        "Found " + count + " television"
                                + (count == 1 ? "" : "s")
                                + ". Select the one you want to install on.");
            }
            installButton.setEnabled(true);
        });
    }

    private void addOrUpdateDevice(Device incoming) {
        synchronized (deviceLock) {
            Device existing = devices.get(incoming.key);
            if (existing == null) {
                devices.put(incoming.key, incoming);
            } else {
                existing.mergeFrom(incoming);
            }
        }
        mainHandler.post(this::renderDeviceList);
    }

    private void renderDeviceList() {
        List<Device> snapshot;
        synchronized (deviceLock) {
            snapshot = new ArrayList<>(devices.values());
        }

        deviceGroup.setOnCheckedChangeListener(null);
        deviceGroup.removeAllViews();

        if (!snapshot.isEmpty() && selectedDeviceKey == null) {
            selectedDeviceKey = snapshot.get(0).key;
        }

        for (Device device : snapshot) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setTag(device.key);
            radio.setButtonTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{COLOR_BLUE, COLOR_MUTED}));
            radio.setText(device.displayText());
            radio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            radio.setTextColor(COLOR_TEXT);
            radio.setGravity(Gravity.CENTER_VERTICAL);
            radio.setPadding(dp(14), dp(14), dp(14), dp(14));
            radio.setMinHeight(dp(72));
            radio.setChecked(device.key.equals(selectedDeviceKey));
            radio.setBackground(rounded(
                    radio.isChecked() ? COLOR_LIGHT_BLUE : Color.WHITE,
                    16,
                    radio.isChecked() ? COLOR_BLUE : COLOR_BORDER));

            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(10));
            deviceGroup.addView(radio, params);
        }

        emptyStateText.setVisibility(snapshot.isEmpty() ? View.VISIBLE : View.GONE);
        deviceGroup.setVisibility(snapshot.isEmpty() ? View.GONE : View.VISIBLE);

        deviceGroup.setOnCheckedChangeListener((group, checkedId) ->
                onDeviceSelected(group, checkedId));

        refreshDeviceStyles();
        updateInstallButtonLabel();
    }

    private void onDeviceSelected(RadioGroup group, int checkedId) {
        View checked = group.findViewById(checkedId);
        if (checked != null && checked.getTag() instanceof String) {
            selectedDeviceKey = (String) checked.getTag();
            refreshDeviceStyles();
            updateInstallButtonLabel();
        }
    }

    private void refreshDeviceStyles() {
        for (int i = 0; i < deviceGroup.getChildCount(); i++) {
            View child = deviceGroup.getChildAt(i);
            if (child instanceof RadioButton) {
                RadioButton radio = (RadioButton) child;
                radio.setBackground(rounded(
                        radio.isChecked() ? COLOR_LIGHT_BLUE : Color.WHITE,
                        16,
                        radio.isChecked() ? COLOR_BLUE : COLOR_BORDER));
            }
        }
    }

    private void updateInstallButtonLabel() {
        Device selected = getSelectedDevice();
        if (selected == null) {
            installButton.setText("Find ConfluenceTV in Google Play");
        } else {
            installButton.setText("Install on " + selected.shortName());
        }
        installButton.setEnabled(!scanning);
    }

    private Device getSelectedDevice() {
        synchronized (deviceLock) {
            return selectedDeviceKey == null ? null : devices.get(selectedDeviceKey);
        }
    }

    private void confirmAndOpenPlayStore() {
        Device selected = getSelectedDevice();
        String destination = selected == null ? "your Android TV" : selected.shortName();

        String message = "Google Play will open with “ConfluenceTV” already searched.\n\n"
                + "1. Tap the Android TV filter.\n"
                + "2. Open ConfluenceTV by Nielsen Streaming Panel.\n"
                + "3. Tap Install and select " + destination + ".\n\n"
                + "Your TV must use the same Google account and be compatible with the app.";

        new AlertDialog.Builder(this)
                .setTitle("Ready to install")
                .setMessage(message)
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open Google Play", (dialog, which) -> openPlayStoreSearch())
                .show();
    }

    private void openPlayStoreSearch() {
        Uri marketUri = Uri.parse(
                "market://search?q=" + Uri.encode(PLAY_SEARCH_QUERY) + "&c=apps");
        Intent playIntent = new Intent(Intent.ACTION_VIEW, marketUri);
        playIntent.setPackage("com.android.vending");
        playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(playIntent);
            playStoreOpened = true;
        } catch (Exception playStoreError) {
            Uri webUri = Uri.parse(
                    "https://play.google.com/store/search?q="
                            + Uri.encode(PLAY_SEARCH_QUERY)
                            + "&c=apps");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, webUri));
                playStoreOpened = true;
            } catch (Exception browserError) {
                Toast.makeText(
                        this,
                        "Google Play or a web browser could not be opened.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showAfterInstallDialog() {
        Device selected = getSelectedDevice();
        String destination = selected == null ? "the TV" : selected.shortName();

        new AlertDialog.Builder(this)
                .setTitle("Finish on your TV")
                .setMessage(
                        "Open ConfluenceTV from the Apps screen on " + destination + ".\n\n"
                                + "Complete any sign-in or pairing shown by the TV app. "
                                + "Use the TV app’s first launch—not network discovery alone—as the installation confirmation.")
                .setPositiveButton("Got it", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playStoreOpened && installedButton != null) {
            installedButton.setVisibility(View.VISIBLE);
            statusText.setText(
                    "Google Play was opened. After installation, return here for the final TV steps.");
        }
    }

    private static String extractHeader(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String extractXmlValue(Pattern pattern, String xml) {
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String readLimited(InputStream input, int maxCharacters) throws Exception {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1 && value.length() < maxCharacters) {
                int accepted = Math.min(read, maxCharacters - value.length());
                value.append(buffer, 0, accepted);
            }
        }
        return value.toString();
    }

    private void stopCastDiscoveryQuietly() {
        NsdManager.DiscoveryListener listener = castDiscoveryListener;
        castDiscoveryListener = null;
        if (nsdManager != null && listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
                Log.d(TAG, "Cast discovery already stopped");
            }
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    @Override
    protected void onDestroy() {
        ++scanGeneration;
        stopCastDiscoveryQuietly();
        releaseMulticastLock();
        super.onDestroy();
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        textView.setTextColor(color);
        return textView;
    }

    private LinearLayout card(int fillColor, int strokeColor) {
        LinearLayout layout = new LinearLayout(this);
        layout.setBackground(rounded(fillColor, 18, strokeColor));
        return layout;
    }

    private GradientDrawable rounded(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams spacedParams(
            int left,
            int top,
            int right,
            int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Device {
        private final String key;
        private String name;
        private String host;
        private int port;
        private String source;
        private String dialAppsBaseUrl;

        private Device(
                String key,
                String name,
                String host,
                int port,
                String source,
                String dialAppsBaseUrl) {
            this.key = key;
            this.name = name;
            this.host = host;
            this.port = port;
            this.source = source;
            this.dialAppsBaseUrl = dialAppsBaseUrl;
        }

        private void mergeFrom(Device other) {
            if ((name == null
                    || name.startsWith("Android TV")
                    || name.startsWith("Google Cast"))
                    && other.name != null) {
                name = other.name;
            }
            if (host == null && other.host != null) {
                host = other.host;
            }
            if (other.port > 0) {
                port = other.port;
            }
            if (source == null) {
                source = other.source;
            } else if (other.source != null && !source.contains(other.source)) {
                source = source + " + " + other.source;
            }
            if (dialAppsBaseUrl == null && other.dialAppsBaseUrl != null) {
                dialAppsBaseUrl = other.dialAppsBaseUrl;
            }
        }

        private String displayText() {
            return shortName() + "\nTV detected on this Wi-Fi";
        }

        private String shortName() {
            String value = name == null || name.trim().isEmpty() ? "Android TV" : name.trim();
            if (value.length() > 34) {
                return value.substring(0, 31).trim() + "…";
            }
            return value;
        }
    }
}
