package com.nlsn.tvcompanion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TV_Companion";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String TARGET_APP_PACKAGE = "com.nlsn.psc";

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?im)^LOCATION:\\s*(https?://[^\\r\\n]+)");
    private static final Pattern APPLICATION_URL_PATTERN = Pattern.compile(
            "(?im)^APPLICATION-URL:\\s*(https?://[^\\r\\n]+)");

    private WifiManager.MulticastLock multicastLock;
    private volatile String discoveredTvHost;
    private volatile String discoveredDialAppsBaseUrl;

    private TextView statusTextView;
    private ProgressBar loadingSpinner;
    private Button actionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupInterface();

        if (checkLocationPermissions()) {
            initiateTvDiscovery();
        }
    }

    private void setupInterface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);

        statusTextView = new TextView(this);
        statusTextView.setText("Checking permissions...");
        statusTextView.setTextSize(18);
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setPadding(0, 0, 0, 40);

        loadingSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        loadingSpinner.setVisibility(View.GONE);

        actionButton = new Button(this);
        actionButton.setText("Send App to Smart TV");
        actionButton.setEnabled(false);
        actionButton.setPadding(30, 20, 30, 20);

        layout.addView(statusTextView);
        layout.addView(loadingSpinner);
        layout.addView(actionButton);
        setContentView(layout);

        actionButton.setOnClickListener(v -> executeTargetTvFlow());
    }

    private boolean checkLocationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            statusTextView.setText("Permission required to scan the local Wi-Fi network.");
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
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

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initiateTvDiscovery();
        } else {
            statusTextView.setText("Permission denied. Cannot scan for nearby Smart TVs.");
            Toast.makeText(
                    this,
                    "Local network scanning requires location permission in this test build.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void initiateTvDiscovery() {
        statusTextView.setText("Searching for Smart TVs on your Wi-Fi...");
        loadingSpinner.setVisibility(View.VISIBLE);
        actionButton.setEnabled(false);

        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("DIAL_SSDP_LOCK");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        startSsdpScanLoop();
    }

    private void startSsdpScanLoop() {
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                InetAddress multicastGroup = InetAddress.getByName("239.255.255.250");
                socket = new DatagramSocket();
                socket.setSoTimeout(1000);

                String ssdpQuery = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 3\r\n"
                        + "ST: urn:dial-multicast:service:dial:1\r\n\r\n";

                byte[] sendData = ssdpQuery.getBytes(StandardCharsets.UTF_8);
                DatagramPacket sendPacket = new DatagramPacket(
                        sendData,
                        sendData.length,
                        multicastGroup,
                        1900);

                for (int i = 0; i < 3; i++) {
                    socket.send(sendPacket);
                    Thread.sleep(150);
                }

                byte[] receiveData = new byte[4096];
                long searchStartTime = System.currentTimeMillis();

                while (System.currentTimeMillis() - searchStartTime < 7000) {
                    DatagramPacket receivePacket = new DatagramPacket(
                            receiveData,
                            receiveData.length);
                    try {
                        socket.receive(receivePacket);
                    } catch (java.io.InterruptedIOException timeout) {
                        continue;
                    }

                    String response = new String(
                            receivePacket.getData(),
                            0,
                            receivePacket.getLength(),
                            StandardCharsets.UTF_8);
                    Log.d(TAG, "SSDP response:\n" + response);

                    if (!response.toUpperCase().contains("200 OK")) {
                        continue;
                    }

                    Matcher locationMatcher = LOCATION_PATTERN.matcher(response);
                    if (!locationMatcher.find()) {
                        continue;
                    }

                    URL locationUrl = new URL(locationMatcher.group(1).trim());
                    int port = locationUrl.getPort() >= 0
                            ? locationUrl.getPort()
                            : locationUrl.getDefaultPort();
                    discoveredTvHost = locationUrl.getHost() + ":" + port;

                    Matcher applicationUrlMatcher = APPLICATION_URL_PATTERN.matcher(response);
                    if (applicationUrlMatcher.find()) {
                        discoveredDialAppsBaseUrl = applicationUrlMatcher.group(1).trim();
                    } else {
                        discoveredDialAppsBaseUrl = locationUrl.getProtocol()
                                + "://" + discoveredTvHost + "/apps/";
                    }

                    String hostForUi = discoveredTvHost;
                    runOnUiThread(() -> {
                        statusTextView.setText(
                                "✓ DIAL-compatible device found at " + hostForUi
                                        + "\nReady to open Google Play.");
                        loadingSpinner.setVisibility(View.GONE);
                        actionButton.setEnabled(true);
                    });
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "SSDP discovery failed", e);
            } finally {
                if (socket != null) {
                    socket.close();
                }
                releaseMulticastLock();

                runOnUiThread(() -> {
                    if (discoveredTvHost == null) {
                        statusTextView.setText(
                                "No DIAL-compatible TV was detected.\n"
                                        + "You can still open the Play Store manually.");
                        loadingSpinner.setVisibility(View.GONE);
                        actionButton.setEnabled(true);
                    }
                });
            }
        }, "ssdp-discovery").start();
    }

    private void executeTargetTvFlow() {
        try {
            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + TARGET_APP_PACKAGE));
            startActivity(intent);

            if (discoveredDialAppsBaseUrl != null) {
                triggerPersistentTvLaunchDaemon(discoveredDialAppsBaseUrl);
            }
        } catch (Exception e) {
            Intent browserIntent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + TARGET_APP_PACKAGE));
            try {
                startActivity(browserIntent);
            } catch (Exception browserError) {
                Toast.makeText(
                        this,
                        "No Play Store or browser was found.",
                        Toast.LENGTH_SHORT).show();
            }
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

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    @Override
    protected void onDestroy() {
        releaseMulticastLock();
        super.onDestroy();
    }
}
