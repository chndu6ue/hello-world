package com.nlsn.tvcompanion;

import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.EditText;
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
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LaunchActivity extends AppCompatActivity {

    private static final String TAG = "TV_DIAL_LAUNCHER";
    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final long DISCOVERY_WINDOW_MS = 9000L;
    private static final String TARGET_APP_PACKAGE = "com.nlsn.psc";

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
    private ProgressBar progress;
    private Button discoverButton;
    private Button launchButton;
    private RadioGroup deviceGroup;
    private EditText dialAppNameInput;

    private volatile int scanGeneration = 0;
    private volatile boolean scanning = false;
    private String selectedDeviceKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        setupUi();

        if (checkDiscoveryPermissions()) {
            discoverDevices();
        }
    }

    private void setupUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(40, 48, 40, 48);

        statusText = new TextView(this);
        statusText.setText("Preparing discovery...");
        statusText.setTextSize(18);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 0, 0, 20);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        progress.setVisibility(View.GONE);

        discoverButton = new Button(this);
        discoverButton.setText("Discover / Rescan Devices");
        discoverButton.setOnClickListener(v -> discoverDevices());

        TextView title = new TextView(this);
        title.setText("Discovered devices");
        title.setTextSize(18);
        title.setPadding(0, 26, 0, 8);

        deviceGroup = new RadioGroup(this);
        deviceGroup.setOrientation(RadioGroup.VERTICAL);
        deviceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String) {
                selectedDeviceKey = (String) checked.getTag();
                updateLaunchButton();
            }
        });

        TextView appNameLabel = new TextView(this);
        appNameLabel.setText("DIAL application name");
        appNameLabel.setTextSize(16);
        appNameLabel.setPadding(0, 24, 0, 6);

        dialAppNameInput = new EditText(this);
        dialAppNameInput.setSingleLine(true);
        dialAppNameInput.setText(TARGET_APP_PACKAGE);
        dialAppNameInput.setHint("Example: YouTube or vendor-defined app name");

        launchButton = new Button(this);
        launchButton.setText("Launch Installed TV App");
        launchButton.setEnabled(false);
        launchButton.setOnClickListener(v -> launchSelectedDevice());

        root.addView(statusText);
        root.addView(progress);
        root.addView(discoverButton);
        root.addView(title);
        root.addView(deviceGroup);
        root.addView(appNameLabel);
        root.addView(dialAppNameInput);
        root.addView(launchButton);
        scrollView.addView(root);
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
            Toast.makeText(this,
                    "Nearby-device permission was denied. Discovery may be incomplete.",
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

        statusText.setText("Searching with DIAL, SSDP/UPnP and Google Cast...");
        progress.setVisibility(View.VISIBLE);
        discoverButton.setEnabled(false);
        launchButton.setEnabled(false);
        acquireMulticastLock();

        AtomicInteger pending = new AtomicInteger(2);
        startSsdpDiscovery(generation, pending);
        startCastDiscovery(generation, pending);
    }

    private void acquireMulticastLock() {
        releaseMulticastLock();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("TV_DIAL_DISCOVERY");
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

                    String response = new String(packet.getData(), 0, packet.getLength(),
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
        }, "dial-ssdp-discovery").start();
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
                name = server == null ? "SSDP device" : server;
            }
            if (model != null && !model.isEmpty()
                    && !name.toLowerCase(Locale.US).contains(model.toLowerCase(Locale.US))) {
                name = name + " — " + model;
            }

            boolean looksLikeCast = (server != null
                    && server.toLowerCase(Locale.US).contains("chromecast"))
                    || locationPort == 8008;
            boolean looksLikeDial = response.toLowerCase(Locale.US).contains("dial")
                    || applicationUrl != null;

            if (applicationUrl == null && (looksLikeCast || looksLikeDial)) {
                applicationUrl = "http://" + host + ":8008/apps/";
            }

            addOrUpdateDevice(new Device(
                    host,
                    name,
                    host,
                    looksLikeCast ? 8008 : locationPort,
                    looksLikeDial ? "DIAL / SSDP" : "Chromecast / SSDP",
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
                            String name = resolved.getServiceName();
                            if (name == null || name.trim().isEmpty()) {
                                name = "Google Cast device";
                            }
                            addOrUpdateDevice(new Device(
                                    host,
                                    name,
                                    host,
                                    8008,
                                    "Google Cast / mDNS",
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
            nsdManager.discoverServices("_googlecast._tcp.",
                    NsdManager.PROTOCOL_DNS_SD,
                    listener);
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
            statusText.setText(count == 0
                    ? "No TV responded. Confirm both devices are on the same non-guest Wi-Fi."
                    : "Found " + count + " device" + (count == 1 ? "" : "s")
                    + ". Select the TV and launch the installed app.");
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
            radio.setText(device.displayText());
            radio.setTextSize(16);
            radio.setPadding(8, 12, 8, 12);
            radio.setChecked(device.key.equals(selectedDeviceKey));
            deviceGroup.addView(radio);
        }

        deviceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String) {
                selectedDeviceKey = (String) checked.getTag();
                updateLaunchButton();
            }
        });
        updateLaunchButton();
    }

    private void updateLaunchButton() {
        Device selected = getSelectedDevice();
        launchButton.setText(selected == null
                ? "Launch Installed TV App"
                : "Launch on " + selected.name);
        launchButton.setEnabled(!scanning && selected != null);
    }

    private Device getSelectedDevice() {
        synchronized (deviceLock) {
            return selectedDeviceKey == null ? null : devices.get(selectedDeviceKey);
        }
    }

    private void launchSelectedDevice() {
        Device selected = getSelectedDevice();
        if (selected == null) {
            Toast.makeText(this, "Select a TV first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String baseUrl = selected.dialAppsBaseUrl;
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "http://" + selected.host + ":8008/apps/";
        }

        LinkedHashSet<String> candidateNames = new LinkedHashSet<>();
        String customName = dialAppNameInput.getText().toString().trim();
        if (!customName.isEmpty()) {
            candidateNames.add(customName);
        }
        candidateNames.add(TARGET_APP_PACKAGE);
        candidateNames.add("ConfluenceTV");
        candidateNames.add("Confluence");

        launchButton.setEnabled(false);
        statusText.setText("Testing DIAL endpoint on " + selected.name + "...");
        launchByDial(baseUrl, new ArrayList<>(candidateNames));
    }

    private void launchByDial(String appsBaseUrl, List<String> candidates) {
        new Thread(() -> {
            String normalizedBase = appsBaseUrl.endsWith("/")
                    ? appsBaseUrl
                    : appsBaseUrl + "/";
            List<String> attempts = new ArrayList<>();

            for (String appName : candidates) {
                String encodedName = appName.replace(" ", "%20");
                String endpoint = normalizedBase + encodedName;
                int getCode = performRequest(endpoint, "GET");
                int postCode = performRequest(endpoint, "POST");
                attempts.add(appName + ": GET " + getCode + ", POST " + postCode);

                if (postCode == HttpURLConnection.HTTP_OK
                        || postCode == HttpURLConnection.HTTP_CREATED
                        || postCode == HttpURLConnection.HTTP_ACCEPTED) {
                    mainHandler.post(() -> {
                        statusText.setText("Launch accepted by TV using DIAL name ‘"
                                + appName + "’ (HTTP " + postCode + ").");
                        launchButton.setEnabled(true);
                    });
                    return;
                }
            }

            StringBuilder message = new StringBuilder();
            message.append("The TV answered, but none of the tested DIAL names were launchable.\n\n");
            for (String attempt : attempts) {
                message.append(attempt).append('\n');
            }
            message.append("\nHTTP 404 usually means that name is not registered in the TV’s DIAL server.");

            mainHandler.post(() -> {
                statusText.setText(message.toString());
                launchButton.setEnabled(true);
            });
        }, "dial-launch").start();
    }

    private int performRequest(String endpoint, String method) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setRequestProperty("Connection", "close");

            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                connection.setFixedLengthStreamingMode(0);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(new byte[0]);
                }
            }

            int code = connection.getResponseCode();
            Log.d(TAG, method + " " + endpoint + " -> " + code);
            return code;
        } catch (Exception e) {
            Log.w(TAG, method + " " + endpoint + " failed", e);
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
            if ((name == null || name.startsWith("SSDP device")) && other.name != null) {
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
            String endpoint = host == null ? "Address unavailable" : host;
            if (port > 0) {
                endpoint += ":" + port;
            }
            return name + "\n" + source + " • " + endpoint;
        }
    }
}
