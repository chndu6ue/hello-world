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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent best-effort behavioural event delivery. */
final class AnalyticsTracker {
    private static final String TAG = "AssistantAnalytics";
    private static final String PREFS = "assistant_analytics";
    private static final String KEY_INSTALLATION_ID = "installation_id";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_QUEUE = "event_queue";
    private static final int MAX_QUEUED_EVENTS = 250;

    static final String EXTRA_USER_ID = "userid";

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object queueLock = new Object();
    private final String endpoint;
    private final String installationId;
    private final String sessionId = UUID.randomUUID().toString();

    private volatile String currentUserId;
    private volatile boolean enabled;
    private boolean launchRecorded;

    AnalyticsTracker(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.endpoint = normalizeEndpoint(BuildConfig.TRACKING_API_BASE_URL);
        this.enabled = !endpoint.isEmpty();
        this.installationId = getOrCreateInstallationId();
        this.currentUserId = preferences.getString(KEY_USER_ID, null);
    }

    void beginSession(Intent launchIntent, String language) {
        if (!enabled) {
            Log.i(TAG, "Tracking disabled: TRACKING_API_BASE_URL is not configured.");
            return;
        }
        String supplied = userIdFromIntent(launchIntent);
        if (supplied != null) {
            currentUserId = supplied;
            preferences.edit().putString(KEY_USER_ID, supplied).apply();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            Log.w(TAG, "Tracking not started: launch the assistant with ?userid=...");
            return;
        }
        if (!launchRecorded) {
            launchRecorded = true;
            JSONObject value = new JSONObject();
            put(value, "launch_source", launchIntent != null && launchIntent.getData() != null
                    ? "web_deep_link" : "launcher_or_intent");
            put(value, "device_manufacturer", Build.MANUFACTURER);
            put(value, "device_model", Build.MODEL);
            enqueue("app_launched", value, language);
        }
        flush();
    }

    void trackLanguageSelected(String language) {
        JSONObject value = new JSONObject();
        put(value, "language", language);
        track("language_selected", value, language);
    }

    void trackTvScanCompleted(int tvCount, List<String> tvNames, List<String> tvSources,
                              long scanDurationMs, String language) {
        JSONObject value = new JSONObject();
        put(value, "tv_count", Math.max(0, tvCount));
        put(value, "tv_names", new JSONArray(tvNames == null ? new ArrayList<>() : tvNames));
        put(value, "tv_sources", new JSONArray(tvSources == null ? new ArrayList<>() : tvSources));
        put(value, "scan_duration_ms", Math.max(0L, scanDurationMs));
        track("tv_scan_completed", value, language);
    }

    void trackInstallationButtonClicked(String selectedTvName, String selectedTvSource,
                                        int tvCount, String language) {
        JSONObject value = new JSONObject();
        put(value, "selected_tv_name", selectedTvName);
        put(value, "selected_tv_source", selectedTvSource);
        put(value, "tv_count", Math.max(0, tvCount));
        put(value, "play_store_package", "com.nlsn.confluencetv");
        track("installation_button_clicked", value, language);
    }

    void trackAfterInstallButtonClicked(String selectedTvName, String selectedTvSource,
                                        long elapsedSinceInstallMs, String language) {
        JSONObject value = new JSONObject();
        put(value, "selected_tv_name", selectedTvName);
        put(value, "selected_tv_source", selectedTvSource);
        if (elapsedSinceInstallMs >= 0) {
            put(value, "elapsed_since_install_click_ms", elapsedSinceInstallMs);
        }
        track("after_install_button_clicked", value, language);
    }

    void flush() {
        if (enabled && currentUserId != null) {
            executor.execute(this::flushQueue);
        }
    }

    private void track(String action, JSONObject value, String language) {
        if (!enabled || currentUserId == null || currentUserId.isEmpty()) {
            return;
        }
        enqueue(action, value, language);
        flush();
    }

    private void enqueue(String action, JSONObject value, String language) {
        try {
            JSONObject event = new JSONObject()
                    .put("user_id", currentUserId)
                    .put("action", action)
                    .put("value", value == null ? new JSONObject() : value)
                    .put("event_id", UUID.randomUUID().toString())
                    .put("session_id", sessionId)
                    .put("installation_id", installationId)
                    .put("app_version", appVersion())
                    .put("language", language == null ? "en" : language)
                    .put("client_at", utcNow());
            synchronized (queueLock) {
                JSONArray queue = readQueueLocked();
                while (queue.length() >= MAX_QUEUED_EVENTS) {
                    queue = withoutIndex(queue, 0);
                }
                queue.put(event);
                writeQueueLocked(queue);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not persist tracking event: " + error.getClass().getSimpleName());
        }
    }

    private void flushQueue() {
        while (enabled) {
            JSONObject event;
            synchronized (queueLock) {
                event = readQueueLocked().optJSONObject(0);
            }
            if (event == null) {
                return;
            }
            String eventId = event.optString("event_id");
            try {
                HttpResult result = postJson(event);
                if ((result.statusCode >= 200 && result.statusCode < 300)
                        || (result.statusCode >= 400 && result.statusCode < 500
                        && result.statusCode != 408 && result.statusCode != 429)) {
                    removeEvent(eventId);
                    continue;
                }
                return;
            } catch (Exception error) {
                Log.w(TAG, "Tracking delivery paused: " + error.getClass().getSimpleName());
                return;
            }
        }
    }

    private HttpResult postJson(JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint + "/events").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int statusCode = connection.getResponseCode();
            InputStream input = statusCode >= 200 && statusCode < 400
                    ? connection.getInputStream() : connection.getErrorStream();
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
                JSONObject event = queue.optJSONObject(i);
                if (event == null || !eventId.equals(event.optString("event_id"))) {
                    if (event != null) retained.put(event);
                }
            }
            writeQueueLocked(retained);
        }
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

    private String getOrCreateInstallationId() {
        String existing = preferences.getString(KEY_INSTALLATION_ID, null);
        if (existing != null && !existing.isEmpty()) return existing;
        String created = UUID.randomUUID().toString();
        preferences.edit().putString(KEY_INSTALLATION_ID, created).apply();
        return created;
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String userIdFromIntent(Intent intent) {
        if (intent == null) return null;
        String value = firstNonBlank(
                intent.getStringExtra(EXTRA_USER_ID),
                intent.getStringExtra("user_id"),
                query(intent.getData(), "userid"),
                query(intent.getData(), "user_id"));
        if (value == null) return null;
        value = value.trim();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private static String query(Uri uri, String key) {
        if (uri == null || !uri.isHierarchical()) return null;
        try {
            return uri.getQueryParameter(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private static String normalizeEndpoint(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.startsWith("https://") ? value : "";
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static void put(JSONObject target, String key, Object value) {
        if (value == null) return;
        try {
            target.put(key, value);
        } catch (Exception ignored) {
            // Analytics must never interrupt the install flow.
        }
    }

    private static JSONArray withoutIndex(JSONArray source, int skippedIndex) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            if (i != skippedIndex && source.opt(i) != null) result.put(source.opt(i));
        }
        return result;
    }

    private static String readBody(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && body.length() < 65536) body.append(line);
        }
        return body.toString();
    }

    private static final class HttpResult {
        private final int statusCode;
        @SuppressWarnings("unused") private final String body;
        private HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
