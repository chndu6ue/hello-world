package com.nlsn.tvcompanion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent, best-effort analytics delivery for the install assistant.
 *
 * <p>The plaintext mobile number is held only long enough to start the server
 * session. It is never written to SharedPreferences or Android logs. Events are
 * assigned UUIDs and persisted before delivery, so server retries are
 * idempotent.</p>
 */
final class AnalyticsTracker {

    private static final String TAG = "AssistantAnalytics";
    private static final String PREFS = "assistant_analytics";
    private static final String KEY_INSTALLATION_ID = "installation_id";
    private static final String KEY_SEQUENCE = "sequence";
    private static final String KEY_QUEUE = "event_queue";
    private static final String KEY_MOBILE_FINGERPRINT = "mobile_fingerprint";
    private static final int MAX_QUEUED_EVENTS = 200;

    static final String EXTRA_MOBILE_NUMBER = "mobile_number";
    static final String EXTRA_REGISTRATION_ID = "registration_id";
    static final String EXTRA_TRANSID = "transid";

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object queueLock = new Object();
    private final String apiBaseUrl;
    private final String installationId;

    private volatile String currentSessionToken;
    private volatile String currentMobileFingerprint;
    private volatile boolean enabled;

    AnalyticsTracker(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.apiBaseUrl = normalizeBaseUrl(BuildConfig.TRACKING_API_BASE_URL);
        this.enabled = !this.apiBaseUrl.isEmpty();
        this.installationId = getOrCreateInstallationId();
    }

    void beginSession(Intent launchIntent, String language) {
        if (!enabled) {
            Log.i(TAG, "Tracking is disabled because TRACKING_API_BASE_URL is not configured.");
            return;
        }

        LaunchIdentity identity = LaunchIdentity.fromIntent(launchIntent);
        if (identity.mobileNumber == null || identity.mobileNumber.trim().isEmpty()) {
            Log.w(TAG, "Tracking session not started: mobile_number was not supplied.");
            return;
        }

        currentMobileFingerprint = sha256(identity.mobileNumber.trim());
        discardUnboundEventsForOtherUser(currentMobileFingerprint);

        Map<String, Object> launchProperties = new HashMap<>();
        launchProperties.put("launch_source", identity.launchSource);
        enqueueEvent("app_launched", launchProperties, null, currentMobileFingerprint);

        executor.execute(() -> startSessionWithRetry(identity, language));
    }

    void trackLanguageSelected(String language) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("language", language);
        track("language_selected", properties);
    }

    void trackTvScanCompleted(int tvCount, List<String> tvSources, long scanDurationMs) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("tv_count", Math.max(0, tvCount));
        properties.put("tv_sources", tvSources == null ? new ArrayList<>() : tvSources);
        properties.put("scan_duration_ms", Math.max(0L, scanDurationMs));
        track("tv_scan_completed", properties);
    }

    void trackInstallationClicked(String selectedTvSource, int tvCount) {
        Map<String, Object> properties = new HashMap<>();
        if (selectedTvSource != null) {
            properties.put("selected_tv_source", selectedTvSource);
        }
        properties.put("tv_count", Math.max(0, tvCount));
        properties.put("play_store_package", "com.nlsn.confluencetv");
        track("installation_clicked", properties);
    }

    void trackPostInstallClicked(String selectedTvSource, long elapsedMs) {
        Map<String, Object> properties = new HashMap<>();
        if (selectedTvSource != null) {
            properties.put("selected_tv_source", selectedTvSource);
        }
        if (elapsedMs >= 0) {
            properties.put("elapsed_since_install_click_ms", elapsedMs);
        }
        track("post_install_clicked", properties);
    }

    void flush() {
        if (enabled) {
            executor.execute(this::flushQueue);
        }
    }

    private void track(String eventType, Map<String, Object> properties) {
        if (!enabled || currentMobileFingerprint == null) {
            return;
        }
        enqueueEvent(eventType, properties, currentSessionToken, currentMobileFingerprint);
        flush();
    }

    private void startSessionWithRetry(LaunchIdentity identity, String language) {
        long[] delaysMs = {0L, 2000L, 7000L};
        for (int attempt = 0; attempt < delaysMs.length; attempt++) {
            if (delaysMs[attempt] > 0) {
                try {
                    Thread.sleep(delaysMs[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            try {
                String token = startSession(identity, language);
                if (token != null && !token.isEmpty()) {
                    currentSessionToken = token;
                    bindUnboundEvents(currentMobileFingerprint, token);
                    flushQueue();
                    return;
                }
            } catch (Exception error) {
                Log.w(TAG, "Session start attempt failed: " + error.getClass().getSimpleName());
            }
        }
    }

    private String startSession(LaunchIdentity identity, String language) throws Exception {
        JSONObject device = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_sdk", Build.VERSION.SDK_INT);

        JSONObject payload = new JSONObject()
                .put("mobile_number", identity.mobileNumber)
                .put("installation_id", installationId)
                .put("app_id", context.getPackageName())
                .put("app_version", appVersion())
                .put("language", language)
                .put("launch_source", identity.launchSource)
                .put("device", device);

        if (identity.registrationId != null && !identity.registrationId.isEmpty()) {
            payload.put("registration_id", identity.registrationId);
        }
        if (identity.transid != null && !identity.transid.isEmpty()) {
            payload.put("transid", identity.transid);
        }

        HttpResult result = postJson("/v1/sessions/start", payload, null);
        if (result.statusCode < 200 || result.statusCode >= 300) {
            throw new IllegalStateException("Session start HTTP " + result.statusCode);
        }
        return new JSONObject(result.body).optString("session_token", "");
    }

    private void enqueueEvent(
            String eventType,
            Map<String, Object> properties,
            String token,
            String fingerprint) {
        try {
            JSONObject event = new JSONObject()
                    .put("event_id", UUID.randomUUID().toString())
                    .put("event_type", eventType)
                    .put("client_timestamp", utcNow())
                    .put("sequence_number", nextSequence())
                    .put("properties", new JSONObject(properties));

            JSONObject envelope = new JSONObject()
                    .put("token", token == null ? "" : token)
                    .put("fingerprint", fingerprint == null ? "" : fingerprint)
                    .put("event", event);

            synchronized (queueLock) {
                JSONArray queue = readQueueLocked();
                while (queue.length() >= MAX_QUEUED_EVENTS) {
                    queue = withoutIndex(queue, 0);
                }
                queue.put(envelope);
                writeQueueLocked(queue);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not persist analytics event: " + error.getClass().getSimpleName());
        }
    }

    private void bindUnboundEvents(String fingerprint, String token) {
        synchronized (queueLock) {
            JSONArray queue = readQueueLocked();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject envelope = queue.optJSONObject(i);
                if (envelope == null) {
                    continue;
                }
                if (envelope.optString("token").isEmpty()
                        && fingerprint.equals(envelope.optString("fingerprint"))) {
                    try {
                        envelope.put("token", token);
                    } catch (Exception ignored) {
                        // JSONObject put cannot fail for String values.
                    }
                }
            }
            writeQueueLocked(queue);
        }
    }

    private void discardUnboundEventsForOtherUser(String fingerprint) {
        synchronized (queueLock) {
            JSONArray queue = readQueueLocked();
            JSONArray retained = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject envelope = queue.optJSONObject(i);
                if (envelope == null) {
                    continue;
                }
                String token = envelope.optString("token");
                String queuedFingerprint = envelope.optString("fingerprint");
                if (!token.isEmpty() || fingerprint.equals(queuedFingerprint)) {
                    retained.put(envelope);
                }
            }
            writeQueueLocked(retained);
            preferences.edit().putString(KEY_MOBILE_FINGERPRINT, fingerprint).apply();
        }
    }

    private void flushQueue() {
        while (enabled) {
            JSONObject envelope;
            synchronized (queueLock) {
                envelope = firstDeliverableEnvelope(readQueueLocked());
            }
            if (envelope == null) {
                return;
            }

            String token = envelope.optString("token");
            JSONObject event = envelope.optJSONObject("event");
            if (token.isEmpty() || event == null) {
                return;
            }

            String eventId = event.optString("event_id");
            try {
                HttpResult result = postJson("/v1/events", event, token);
                if ((result.statusCode >= 200 && result.statusCode < 300)
                        || (result.statusCode >= 400
                        && result.statusCode < 500
                        && result.statusCode != 408
                        && result.statusCode != 429)) {
                    removeEvent(eventId);
                    continue;
                }
                return;
            } catch (Exception error) {
                Log.w(TAG, "Event delivery paused: " + error.getClass().getSimpleName());
                return;
            }
        }
    }

    private HttpResult postJson(String path, JSONObject payload, String bearerToken)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiBaseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Client-App", context.getPackageName());
            if (bearerToken != null && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int statusCode = connection.getResponseCode();
            InputStream input = statusCode >= 200 && statusCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            return new HttpResult(statusCode, readBody(input));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void removeEvent(String eventId) {
        synchronized (queueLock) {
            JSONArray queue = readQueueLocked();
            JSONArray retained = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject envelope = queue.optJSONObject(i);
                JSONObject event = envelope == null ? null : envelope.optJSONObject("event");
                if (event == null || !eventId.equals(event.optString("event_id"))) {
                    if (envelope != null) {
                        retained.put(envelope);
                    }
                }
            }
            writeQueueLocked(retained);
        }
    }

    private JSONObject firstDeliverableEnvelope(JSONArray queue) {
        for (int i = 0; i < queue.length(); i++) {
            JSONObject envelope = queue.optJSONObject(i);
            if (envelope != null && !envelope.optString("token").isEmpty()) {
                return envelope;
            }
        }
        return null;
    }

    private JSONArray readQueueLocked() {
        try {
            return new JSONArray(preferences.getString(KEY_QUEUE, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void writeQueueLocked(JSONArray queue) {
        preferences.edit().putString(KEY_QUEUE, queue.toString()).apply();
    }

    private int nextSequence() {
        synchronized (queueLock) {
            int next = preferences.getInt(KEY_SEQUENCE, 0) + 1;
            preferences.edit().putInt(KEY_SEQUENCE, next).apply();
            return next;
        }
    }

    private String getOrCreateInstallationId() {
        String existing = preferences.getString(KEY_INSTALLATION_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        preferences.edit().putString(KEY_INSTALLATION_ID, created).apply();
        return created;
    }

    private String appVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static JSONArray withoutIndex(JSONArray source, int skippedIndex) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            if (i != skippedIndex) {
                Object value = source.opt(i);
                if (value != null) {
                    result.put(value);
                }
            }
        }
        return result;
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("https://")) {
            return "";
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hashed) {
                result.append(String.format(Locale.US, "%02x", item));
            }
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String readBody(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 65536) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private static final class HttpResult {
        private final int statusCode;
        private final String body;

        private HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final class LaunchIdentity {
        private final String mobileNumber;
        private final String registrationId;
        private final String transid;
        private final String launchSource;

        private LaunchIdentity(
                String mobileNumber,
                String registrationId,
                String transid,
                String launchSource) {
            this.mobileNumber = mobileNumber;
            this.registrationId = registrationId;
            this.transid = transid;
            this.launchSource = launchSource;
        }

        private static LaunchIdentity fromIntent(Intent intent) {
            if (intent == null) {
                return new LaunchIdentity(null, null, null, "unknown");
            }

            String mobile = firstNonBlank(
                    intent.getStringExtra(EXTRA_MOBILE_NUMBER),
                    intent.getStringExtra("mobile"),
                    queryParameter(intent.getData(), "mobile_number"),
                    queryParameter(intent.getData(), "mobile"));
            String registrationId = firstNonBlank(
                    intent.getStringExtra(EXTRA_REGISTRATION_ID),
                    queryParameter(intent.getData(), EXTRA_REGISTRATION_ID));
            String transid = firstNonBlank(
                    intent.getStringExtra(EXTRA_TRANSID),
                    queryParameter(intent.getData(), EXTRA_TRANSID));
            String launchSource = intent.getData() != null ? "deep_link" : "intent_extra";
            return new LaunchIdentity(mobile, registrationId, transid, launchSource);
        }

        private static String queryParameter(Uri uri, String key) {
            if (uri == null || !uri.isHierarchical()) {
                return null;
            }
            try {
                return uri.getQueryParameter(key);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
            return null;
        }
    }
}
