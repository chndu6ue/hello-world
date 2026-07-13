#!/usr/bin/env python3
"""Apply the six-step visual walkthrough before building the assistant."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / "app/src/main/java/com/nlsn/tvcompanion/LaunchActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "app/build.gradle"
README = ROOT / "README.md"

text = JAVA.read_text(encoding="utf-8")
text = text.replace(
    "private static final long PREVIEW_FRAME_MS = 3200L;",
    "private static final long PREVIEW_FRAME_MS = 4400L;")
text = text.replace(
    "previewFrame = (previewFrame + 1) % 3;",
    "previewFrame = (previewFrame + 1) % 6;")
text = text.replace(
    "private TextView previewVisual;",
    "private TutorialPreviewView previewVisual;")

method_pattern = re.compile(
    r"    private LinearLayout createPreviewCard\(\) \{.*?^    private int currentLanguageIndex\(\) \{",
    re.DOTALL | re.MULTILINE,
)

method_replacement = r'''    private LinearLayout createPreviewCard() {
        LinearLayout previewCard = card(Color.WHITE, COLOR_BORDER);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView heading = text(getString(R.string.preview_heading), 18, COLOR_TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewCard.addView(heading);

        previewPanel = new LinearLayout(this);
        previewPanel.setOrientation(LinearLayout.VERTICAL);
        previewPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
        previewPanel.setBackground(rounded(COLOR_LIGHT_BLUE, 16, COLOR_LIGHT_BLUE));

        previewStep = text("", 12, COLOR_BLUE_DARK);
        previewStep.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewPanel.addView(previewStep);

        previewVisual = new TutorialPreviewView(this);
        previewVisual.setStep(0);
        previewVisual.setContentDescription(getString(R.string.preview_heading));
        previewVisual.setBackground(rounded(Color.WHITE, 16, COLOR_BORDER));
        previewVisual.setOnClickListener(
                v -> startActivity(new Intent(this, TutorialActivity.class)));
        previewPanel.addView(previewVisual, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(224)));

        previewTitle = text("", 17, COLOR_TEXT);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewPanel.addView(previewTitle, spacedParams(0, 12, 0, 0));

        previewBody = text("", 14, COLOR_MUTED);
        previewBody.setLineSpacing(0, 1.12f);
        previewBody.setPadding(0, dp(5), 0, 0);
        previewPanel.addView(previewBody);

        previewDots = text("", 14, COLOR_BLUE);
        previewDots.setGravity(Gravity.CENTER);
        previewDots.setPadding(0, dp(10), 0, 0);
        previewPanel.addView(previewDots);

        Button fullGuide = new Button(this);
        fullGuide.setAllCaps(false);
        fullGuide.setText(R.string.preview_open_full);
        fullGuide.setTextColor(COLOR_BLUE_DARK);
        fullGuide.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        fullGuide.setBackground(rounded(Color.WHITE, 14, COLOR_BORDER));
        fullGuide.setOnClickListener(
                v -> startActivity(new Intent(this, TutorialActivity.class)));
        previewPanel.addView(fullGuide, spacedParams(0, 10, 0, 0));

        previewCard.addView(previewPanel, spacedParams(0, 12, 0, 0));
        return previewCard;
    }

    private void showPreviewFrame(int frame, boolean animate) {
        if (previewPanel == null) {
            return;
        }

        Runnable apply = () -> {
            int[] titleIds = {
                    R.string.preview_title_1,
                    R.string.preview_title_2,
                    R.string.preview_title_3,
                    R.string.preview_title_4,
                    R.string.preview_title_5,
                    R.string.preview_title_6
            };
            int[] bodyIds = {
                    R.string.preview_body_1,
                    R.string.preview_body_2,
                    R.string.preview_body_3,
                    R.string.preview_body_4,
                    R.string.preview_body_5,
                    R.string.preview_body_6
            };
            int bounded = Math.max(0, Math.min(5, frame));
            previewStep.setText(getString(R.string.preview_step, bounded + 1, 6));
            previewVisual.setStep(bounded);
            previewTitle.setText(titleIds[bounded]);
            previewBody.setText(bodyIds[bounded]);
            previewDots.setText(previewDots(bounded));
        };

        if (!animate) {
            apply.run();
            return;
        }

        previewPanel.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    apply.run();
                    previewPanel.setAlpha(0f);
                    previewPanel.animate().alpha(1f).setDuration(220).start();
                })
                .start();
    }

    private String previewDots(int active) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                value.append("  ");
            }
            value.append(i == active ? "●" : "○");
        }
        return value.toString();
    }

    private int currentLanguageIndex() {'''

text, count = method_pattern.subn(method_replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"Expected one preview method block, replaced {count}")

JAVA.write_text(text, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
tutorial_activity = '''        <activity
            android:name=".TutorialActivity"
            android:exported="false" />

'''
if 'android:name=".TutorialActivity"' not in manifest:
    marker = '        <activity\n            android:name=".MainActivity"'
    if marker not in manifest:
        raise RuntimeError("Could not find MainActivity marker in manifest")
    manifest = manifest.replace(marker, tutorial_activity + marker)
MANIFEST.write_text(manifest, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = re.sub(r"versionCode\s+\d+", "versionCode 11", gradle, count=1)
gradle = re.sub(
    r"versionName\s+'[^']+'",
    "versionName '1.7-visual-install-guide'",
    gradle,
    count=1)
GRADLE.write_text(gradle, encoding="utf-8")

translations = {
    "values": {
        "visual_guide_title": "Install and open on TV",
        "visual_guide_note": "Google Play and TV menus may look slightly different by phone, TV brand and software version.",
        "preview_open_full": "View all visual steps",
        "previous": "Previous",
        "next": "Next",
        "done": "Done",
        "preview_title_1": "Open the ConfluenceTV listing",
        "preview_body_1": "The assistant opens the exact Android TV listing. The page may say the app is available only for your other devices.",
        "preview_title_2": "Expand the TV device list",
        "preview_body_2": "Tap the arrow beside the device section to see TVs connected to the same Google account.",
        "preview_title_3": "Choose the TV and confirm Install",
        "preview_body_3": "Select the TV you discovered. On a new TV the blue action says Install; after installation it may say Uninstall.",
        "preview_title_4": "Press Home and open Apps",
        "preview_body_4": "On the TV remote, press Home and move to Apps or Your apps.",
        "preview_title_5": "Select ConfluenceTV",
        "preview_body_5": "Find ConfluenceTV in the installed apps row, highlight it and press OK.",
        "preview_title_6": "Sign in and verify OTP",
        "preview_body_6": "Use the same registered mobile number, enter the OTP received on the phone, and keep the TV app open until activation is confirmed."
    },
    "values-hi": {
        "visual_guide_title": "टीवी पर इंस्टॉल और खोलें",
        "visual_guide_note": "फोन, टीवी ब्रांड और सॉफ्टवेयर संस्करण के अनुसार Google Play और टीवी मेनू थोड़ा अलग दिख सकते हैं।",
        "preview_open_full": "सभी दृश्य चरण देखें",
        "previous": "पिछला",
        "next": "अगला",
        "done": "पूर्ण",
        "preview_title_1": "ConfluenceTV का पेज खोलें",
        "preview_body_1": "सहायक Android TV का सही पेज खोलता है। पेज पर ऐप केवल अन्य डिवाइसों के लिए उपलब्ध बताया जा सकता है।",
        "preview_title_2": "टीवी डिवाइस सूची खोलें",
        "preview_body_2": "उसी Google खाते से जुड़े टीवी देखने के लिए डिवाइस सेक्शन के पास तीर पर टैप करें।",
        "preview_title_3": "टीवी चुनें और इंस्टॉल की पुष्टि करें",
        "preview_body_3": "खोजा गया टीवी चुनें। नए टीवी पर नीला बटन इंस्टॉल दिखाता है; इंस्टॉल होने के बाद अनइंस्टॉल दिख सकता है।",
        "preview_title_4": "Home दबाएँ और Apps खोलें",
        "preview_body_4": "टीवी रिमोट पर Home दबाएँ और Apps या Your apps पर जाएँ।",
        "preview_title_5": "ConfluenceTV चुनें",
        "preview_body_5": "इंस्टॉल किए गए ऐप्स में ConfluenceTV खोजें, उसे चुनें और OK दबाएँ।",
        "preview_title_6": "साइन इन करें और OTP सत्यापित करें",
        "preview_body_6": "वही पंजीकृत मोबाइल नंबर उपयोग करें, फोन पर मिला OTP दर्ज करें और सक्रियण की पुष्टि तक टीवी ऐप खुला रखें।"
    },
    "values-bn": {
        "visual_guide_title": "টিভিতে ইনস্টল ও খুলুন",
        "visual_guide_note": "ফোন, টিভি ব্র্যান্ড ও সফটওয়্যার সংস্করণ অনুযায়ী Google Play এবং টিভির মেনু কিছুটা আলাদা হতে পারে।",
        "preview_open_full": "সব ভিজ্যুয়াল ধাপ দেখুন",
        "previous": "আগের",
        "next": "পরের",
        "done": "সম্পন্ন",
        "preview_title_1": "ConfluenceTV পেজ খুলুন",
        "preview_body_1": "সহায়ক সঠিক Android TV লিস্টিং খুলে দেয়। পেজে অ্যাপটি শুধু অন্য ডিভাইসের জন্য উপলভ্য বলা হতে পারে।",
        "preview_title_2": "টিভি ডিভাইস তালিকা খুলুন",
        "preview_body_2": "একই Google অ্যাকাউন্টে যুক্ত টিভি দেখতে ডিভাইস অংশের পাশের তীর চাপুন।",
        "preview_title_3": "টিভি বেছে Install নিশ্চিত করুন",
        "preview_body_3": "খুঁজে পাওয়া টিভি বেছে নিন। নতুন টিভিতে নীল বোতামে Install এবং পরে Uninstall দেখা যেতে পারে।",
        "preview_title_4": "Home চাপুন এবং Apps খুলুন",
        "preview_body_4": "টিভি রিমোটে Home চাপুন এবং Apps অথবা Your apps এ যান।",
        "preview_title_5": "ConfluenceTV বেছে নিন",
        "preview_body_5": "ইনস্টল করা অ্যাপের সারিতে ConfluenceTV খুঁজে OK চাপুন।",
        "preview_title_6": "সাইন ইন ও OTP যাচাই করুন",
        "preview_body_6": "একই নিবন্ধিত মোবাইল নম্বর ব্যবহার করুন, ফোনে পাওয়া OTP লিখুন এবং অ্যাক্টিভেশন নিশ্চিত হওয়া পর্যন্ত অ্যাপ খোলা রাখুন।"
    },
    "values-mr": {
        "visual_guide_title": "टीव्हीवर इंस्टॉल करा आणि उघडा",
        "visual_guide_note": "फोन, टीव्ही ब्रँड आणि सॉफ्टवेअर आवृत्तीनुसार Google Play व टीव्ही मेनू थोडे वेगळे दिसू शकतात.",
        "preview_open_full": "सर्व दृश्य पायऱ्या पहा",
        "previous": "मागील",
        "next": "पुढील",
        "done": "पूर्ण",
        "preview_title_1": "ConfluenceTV चे पेज उघडा",
        "preview_body_1": "सहायक Android TV ची अचूक लिस्टिंग उघडतो. पेजवर अॅप फक्त इतर डिव्हाइससाठी उपलब्ध असल्याचे दिसू शकते.",
        "preview_title_2": "टीव्ही डिव्हाइस यादी उघडा",
        "preview_body_2": "त्याच Google खात्याशी जोडलेले टीव्ही पाहण्यासाठी डिव्हाइस विभागाजवळील बाण टॅप करा.",
        "preview_title_3": "टीव्ही निवडा आणि Install निश्चित करा",
        "preview_body_3": "शोधलेला टीव्ही निवडा. नवीन टीव्हीवर निळे बटण Install आणि नंतर Uninstall दाखवू शकते.",
        "preview_title_4": "Home दाबा आणि Apps उघडा",
        "preview_body_4": "टीव्ही रिमोटवर Home दाबा आणि Apps किंवा Your apps कडे जा.",
        "preview_title_5": "ConfluenceTV निवडा",
        "preview_body_5": "इंस्टॉल केलेल्या अॅप्समध्ये ConfluenceTV शोधा, निवडा आणि OK दाबा.",
        "preview_title_6": "साइन इन करा आणि OTP पडताळा",
        "preview_body_6": "तोच नोंदणीकृत मोबाइल नंबर वापरा, फोनवरील OTP भरा आणि सक्रियता निश्चित होईपर्यंत टीव्ही अॅप उघडे ठेवा."
    },
    "values-te": {
        "visual_guide_title": "టీవీలో ఇన్‌స్టాల్ చేసి తెరవండి",
        "visual_guide_note": "ఫోన్, టీవీ బ్రాండ్ మరియు సాఫ్ట్‌వేర్ వెర్షన్‌ను బట్టి Google Play మరియు టీవీ మెనూలు కొద్దిగా భిన్నంగా కనిపించవచ్చు.",
        "preview_open_full": "అన్ని దృశ్య దశలను చూడండి",
        "previous": "మునుపటి",
        "next": "తదుపరి",
        "done": "పూర్తి",
        "preview_title_1": "ConfluenceTV పేజీని తెరవండి",
        "preview_body_1": "సహాయక యాప్ సరైన Android TV లిస్టింగ్‌ను తెరుస్తుంది. యాప్ ఇతర పరికరాలకు మాత్రమే అందుబాటులో ఉందని పేజీ చూపవచ్చు.",
        "preview_title_2": "టీవీ పరికరాల జాబితాను తెరవండి",
        "preview_body_2": "అదే Google ఖాతాకు కనెక్ట్ అయిన టీవీలను చూడటానికి పరికరాల విభాగం పక్కన ఉన్న బాణాన్ని నొక్కండి.",
        "preview_title_3": "టీవీని ఎంచుకుని Install నిర్ధారించండి",
        "preview_body_3": "కనుగొన్న టీవీని ఎంచుకోండి. కొత్త టీవీలో నీలి బటన్ Install అని, ఇన్‌స్టాల్ అయిన తర్వాత Uninstall అని చూపవచ్చు.",
        "preview_title_4": "Home నొక్కి Apps తెరవండి",
        "preview_body_4": "టీవీ రిమోట్‌లో Home నొక్కి Apps లేదా Your apps కి వెళ్లండి.",
        "preview_title_5": "ConfluenceTV ఎంచుకోండి",
        "preview_body_5": "ఇన్‌స్టాల్ చేసిన యాప్‌ల వరుసలో ConfluenceTVను కనుగొని OK నొక్కండి.",
        "preview_title_6": "సైన్ ఇన్ చేసి OTP ధృవీకరించండి",
        "preview_body_6": "అదే నమోదైన మొబైల్ నంబర్ ఉపయోగించి, ఫోన్‌కు వచ్చిన OTP నమోదు చేసి, యాక్టివేషన్ నిర్ధారణ వరకు టీవీ యాప్‌ను తెరిచి ఉంచండి."
    },
    "values-ta": {
        "visual_guide_title": "டிவியில் நிறுவி திறக்கவும்",
        "visual_guide_note": "தொலைபேசி, டிவி பிராண்ட் மற்றும் மென்பொருள் பதிப்பைப் பொறுத்து Google Play மற்றும் டிவி மெனுக்கள் சற்று மாறுபடலாம்.",
        "preview_open_full": "அனைத்து காட்சி படிகளையும் பார்க்கவும்",
        "previous": "முந்தையது",
        "next": "அடுத்தது",
        "done": "முடிந்தது",
        "preview_title_1": "ConfluenceTV பக்கத்தைத் திறக்கவும்",
        "preview_body_1": "உதவி செயலி சரியான Android TV பட்டியலைத் திறக்கும். செயலி மற்ற சாதனங்களுக்கு மட்டும் கிடைக்கும் என்று பக்கம் காட்டலாம்.",
        "preview_title_2": "டிவி சாதன பட்டியலைத் திறக்கவும்",
        "preview_body_2": "அதே Google கணக்குடன் இணைந்த டிவிகளைப் பார்க்க சாதனப் பகுதியின் அருகிலுள்ள அம்பைத் தட்டவும்.",
        "preview_title_3": "டிவியைத் தேர்ந்து Install உறுதி செய்யவும்",
        "preview_body_3": "கண்டறியப்பட்ட டிவியைத் தேர்ந்தெடுக்கவும். புதிய டிவியில் நீல பொத்தான் Install என்றும் நிறுவிய பின் Uninstall என்றும் தோன்றலாம்.",
        "preview_title_4": "Home அழுத்தி Apps திறக்கவும்",
        "preview_body_4": "டிவி ரிமோட்டில் Home அழுத்தி Apps அல்லது Your apps பகுதிக்குச் செல்லவும்.",
        "preview_title_5": "ConfluenceTV தேர்ந்தெடுக்கவும்",
        "preview_body_5": "நிறுவப்பட்ட செயலிகளில் ConfluenceTVஐ கண்டுபிடித்து OK அழுத்தவும்.",
        "preview_title_6": "உள்நுழைந்து OTP சரிபார்க்கவும்",
        "preview_body_6": "அதே பதிவு செய்யப்பட்ட மொபைல் எண்ணைப் பயன்படுத்தி, தொலைபேசியில் வந்த OTPஐ உள்ளிட்டு, செயல்படுத்தல் உறுதியாகும் வரை டிவி செயலியை திறந்துவைக்கவும்."
    }
}

for qualifier, values in translations.items():
    path = ROOT / f"app/src/main/res/{qualifier}/strings.xml"
    tree = ET.parse(path)
    root = tree.getroot()
    existing = {node.attrib.get("name"): node for node in root.findall("string")}
    for key, value in values.items():
        node = existing.get(key)
        if node is None:
            node = ET.SubElement(root, "string", {"name": key})
        node.text = value
    ET.indent(tree, space="    ")
    tree.write(path, encoding="utf-8", xml_declaration=True)

readme = README.read_text(encoding="utf-8")
section = """
## Visual install guide

Version 1.7 adds a six-step, source-drawn walkthrough:

1. Open the exact ConfluenceTV Google Play listing.
2. Expand the eligible TV device list.
3. Select the TV and confirm installation.
4. Press Home and open Apps on the TV.
5. Select ConfluenceTV.
6. Sign in with the registered mobile number and verify OTP.

The illustrations are intentionally drawn in-app instead of copying Google Play
screenshots, because Play Store and Android TV layouts vary by version and OEM.
"""
if "## Visual install guide" not in readme:
    readme = readme.rstrip() + "\n" + section
README.write_text(readme, encoding="utf-8")

print("Applied six-step visual install guide.")
