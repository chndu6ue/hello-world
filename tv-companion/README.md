# TV Sync Companion test build

This Android test app scans the local network for DIAL-compatible devices using SSDP, opens the Google Play listing for `com.nlsn.psc`, and attempts a DIAL launch when an application endpoint is available.

Important limitations:
- DIAL/SSDP discovery does not prove the device is Android TV.
- Google Play remote installation still requires user interaction.
- A DIAL receiver can launch only applications it recognizes; Android package names are not universally valid DIAL app names.
- The debug APK is signed with the standard Android debug key and is for testing only.
