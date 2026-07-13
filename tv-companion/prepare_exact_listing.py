#!/usr/bin/env python3
"""Configure the assistant for the exact ConfluenceTV listing and activation flow."""

from pathlib import Path
import re

SOURCE = Path("app/src/main/java/com/nlsn/tvcompanion/LaunchActivity.java")
TV_PACKAGE = "com.nlsn.confluencetv"

text = SOURCE.read_text(encoding="utf-8")

replacements = {
    'private static final String PLAY_SEARCH_QUERY = "confluencetv";':
        f'private static final String TV_APP_PACKAGE = "{TV_PACKAGE}";',
    'Find your television, then continue in Google Play with the search already prepared.':
        'Find your television, install ConfluenceTV, then activate it with your mobile number and OTP.',
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
    'installedButton.setText("I installed it — what next?");':
        'installedButton.setText("Open & activate on TV — claim ₹170");',
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
    'Google Play was opened. After installation, return here for the final TV steps.':
        'Installed on TV? Open ConfluenceTV, sign in with your mobile number, and complete OTP to claim ₹170.',
}

for old, new in replacements.items():
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found: {old[:100]!r}")
    text = text.replace(old, new)

play_method_pattern = re.compile(
    r"    private void openPlayStoreSearch\(\) \{.*?^    \}\n\n"
    r"    private void showAfterInstallDialog",
    re.DOTALL | re.MULTILINE,
)

play_method_replacement = '''    private void openPlayStoreListing() {
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

text, count = play_method_pattern.subn(play_method_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"Expected to replace one Play Store method, replaced {count}")

activation_method_pattern = re.compile(
    r"    private void showAfterInstallDialog\(\) \{.*?^    \}\n\n"
    r"    @Override\n    protected void onResume",
    re.DOTALL | re.MULTILINE,
)

activation_method_replacement = '''    private void showAfterInstallDialog() {
        Device selected = getSelectedDevice();
        String destination = selected == null ? "your TV" : selected.shortName();

        new AlertDialog.Builder(this)
                .setTitle("Open and activate ConfluenceTV")
                .setMessage(
                        "On " + destination + ":\\n\\n"
                                + "1. Press Home on the TV remote.\\n"
                                + "2. Open Apps or Your apps.\\n"
                                + "3. Select ConfluenceTV.\\n"
                                + "4. Sign in with the same mobile number used during registration.\\n"
                                + "5. Enter the OTP received on your mobile.\\n"
                                + "6. Keep the TV app open until activation is confirmed.\\n\\n"
                                + "After successful TV activation and OTP verification, your ₹170 installation reward can be credited. Continue using ConfluenceTV to earn daily rewards.\\n\\n"
                                + "Rewards are subject to successful verification and campaign eligibility.")
                .setPositiveButton("Got it", null)
                .show();
    }

    @Override
    protected void onResume'''

text, count = activation_method_pattern.subn(activation_method_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"Expected to replace one activation guide method, replaced {count}")

SOURCE.write_text(text, encoding="utf-8")
print(f"Configured exact Google Play listing and reward activation flow for {TV_PACKAGE}")
