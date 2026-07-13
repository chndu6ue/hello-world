package com.nlsn.tvcompanion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TV_Companion";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String TARGET_APP_PACKAGE = "com.nlsn.psc";
    private static final long DISCOVERY_WINDOW_MS = 9000L;

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
    private final Map<String, DiscoveredDevice> discoveredDevices = new LinkedHashMap<>();

    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener castDiscoveryListener;

    private TextView statusTextView;
    private TextView deviceListTitle;
    private ProgressBar loadingSpinner;
    private Button discoverButton;
    private Button actionButton;
    private RadioGroup deviceRadioGroup;

    private volatile int scanGeneration = 0;
    private volatile boolean scanning = false;
    private String selectedDeviceKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupInterface();
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);

        if (checkDiscoveryPermissions()) {
            initiateDeviceDiscovery();
        }
    }

    private void setupInterface() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(42, 64, 42, 64);

        statusTextView = new TextView(this);
        statusTextView.setText("Checking permissions...");
        statusTextView.setTextSize(18);
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setPadding(0, 0, 0, 24);

        loadingSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        loadingSpinner.setVisibility(View.GONE);

        discoverButton = new Button(this);
        discoverButton.setText("Discover / Rescan Devices");
        discoverButton.setPadding(24, 18, 24, 18);
        discoverButton.setOnClickListener(v -> initiateDeviceDiscovery());

        deviceListTitle = new TextView(this);
        deviceListTitle.setText("Discovered devices");
        deviceListTitle.setTextSize(17);
        deviceListTitle.setPadding(0, 30, 0, 10);
        deviceListTitle.setVisibility(View.GONE);

        deviceRadioGroup = new RadioGroup(this);
        deviceRadioGroup.setOrientation(RadioGroup.VERTICAL);
        deviceRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String) {
                selectedDeviceKey = (String) checked.getTag();
                updateActionButtonLabel();
            }
        });

        actionButton = new Button(this);
        actionButton.setText("Open Play Store");
        actionButton.setEnabled(false);
        actionButton.setPadding(24, 18, 24, 18);
        actionButton.setOnClickListener(v -> executeTargetTvFlow());

        layout.addView(statusTextView);
        layout.addView(loadingSpinner);
        layout.addView(discoverButton);
        layout.addView(deviceListTitle);
        layout.addView(deviceRadioGroup);
        layout.addView(actionButton);
        scrollView.addView(layout);
        setContentView(scrollView);
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
            statusTextView.setText("Nearby-device permission is needed for reliable discovery.");
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
                    "Permission was denied. Discovery will still be attempted but may be incomplete.",
                    Toast.LENGTH_LONG).show();
        }
        initiateDeviceDiscovery();
    }

    private void initiateDeviceDiscovery() {
        int generation = ++scanGeneration;
        scanning = true;
        stopCastDiscoveryQuietly();

        synchronized (deviceLock) {
            discoveredDevices.clear();
        }
        selectedDeviceKey = null;
        renderDeviceList();

        statusTextView.setText("Searching with DIAL, SSDP/UPnP and Google Cast...");
        loadingSpinner.setVisibility(View.VISIBLE);
        discoverButton.setEnabled(false);
        actionButton.setEnabled(false);

        acquireMulticastLock();

        AtomicInteger pendingTasks = new AtomicInteger(2);
        startSsdpDiscovery(generation, pendingTasks);
        startGoogleCastDiscovery(generation, pendingTasks);
    }

    private void acquireMulticastLock() {
        releaseMulticastLock();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("TV_DISCOVERY_MULTICAST_LOCK");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void startSsdpDiscovery(int generation, AtomicInteger pendingTasks) {
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                InetAddress multicastGroup = InetAddress.getByName("239.255.255.250");
                socket = new DatagramSocket();
                socket.setSoTimeout(750);

                String[] searchTargets = new String[]{
                        "urn:dial-multiscreen-org:service:dial:1",
                        "upnp:rootdevice",
                        "ssdp:all"
                };

                for (String target : searchTargets) {
                    String query = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 3\r\n"
                            + "ST: " + target + "\r\n\r\n";
                    byte[] data = query.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket packet = new DatagramPacket(data, data.length, multicastGroup, 1900);
                    socket.send(packet);
                    Thread.sleep(120);
                    socket.send(packet);
                }

                byte[] buffer = new byte[8192];
                long startedAt = System.currentTimeMillis();
                Set<String> processedLocations = new HashSet<>();

                while (generation == scanGeneration
                        && System.currentTimeMillis() - startedAt < DISCOVERY_WINDOW_MS) {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(responsePacket);
                    } catch (java.io.InterruptedIOException timeout) {
                        continue;
                    }

                    String response = new String(
                            responsePacket.getData(),
                            0,
                            responsePacket.getLength(),
                            StandardCharsets.UTF_8);
                    Log.d(TAG, "SSDP response:\n" + response);

                    Matcher locationMatcher = LOCATION_PATTERN.matcher(response);
                    if (!response.toUpperCase(Locale.US).contains("200 OK")
                            || !locationMatcher.find()) {
                        continue;
                    }

                    String location = locationMatcher.group(1).trim();
                    if (!processedLocations.add(location)) {
                        continue;
                    }
                    processSsdpResponse(generation, response, location, responsePacket.getAddress());
                }
            } catch (Exception e) {
                Log.e(TAG, "SSDP discovery failed", e);
            } finally {
                if (socket != null) {
                    socket.close();
                }
                completeDiscoveryTask(generation, pendingTasks);
            }
        }, "ssdp-discovery").start();
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
            int port = locationUrl.getPort() >= 0
                    ? locationUrl.getPort()
                    : locationUrl.getDefaultPort();

            String name = null;
            String model = null;
            String applicationUrl = extractHeader(APPLICATION_URL_PATTERN, response);

            try {
                connection = (HttpURLConnection) locationUrl.openConnection();
                connection.setRequestMethod("GET");
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
            } catch (Exception descriptionError) {
                Log.d(TAG, "Could not read SSDP description at " + location, descriptionError);
            }

            if (name == null || name.isEmpty()) {
                name = extractHeader(SERVER_PATTERN, response);
            }
            if (name == null || name.isEmpty()) {
                name = "SSDP device";
            }
            if (model != null && !model.isEmpty()
                    && !name.toLowerCase(Locale.US).contains(model.toLowerCase(Locale.US))) {
                name = name + " — " + model;
            }

            boolean dial = response.toLowerCase(Locale.US).contains("dial")
                    || applicationUrl != null;
            if (applicationUrl == null && dial) {
                applicationUrl = locationUrl.getProtocol() + "://" + host + ":" + port + "/apps/";
            }

            addOrUpdateDevice(new DiscoveredDevice(
                    host,
                    name,
                    host,
                    port,
                    dial ? "DIAL / SSDP" : "SSDP / UPnP",
                    applicationUrl));
        } catch (Exception e) {
            Log.w(TAG, "Invalid SSDP response location: " + location, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void startGoogleCastDiscovery(int generation, AtomicInteger pendingTasks) {
        if (nsdManager == null) {
            completeDiscoveryTask(generation, pendingTasks);
            return;
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Google Cast mDNS discovery started");
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
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.d(TAG, "Cast resolve failed for "
                                    + serviceInfo.getServiceName() + ": " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            if (generation != scanGeneration || resolved.getHost() == null) {
                                return;
                            }
                            String host = resolved.getHost().getHostAddress();
                            String name = resolved.getServiceName();
                            if (name == null || name.trim().isEmpty()) {
                                name = "Google Cast device";
                            }
                            addOrUpdateDevice(new DiscoveredDevice(
                                    host,
                                    name,
                                    host,
                                    resolved.getPort(),
                                    "Google Cast / mDNS",
                                    null));
                        }
                    });
                } catch (Exception resolveError) {
                    Log.d(TAG, "Could not resolve Cast service", resolveError);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Cast service lost: " + serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                finishCastTaskOnce(generation, pendingTasks, completed);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Cast discovery start failed: " + errorCode);
                stopCastDiscoveryQuietly();
                finishCastTaskOnce(generation, pendingTasks, completed);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Cast discovery stop failed: " + errorCode);
                finishCastTaskOnce(generation, pendingTasks, completed);
            }
        };

        castDiscoveryListener = listener;
        try {
            nsdManager.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            mainHandler.postDelayed(() -> {
                if (generation == scanGeneration && castDiscoveryListener == listener) {
                    stopCastDiscoveryQuietly();
                }
                finishCastTaskOnce(generation, pendingTasks, completed);
            }, DISCOVERY_WINDOW_MS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start Cast mDNS discovery", e);
            finishCastTaskOnce(generation, pendingTasks, completed);
        }
    }

    private void finishCastTaskOnce(
            int generation,
            AtomicInteger pendingTasks,
            AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            completeDiscoveryTask(generation, pendingTasks);
        }
    }

    private void completeDiscoveryTask(int generation, AtomicInteger pendingTasks) {
        if (generation != scanGeneration) {
            return;
        }
        if (pendingTasks.decrementAndGet() != 0) {
            return;
        }

        mainHandler.post(() -> {
            if (generation != scanGeneration) {
                return;
            }
            scanning = false;
            releaseMulticastLock();
            loadingSpinner.setVisibility(View.GONE);
            discoverButton.setEnabled(true);
            actionButton.setEnabled(true);

            int count;
            synchronized (deviceLock) {
                count = discoveredDevices.size();
            }
            if (count == 0) {
                statusTextView.setText(
                        "No discoverable TV or media device responded.\n"
                                + "Confirm both devices use the same non-guest Wi-Fi, then tap Rescan.");
            } else {
                statusTextView.setText(
                        "Found " + count + " device" + (count == 1 ? "" : "s")
                                + ". Select your TV below.");
            }
            renderDeviceList();
        });
    }

    private void addOrUpdateDevice(DiscoveredDevice incoming) {
        synchronized (deviceLock) {
            DiscoveredDevice existing = discoveredDevices.get(incoming.key);
            if (existing == null) {
                discoveredDevices.put(incoming.key, incoming);
            } else {
                existing.mergeFrom(incoming);
            }
        }
        mainHandler.post(this::renderDeviceList);
    }

    private void renderDeviceList() {
        List<DiscoveredDevice> snapshot;
        synchronized (deviceLock) {
            snapshot = new ArrayList<>(discoveredDevices.values());
        }

        deviceRadioGroup.setOnCheckedChangeListener(null);
        deviceRadioGroup.removeAllViews();
        deviceListTitle.setVisibility(snapshot.isEmpty() ? View.GONE : View.VISIBLE);

        if (!snapshot.isEmpty() && selectedDeviceKey == null) {
            selectedDeviceKey = snapshot.get(0).key;
        }

        for (DiscoveredDevice device : snapshot) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setTag(device.key);
            radio.setText(device.displayText());
            radio.setTextSize(16);
            radio.setPadding(8, 14, 8, 14);
            radio.setChecked(device.key.equals(selectedDeviceKey));
            deviceRadioGroup.addView(radio);
        }

        deviceRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String) {
                selectedDeviceKey = (String) checked.getTag();
                updateActionButtonLabel();
            }
        });
        updateActionButtonLabel();
    }

    private void updateActionButtonLabel() {
        DiscoveredDevice selected = getSelectedDevice();
        if (selected == null) {
            actionButton.setText("Open Play Store");
        } else {
            actionButton.setText("Continue with " + selected.name);
        }
        actionButton.setEnabled(!scanning);
    }

    private DiscoveredDevice getSelectedDevice() {
        synchronized (deviceLock) {
            return selectedDeviceKey == null ? null : discoveredDevices.get(selectedDeviceKey);
        }
    }

    private void executeTargetTvFlow() {
        DiscoveredDevice selected = getSelectedDevice();
        if (selected != null) {
            Toast.makeText(
                    this,
                    "Selected: " + selected.name
                            + ". Google Play may still ask you to choose the TV.",
                    Toast.LENGTH_LONG).show();
        }

        try {
            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + TARGET_APP_PACKAGE));
            startActivity(intent);
        } catch (Exception playStoreError) {
            try {
                startActivity(new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id="
                                + TARGET_APP_PACKAGE)));
            } catch (Exception browserError) {
                Toast.makeText(this, "No Play Store or browser was found.", Toast.LENGTH_SHORT).show();
            }
        }

        if (selected != null && selected.dialAppsBaseUrl != null) {
            triggerPersistentTvLaunchDaemon(selected.dialAppsBaseUrl);
        }
    }

    private void triggerPersistentTvLaunchDaemon(final String appsBaseUrl) {
        new Thread(() -> {
            String normalizedBase = appsBaseUrl.endsWith("/")
                    ? appsBaseUrl
                    : appsBaseUrl + "/";

            for (int attempt = 1; attempt <= 12; attempt++) {
                HttpURLConnection connection = null;
                try {
                    Thread.sleep(5000);
                    URL url = new URL(normalizedBase + TARGET_APP_PACKAGE);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(0);
                    connection.connect();

                    int status = connection.getResponseCode();
                    Log.d(TAG, "DIAL launch attempt " + attempt + " returned " + status);
                    if (status == HttpURLConnection.HTTP_CREATED
                            || status == HttpURLConnection.HTTP_OK) {
                        Log.i(TAG, "DIAL launch command was accepted.");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "DIAL launch attempt failed: " + e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }, "dial-launch-poller").start();
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

    private static String readLimited(InputStream input, int maximumCharacters) throws Exception {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1 && value.length() < maximumCharacters) {
                int accepted = Math.min(read, maximumCharacters - value.length());
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
                Log.d(TAG, "Cast discovery was already stopped");
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

    private static final class DiscoveredDevice {
        private final String key;
        private String name;
        private String host;
        private int port;
        private String source;
        private String dialAppsBaseUrl;

        private DiscoveredDevice(
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

        private void mergeFrom(DiscoveredDevice other) {
            if ((name == null || name.startsWith("SSDP device")) && other.name != null) {
                name = other.name;
            }
            if (host == null && other.host != null) {
                host = other.host;
            }
            if (port <= 0 && other.port > 0) {
                port = other.port;
            }
            if (other.source != null && source != null && !source.contains(other.source)) {
                source = source + " + " + other.source;
            } else if (source == null) {
                source = other.source;
            }
            if (dialAppsBaseUrl == null && other.dialAppsBaseUrl != null) {
                dialAppsBaseUrl = other.dialAppsBaseUrl;
            }
        }

        private String displayText() {
            String endpoint = host == null ? "Address unavailable" : host;
            if (port > 0) {
                endpoint += ":" + port;
            }
            return name + "\n" + source + " • " + endpoint;
        }
    }
}
