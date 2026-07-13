#!/usr/bin/env python3
"""Apply user-ID analytics, deep-link launch, and safe status-bar insets."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / "app/src/main/java/com/nlsn/tvcompanion/LaunchActivity.java"
TUTORIAL = ROOT / "app/src/main/java/com/nlsn/tvcompanion/TutorialActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle"

text = JAVA.read_text(encoding="utf-8")
text = text.replace(
    "import androidx.core.content.ContextCompat;",
    "import androidx.core.content.ContextCompat;\nimport androidx.core.graphics.Insets;\nimport androidx.core.view.ViewCompat;\nimport androidx.core.view.WindowCompat;\nimport androidx.core.view.WindowInsetsCompat;")
text = text.replace(
    "    private RadioGroup deviceGroup;\n",
    "    private RadioGroup deviceGroup;\n    private AnalyticsTracker analyticsTracker;\n    private long scanStartedAtMs;\n    private long installClickedAtMs = -1L;\n")
old_on_create = '''        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);

        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        setupUi();

        if (checkDiscoveryPermissions()) {'''
new_on_create = '''        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(COLOR_NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);

        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        setupUi();
        analyticsTracker = new AnalyticsTracker(this);
        analyticsTracker.beginSession(getIntent(), currentLanguageCode());

        if (checkDiscoveryPermissions()) {'''
if old_on_create not in text:
    raise RuntimeError("LaunchActivity onCreate block changed")
text = text.replace(old_on_create, new_on_create)
text = text.replace(
    "        header.setBackgroundColor(COLOR_NAVY);\n",
    "        header.setBackgroundColor(COLOR_NAVY);\n        ViewCompat.setOnApplyWindowInsetsListener(header, (view, insets) -> {\n            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());\n            view.setPadding(dp(22), dp(16) + bars.top, dp(22), dp(24));\n            return insets;\n        });\n")
text = text.replace(
    "        brandRow.addView(languageSpinner, new LinearLayout.LayoutParams(dp(116), dp(46)));",
    "        brandRow.addView(languageSpinner, new LinearLayout.LayoutParams(dp(108), dp(40)));")
text = text.replace(
    '''                if (!selectedCode.equals(currentCode)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE)''',
    '''                if (!selectedCode.equals(currentCode)) {
                    if (analyticsTracker != null) {
                        analyticsTracker.trackLanguageSelected(selectedCode);
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE)''')
text = text.replace(
    "        content.setPadding(dp(18), dp(20), dp(18), dp(32));\n",
    "        content.setPadding(dp(18), dp(20), dp(18), dp(32));\n        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {\n            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());\n            view.setPadding(dp(18), dp(20), dp(18), dp(32) + bars.bottom);\n            return insets;\n        });\n")
text = text.replace(
    "        installedButton.setOnClickListener(v -> showAfterInstallDialog());",
    '''        installedButton.setOnClickListener(v -> {
            Device selected = getSelectedDevice();
            if (analyticsTracker != null) {
                analyticsTracker.trackAfterInstallButtonClicked(
                        selected == null ? null : selected.shortName(),
                        selected == null ? null : selected.source,
                        installClickedAtMs < 0 ? -1L : System.currentTimeMillis() - installClickedAtMs,
                        currentLanguageCode());
            }
            showAfterInstallDialog();
        });''')
text = text.replace(
    "    private void discoverDevices() {\n        final int generation = ++scanGeneration;",
    "    private void discoverDevices() {\n        scanStartedAtMs = System.currentTimeMillis();\n        final int generation = ++scanGeneration;")
old_count = '''            int count;
            synchronized (deviceLock) {
                count = devices.size();
            }

            if (count == 0) {'''
new_count = '''            int count;
            List<String> tvNames = new ArrayList<>();
            List<String> tvSources = new ArrayList<>();
            synchronized (deviceLock) {
                count = devices.size();
                for (Device device : devices.values()) {
                    tvNames.add(device.shortName());
                    if (device.source != null) {
                        tvSources.add(device.source);
                    }
                }
            }

            if (analyticsTracker != null) {
                analyticsTracker.trackTvScanCompleted(
                        count,
                        tvNames,
                        tvSources,
                        Math.max(0L, System.currentTimeMillis() - scanStartedAtMs),
                        currentLanguageCode());
            }

            if (count == 0) {'''
if old_count not in text:
    raise RuntimeError("Discovery completion block changed")
text = text.replace(old_count, new_count)
text = text.replace(
    '''                .setPositiveButton(
                        R.string.open_google_play,
                        (dialog, which) -> openPlayStoreListing())''',
    '''                .setPositiveButton(
                        R.string.open_google_play,
                        (dialog, which) -> trackInstallAndOpenPlayStore())''')
marker = "    private void openPlayStoreListing() {\n"
install_method = '''    private void trackInstallAndOpenPlayStore() {
        Device selected = getSelectedDevice();
        int count;
        synchronized (deviceLock) {
            count = devices.size();
        }
        installClickedAtMs = System.currentTimeMillis();
        if (analyticsTracker != null) {
            analyticsTracker.trackInstallationButtonClicked(
                    selected == null ? null : selected.shortName(),
                    selected == null ? null : selected.source,
                    count,
                    currentLanguageCode());
        }
        openPlayStoreListing();
    }

'''
if marker not in text:
    raise RuntimeError("Play Store method marker changed")
text = text.replace(marker, install_method + marker, 1)
text = text.replace(
    '''    @Override
    protected void onResume() {
        super.onResume();''',
    '''    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (analyticsTracker != null) {
            analyticsTracker.beginSession(intent, currentLanguageCode());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (analyticsTracker != null) {
            analyticsTracker.flush();
        }''')
current_marker = "    private int currentLanguageIndex() {\n"
current_method = '''    private String currentLanguageCode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LANGUAGE, "en");
    }

'''
if current_marker not in text:
    raise RuntimeError("Current language marker changed")
text = text.replace(current_marker, current_method + current_marker, 1)
JAVA.write_text(text, encoding="utf-8")

tutorial = TUTORIAL.read_text(encoding="utf-8")
tutorial = tutorial.replace(
    "import androidx.appcompat.app.AppCompatActivity;",
    "import androidx.appcompat.app.AppCompatActivity;\nimport androidx.core.graphics.Insets;\nimport androidx.core.view.ViewCompat;\nimport androidx.core.view.WindowCompat;\nimport androidx.core.view.WindowInsetsCompat;")
tutorial = tutorial.replace(
    "        super.onCreate(savedInstanceState);\n        getWindow().setStatusBarColor(COLOR_NAVY);",
    "        super.onCreate(savedInstanceState);\n        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);\n        getWindow().setStatusBarColor(COLOR_NAVY);")
tutorial = tutorial.replace(
    "        root.setPadding(dp(18), dp(18), dp(18), dp(24));\n",
    "        root.setPadding(dp(18), dp(18), dp(18), dp(24));\n        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {\n            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());\n            view.setPadding(dp(18), dp(18) + bars.top, dp(18), dp(24) + bars.bottom);\n            return insets;\n        });\n")
TUTORIAL.write_text(tutorial, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
manifest = manifest.replace(
    'android:name=".LaunchActivity"\n            android:exported="true">',
    'android:name=".LaunchActivity"\n            android:exported="true"\n            android:launchMode="singleTop">')
if 'android:scheme="nielsenassistant"' not in manifest:
    manifest = manifest.replace(
        '''            </intent-filter>
        </activity>''',
        '''            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="nielsenassistant" android:host="launch" />
            </intent-filter>
        </activity>''',
        1)
MANIFEST.write_text(manifest, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = re.sub(r"versionCode\s+\d+", "versionCode 12", gradle, count=1)
gradle = re.sub(r"versionName\s+'[^']+'", "versionName '1.8-tracking-dashboard'", gradle, count=1)
if "TRACKING_API_BASE_URL" not in gradle:
    gradle = gradle.replace(
        "        targetSdk 35\n",
        "        targetSdk 35\n        buildConfigField 'String', 'TRACKING_API_BASE_URL', '\"https://tr-aqz5boo4qa-el.a.run.app/tracker\"'\n")
if "buildFeatures" not in gradle:
    gradle = gradle.replace(
        "    signingConfigs {\n",
        "    buildFeatures {\n        buildConfig true\n    }\n\n    signingConfigs {\n")
GRADLE.write_text(gradle, encoding="utf-8")
print("Applied user-ID tracking, dashboard endpoint, deep link, and inset fixes")
