#!/usr/bin/env python3
"""Configure the install assistant to open the exact ConfluenceTV Play listing."""

from pathlib import Path
import re

SOURCE = Path("app/src/main/java/com/nlsn/tvcompanion/LaunchActivity.java")
TV_PACKAGE = "com.nlsn.confluencetv"

text = SOURCE.read_text(encoding="utf-8")

replacements = {
    'private static final String PLAY_SEARCH_QUERY = "confluencetv";':
        f'private static final String TV_APP_PACKAGE = "{TV_PACKAGE}";',
    'Find your television, then continue in Google Play with the search already prepared.':
        'Find your television, then open the exact ConfluenceTV listing in Google Play.',
    'We will search for ConfluenceTV automatically. Google Play controls the final device selection.':
        'We will open the exact ConfluenceTV listing. Google Play controls the final device selection.',
    'guideCard.addView(step("1", "Tap the Android TV filter in Google Play."));\n'
    '        guideCard.addView(step("2", "Open ConfluenceTV by Nielsen Streaming Panel."));\n'
    '        guideCard.addView(step("3", "Tap Install, choose your TV, and confirm."));':
        'guideCard.addView(step("1", "Review ConfluenceTV by Nielsen Streaming Panel."));\n'
        '        guideCard.addView(step("2", "Tap Install or the device-selection arrow."));\n'
        '        guideCard.addView(step("3", "Choose your TV and confirm installation."));',
    'installButton.setText("Find ConfluenceTV in Google Play");':
        'installButton.setText("Open ConfluenceTV in Google Play");',
    'String message = "Google Play will open with “ConfluenceTV” already searched.\\n\\n"\n'
    '                + "1. Tap the Android TV filter.\\n"\n'
    '                + "2. Open ConfluenceTV by Nielsen Streaming Panel.\\n"\n'
    '                + "3. Tap Install and select " + destination + ".\\n\\n"':
        'String message = "Google Play will open the exact ConfluenceTV listing.\\n\\n"\n'
        '                + "1. Review ConfluenceTV by Nielsen Streaming Panel.\\n"\n'
        '                + "2. Tap Install or the device-selection arrow.\\n"\n'
        '                + "3. Select " + destination + " and confirm.\\n\\n"',
    '.setPositiveButton("Open Google Play", (dialog, which) -> openPlayStoreSearch())':
        '.setPositiveButton("Open Google Play", (dialog, which) -> openPlayStoreListing())',
}

for old, new in replacements.items():
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found: {old[:100]!r}")
    text = text.replace(old, new)

method_pattern = re.compile(
    r"    private void openPlayStoreSearch\(\) \{.*?^    \}\n\n"
    r"    private void showAfterInstallDialog",
    re.DOTALL | re.MULTILINE,
)

method_replacement = '''    private void openPlayStoreListing() {
        Uri marketUri = Uri.parse("market://details?id=" + TV_APP_PACKAGE);
        Intent playIntent = new Intent(Intent.ACTION_VIEW, marketUri);
        playIntent.setPackage("com.android.vending");
        playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(playIntent);
            playStoreOpened = true;
        } catch (Exception playStoreError) {
            Uri webUri = Uri.parse(
                    "https://play.google.com/store/apps/details?id=" + TV_APP_PACKAGE);
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

    private void showAfterInstallDialog'''

text, count = method_pattern.subn(method_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"Expected to replace one Play Store method, replaced {count}")

SOURCE.write_text(text, encoding="utf-8")
print(f"Configured exact Google Play listing for {TV_PACKAGE}")
