> [अंग्रेज़ी मूल](README.md)

# ReelVault

Lightroom से प्रेरित एक क्रॉस-प्लेटफ़ॉर्म वीडियो कैटलॉगिंग एप्लिकेशन — बड़ी वीडियो लाइब्रेरी के लिए तेज़, नेटिव ब्राउज़िंग।

**ReelVault वीडियो को व्यवस्थित करने, खोजने और प्रबंधित करने के लिए है। यह कोई वीडियो एडिटर नहीं है।**

## अवलोकन

ReelVault आपकी मदद करता है:

- **ब्राउज़** करें हज़ारों वीडियो को एक रिस्पॉन्सिव, वर्चुअलाइज़्ड ग्रिड में जहाँ थंबनेल का साइज़ एडजस्ट किया जा सकता है।
- **स्क्रब** करें Lightroom-स्टाइल: किसी थंबनेल पर होवर करें और टाइमलाइन के फ्रेम प्रीव्यू करने के लिए बाएँ↔दाएँ स्लाइड करें।
- **ग्रुप** करें संबंधित वेरिएंट्स को (जैसे एक ही सोर्स के 4K और 1080p एक्सपोर्ट) Lightroom-स्टाइल स्टैक में; एक को ग्रिड + ओपन के लिए प्रेफर्ड मार्क करें।
- **खोजें** कंटेंट को फुल-टेक्स्ट सर्च, टैग फ़िल्टरिंग, और प्रति-फील्ड फ़िल्टर ड्रॉपडाउन (कैमरा, लेंस, कोडेक, कैप्चर वर्ष, कीवर्ड) के ज़रिए।
- **इंस्पेक्ट** करें विस्तृत मेटाडेटा: कोडेक, रेज़ोल्यूशन, FPS, बिटरेट, कलर स्पेस, HDR, EXIF, GPS, कैमरा/लेंस मॉडल।
- **व्यवस्थित** करें फ्री-फॉर्म नोट्स, कीवर्ड, और मल्टी-सेलेक्ट ऑपरेशन के साथ।
- **हैंड ऑफ** करें क्लिप्स को बाहरी एडिटर को ड्रैग-एंड-ड्रॉप से — एक या अधिक कार्ड को ग्रिड से सीधे DaVinci Resolve, Final Cut Pro, Premiere Pro, या किसी भी ऐसे ऐप में ड्रैग करें जो फाइल ड्रॉप स्वीकार करता है।
- **मल्टी-कैटलॉग** वर्कफ़्लो: File मेनू से SQLite कैटलॉग खोलें / बंद करें / स्विच करें, रीसेंट-कैटलॉग सूची और प्रति-कैटलॉग विंडो टाइटल के साथ।

## आर्किटेक्चर

```
Desktop / macOS clients          iOS / Android clients
   ↓ gRPC over loopback             ↓ gRPC + HTTPS media over the LAN
   │                                │ (mDNS/NSD discovery · pinned TLS · paired)
   └───────────────┬────────────────┘
                   ↓
        Rust Backend Daemon (reelvault-core)
                   ↓
        SQLite Catalog + FFmpeg/FFprobe + Filesystem
```

- **Rust कोर** (`core/`) — Tonic-आधारित gRPC डेमन। SQLite कैटलॉग, FFprobe मेटाडेटा एक्सट्रैक्शन, थंबनेल + स्क्रब-फ्रेम जनरेशन, स्कैन/इंडेक्सिंग, सर्च, और फ़िल्टर एग्रीगेशन का स्वामित्व रखता है। `OpenCatalog` / `CloseCatalog` / `GetCurrentCatalog` RPCs के ज़रिए रनटाइम पर एक्टिव कैटलॉग हॉट-स्वैप को सपोर्ट करता है, जिससे एक ही डेमन प्रोसेस अपने जीवनकाल में कई लाइब्रेरी सर्व कर सकती है।

- **Kotlin Compose डेस्कटॉप क्लाइंट** (`desktop/`) — Compose Multiplatform UI। `127.0.0.1:50051` पर चल रहे डेमन को ऑटो-डिटेक्ट करता है; अगर कोई नहीं चल रहा तो बंडल्ड डेमन को खुद स्पॉन करता है (50051 बिज़ी होने पर OS-असाइन्ड पोर्ट पर फॉलबैक)।

- **SwiftUI macOS क्लाइंट** (`macos/`) — फीचर-पैरिटी नेटिव macOS ऐप, उसी ऑटो-स्पॉन फ्लो के साथ, एक रियल macOS File मेनू (Commands ग्रुप), और एक रिएक्टिव विंडो टाइटल जो खुले कैटलॉग को ट्रैक करता है।

- **SwiftUI iOS क्लाइंट** (`ios/`) — एक **केवल-रिमोट** iPhone / iPad ऐप। इसके पास लोकल फाइल एक्सेस नहीं है और कोई डेमन एम्बेड नहीं है: यह Wi‑Fi (mDNS) पर डेमन डिस्कवर करता है, एक बार पेयरिंग के बाद फिंगरप्रिंट-पिन्ड TLS चैनल से कनेक्ट होता है, gRPC पर ब्राउज़ करता है, और डेमन के मीडिया सर्वर से वीडियो (डाउनस्केल्ड HLS) **स्ट्रीम** करता है। एडिटर ड्रैग-आउट को iOS शेयर शीट से रिप्लेस करता है और Photos / Files से अपलोड जोड़ता है। देखें [`ios/README.md`](ios/README.md)।

- **Kotlin Compose Android client** (`android/`) — A **remote-only**
  Android phone / tablet app. Connects to a daemon over the LAN (NSD
  discovery), streams video via ExoPlayer, and replaces editor drag-out with
  the Android share intent. Also embeds the full Rust core for on-device local
  library access — browse, catalog, and upload footage directly from the
  device. See [`android/README.md`](android/README.md).

- **ReelVaultKit** (`kit/`) — **दोनों** Apple क्लाइंट द्वारा उपयोग किए जाने वाले शेयर्ड Swift का एक लोकल SwiftPM पैकेज: मॉडल, व्यू-मॉडल, gRPC क्लाइंट, डिस्कवरी, पिन्ड TLS, और मीडिया कैशे/स्ट्रीमिंग लेयर।

- **SQLite कैटलॉग** — फुल-टेक्स्ट सर्च के लिए FTS5 के साथ WAL-मोड डेटाबेस। स्कीमा [`core/schema.sql`](core/schema.sql) में है।

> **ProRes RAW थंबनेल केवल macOS पर बेहतर गुणवत्ता के हैं।** ffmpeg ProRes RAW (Atomos S-Log3 / S-Gamut) डिकोड नहीं कर सकता, इसलिए **macOS** पर डेमन इसे QuickLook / AVFoundation के ज़रिए डिकोड करता है — सही रंग और सच्ची प्रति-फ्रेम स्क्रबिंग। **Linux / Windows** पर ऐसा कोई डिकोडर नहीं है, इसलिए डेमन ffmpeg पर फॉलबैक करता है: फ्लैटर/गहरे फ्रेम और एक ही स्क्रब फ्रेम बार-बार। Rust कोर तीनों प्लेटफॉर्म पर समान तरीके से बिल्ड होता है — AVFoundation कभी इससे लिंक नहीं होता। विवरण: [`core/README.md`](core/README.md) (बिल्ड + डिकोड पाथ) और [`macos/README.md`](macos/README.md) (macOS क्लाइंट)। हर दूसरा कोडेक ffmpeg द्वारा हर जगह एक ही तरीके से हैंडल किया जाता है।

## प्रोजेक्ट स्टेटस

**MVP macOS और Compose Desktop पर फंक्शनल है।** दोनों क्लाइंट एक ही फीचर सेट शिप करते हैं; macOS क्लाइंट नेटिव मेनू-बार कमांड और NSWorkspace-ड्रिवन एडिटर लॉन्च जोड़ता है। एक **केवल-रिमोट iOS क्लाइंट** (iPhone / iPad) LAN पर डेमन से कनेक्ट होता है और वीडियो स्ट्रीम करता है — ब्राउज़, इंस्पेक्ट, स्टैक, शेयर, और अपलोड; देखें [`ios/README.md`](ios/README.md)।

### ✅ पूर्ण

**कोर**
- [x] gRPC डेमन पूर्ण RPC सर्फेस के साथ (वीडियो, सर्च, स्कैन, टैग, कलेक्शन, स्टैक, फ़िल्टर, स्टेटस, कॉन्फिग, कैटलॉग लाइफसाइकिल)।
- [x] WAL मोड + FTS5 के साथ SQLite कैटलॉग; `OpenCatalog` / `CloseCatalog` के ज़रिए रनटाइम कैटलॉग हॉट-स्वैप।
- [x] Rich metadata extraction via FFprobe + platform-native helpers: codec,
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
      resolution, FPS, bitrate, bit depth, HDR (from transfer characteristics),
      color space, dynamic range / log profile, timecode, capture FPS, audio
      tracks / language / sample rate / bit depth, EXIF, GPS track (per-frame
      polyline), altitude, camera / lens model, ISO, aperture, exposure time,
      focal length, white balance, exposure mode/program, spatial video, 360°
      video. iPhone-specific QuickTime per-track metadata (lens, GPS, aperture)
      parsed natively so recorder-wrapped clips expose the true camera.
- [x] थंबनेल और Lightroom-स्टाइल स्क्रब-फ्रेम जनरेशन (10 फ्रेम प्रति वीडियो) डुप्लिकेट काम से बचने के लिए प्रति-वीडियो लॉक के साथ।
- [x] कंकरेंट-ffmpeg थ्रॉटल (डिफ़ॉल्ट रूप से होस्ट CPU काउंट) बड़े-लाइब्रेरी स्कैन से SAN-बैक्ड स्टोरेज को थ्रैश होने से बचाने के लिए।
- [x] ऑप्शनल रिकर्सन और वेरिएंट ऑटो-ग्रुपिंग के साथ लाइब्रेरी स्कैनिंग।
- [x] `--db-path`, `--no-catalog`, `--port` के लिए CLI फ्लैग (OS-असाइन्ड-पोर्ट फॉलबैक के साथ), और क्लाइंट लॉन्चर के लिए पार्स करने योग्य `REELVAULT_LISTENING_ON=…` stdout लाइन।

**दोनों क्लाइंट**
- [x] अडैप्टिव कॉलम काउंट और थंबनेल-साइज़ स्लाइडर के साथ वर्चुअलाइज़्ड ग्रिड व्यू।
- [x] होवर-स्क्रब प्रीव्यू, होवर-प्ले ओवरले, shift-रेंज और ⌘/Ctrl-टॉगल के साथ मल्टी-सेलेक्ट।
- [x] स्टैक (ग्रुप) UI: मेंबर काउंट के साथ स्टैक बैज, क्लिक-टू-एक्सपैंड, प्रेफर्ड सेट करने के लिए स्टार, प्रति-मेंबर ओपन बटन।
- [x] साइड पैनल: लाइब्रेरी लोकेशन (बाएँ), डिटेल + मेटाडेटा + नोट्स + कीवर्ड (दाएँ)। Tab दोनों टॉगल करता है, अलग-अलग chevron प्रत्येक को कोलैप्स करता है।
- [x] टॉप-बार फ़िल्टर ड्रॉपडाउन (कैमरा / लेंस / कीवर्ड / कोडेक / वर्ष) — केवल डेटा वाले फील्ड दिखाए जाते हैं; सर्च के साथ AND-कंबाइन्ड।
- [x] सभी प्रमुख फील्ड के साथ सॉर्ट मेनू (फाइलनेम, तारीखें, अवधि, साइज़, रेज़ोल्यूशन, fps, कोडेक, बिटरेट, कैमरा, लेंस, कीवर्ड); दिशा उल्टी करने के लिए फिर से क्लिक करें।
- [x] टैग/कीवर्ड मैनेजमेंट: फ्लाई पर बनाएं, मल्टी-सेलेक्शन पर अप्लाई करें, `>` chevron क्लिक करके ग्रिड फ़िल्टर करें।
- [x] हर कार्ड पर राइट-क्लिक कॉन्टेक्स्ट मेनू: Open with Default Player और Reveal in Finder/Explorer — पूरे मल्टी-सेलेक्शन पर ऑपरेट करता है।
- [x] ड्रैग-एंड-ड्रॉप हैंड-ऑफ: सेलेक्टेड कार्ड को किसी भी ऐप में ड्रैग करें जो फाइल ड्रॉप स्वीकार करता है (DaVinci Resolve, Final Cut Pro, Premiere Pro, आदि)।
- [x] मल्टी-कैटलॉग फ्लो: Open / Close / Open Recent के साथ File मेनू, फर्स्ट-लॉन्च "open a catalog" शीट, पर्सिस्टेंट रीसेंट्स लिस्ट, विंडो टाइटल जो ओपन कैटलॉग का नाम दिखाता है।
- [x] स्टार्टअप पर कोई बैकएंड नहीं चलने पर बंडल्ड डेमन का ऑटो-स्पॉन (पोर्ट-बिज़ी फॉलबैक के साथ)।
- [x] हर इंटरेक्टिव एलिमेंट और मेटाडेटा फील्ड पर होवर-टेक्स्ट हेल्प (Kotlin में `TooltipArea` के ज़रिए टूलटिप; SwiftUI में `.help(_:)` के ज़रिए)।
- [x] डार्क मोड डिफ़ॉल्ट; लाइट/डार्क थीम टॉगल।

### 🚧 प्लान्ड

- [ ] रियल-टाइम फाइल वॉचिंग (अंडरलाइंग फोल्डर बदलने पर री-इंडेक्स)।
- [ ] स्मार्ट कलेक्शन (लाइव फ़िल्टर नियमों के साथ सेव्ड सर्च)।
- [ ] 8K+ फुटेज के लिए प्रॉक्सी वीडियो जनरेशन।
- [ ] GitHub-आधारित ऑटो-अपडेट।
- [ ] CI/CD रिलीज़ पाइपलाइन जो प्रत्येक प्लेटफॉर्म के लिए साइन्ड इंस्टॉलर प्रोड्यूस करे।
- [ ] क्लाइंट ऐप बंडल के अंदर डेमन बाइनरी बंडल करना (आज लॉन्चर इसे `REELVAULT_CORE_BIN` या cargo dev tree के ज़रिए ढूंढता है)।

## शुरू करें

### पूर्वावश्यकताएँ

- **Rust** (1.75+ अनुशंसित) — कोर डेमन बिल्ड करने के लिए।
- **FFmpeg / FFprobe** — `PATH` पर होना चाहिए। मेटाडेटा एक्सट्रैक्शन और थंबनेल/स्क्रब-फ्रेम जनरेशन के लिए उपयोग किया जाता है।
- **JDK 17+** + Gradle (रैपर शामिल) — Kotlin डेस्कटॉप क्लाइंट के लिए।
- **Swift 5.9+ / Xcode 15+** — macOS क्लाइंट के लिए।
- **Xcode 16+ (iOS 18 SDK) + [XcodeGen](https://github.com/yonaskolb/XcodeGen)**
  (`brew install xcodegen`) — iOS क्लाइंट के लिए।

प्लेटफ़ॉर्म-विशिष्ट इंस्टॉल निर्देशों के लिए [`SETUP.md`](SETUP.md) देखें।

### कोर डेमन बिल्ड करें

```bash
cd core
cargo build --release
```

बाइनरी `core/target/release/reelvault-core` पर लैंड होती है। इसे सीधे चलाएं अगर आप खुद इसे चलाना चाहते हैं, या फर्स्ट लॉन्च पर किसी क्लाइंट को इसे आपके लिए स्पॉन करने दें:

```bash
# डिफ़ॉल्ट — डिफ़ॉल्ट पोर्ट पर प्लेटफ़ॉर्म-डिफ़ॉल्ट कैटलॉग उपयोग करें।
./target/release/reelvault-core

# बिना कैटलॉग शुरू करें (क्लाइंट इस मोड का उपयोग करते हैं); बाउंड पोर्ट प्रिंट करें।
./target/release/reelvault-core --no-catalog --port 0

# स्टार्टअप पर एक विशिष्ट कैटलॉग खोलें।
./target/release/reelvault-core --db-path /path/to/library.db
```

डेमन stdout पर एक स्थिर `REELVAULT_LISTENING_ON=127.0.0.1:N` लाइन प्रिंट करता है जिसे क्लाइंट असाइन्ड पोर्ट खोजने के लिए पार्स करते हैं।

### Kotlin Compose डेस्कटॉप क्लाइंट चलाएं

```bash
cd desktop
./gradlew run
```

फर्स्ट लॉन्च पर क्लाइंट `127.0.0.1:50051` प्रोब करता है और — अगर कुछ नहीं सुन रहा — बंडल्ड डेमन स्पॉन करता है। बिल्ड लोकेशन लुकअप ऑर्डर:

1. `$REELVAULT_CORE_BIN` (डेमन एक्सेक्यूटेबल का एब्सोल्यूट पाथ)
2. एप्लिकेशन jar के पास एक बाइनरी
3. डेव ट्री में `core/target/release/reelvault-core` या `core/target/debug/reelvault-core`
4. `PATH` पर `reelvault-core`

डेवलपमेंट के लिए, `core/` के अंदर बस `cargo build` करें और क्लाइंट डिबग बाइनरी उठा लेगा।

### macOS SwiftUI क्लाइंट चलाएं

```bash
cd macos
swift run
```

वही ऑटो-स्पॉन फ्लो, वही बाइनरी-लुकअप ऑर्डर (बंडल्ड ऐप के लिए उपयोग किए जाने वाले इन-बंडल `Resources/reelvault-core` पाथ के अलावा)। कैटलॉग खोलने के लिए `⌘O` और बंद करने के लिए `⇧⌘W` उपयोग करें; रीसेंट लिस्ट `File → Open Recent` के अंतर्गत है।

### iOS SwiftUI क्लाइंट चलाएं

iOS क्लाइंट **केवल-रिमोट** है — यह एक स्पॉन करने के बजाय LAN पर डेमन से कनेक्ट होता है। उसी Wi‑Fi पर किसी मशीन पर रिमोट मोड में डेमन शुरू करें:

```bash
reelvault-core --remote --db-path /path/to/library.db --import-dir /path/to/imports
```

फिर Xcode प्रोजेक्ट जेनरेट करें (`.xcodeproj` कमिट नहीं है) और बिल्ड करें:

```bash
cd ios
make project          # requires XcodeGen: brew install xcodegen
make build            # iOS Simulator; or open ReelVault.xcodeproj to run on a device
```

फर्स्ट लॉन्च पर ऐप mDNS पर डेमन डिस्कवर करता है, आप एक 6-अंकीय पेयरिंग कोड से डिवाइस को एक बार ऑथोराइज़ करते हैं (इसे डेस्कटॉप क्लाइंट के **File → Pair a New Device** से, या डेमन लॉग से जेनरेट करें), और फिर ब्राउज़ + स्ट्रीम करें। **iOS 18+** और **Xcode 16+** की आवश्यकता है। स्ट्रीमिंग और पेयरिंग मॉडल सहित पूरा विवरण [`ios/README.md`](ios/README.md) में है।

## डॉक्यूमेंटेशन

- [`CLAUDE.md`](CLAUDE.md) — प्रोजेक्ट विज़न, आर्किटेक्चर विवरण, डेटाबेस स्कीमा, और डिज़ाइन सिद्धांत।
- [`SETUP.md`](SETUP.md) — डेवलपमेंट एनवायरनमेंट सेटअप।
- [`CORE_REFERENCE.md`](CORE_REFERENCE.md) — Rust कोर के मॉड्यूल और RPC सर्फेस के लिए विस्तृत रेफरेंस।
- [`CLIENT_COMPARISON.md`](CLIENT_COMPARISON.md) — Kotlin और SwiftUI क्लाइंट की साइड-बाय-साइड तुलना।
- [`ios/README.md`](ios/README.md) — केवल-रिमोट iOS (iPhone / iPad) क्लाइंट: डिस्कवरी, पेयरिंग, पिन्ड TLS, और HLS स्ट्रीमिंग।
- [`macos/README.md`](macos/README.md) — नेटिव macOS क्लाइंट।

## योगदान

डेवलपमेंट गाइडलाइन के लिए [`CLAUDE.md`](CLAUDE.md) देखें। Pull requests का स्वागत है — कृपया सभी चारों क्लाइंट में फीचर-पैरिटी बनाए रखें जहाँ उचित हो
(प्रति-प्लेटफ़ॉर्म वैध विचलन के लिए CLAUDE.md देखें), और किसी भी नए सोर्स फाइल में SPDX हेडर जोड़ें (नीचे लाइसेंस देखें)।

## लाइसेंस

ReelVault फ्री सॉफ्टवेयर है, **GNU General Public License, version 3 या (आपकी पसंद पर) किसी बाद के संस्करण** के तहत लाइसेंस प्राप्त। पूरा लाइसेंस टेक्स्ट [`LICENSE`](LICENSE) में है; एक छोटा कॉपीराइट नोटिस [`COPYRIGHT`](COPYRIGHT) में है।

हर सोर्स फाइल एक SPDX आइडेंटिफायर रखती है ताकि लाइसेंस-स्कैनिंग टूल (REUSE, FOSSology, GitHub का licensee डिटेक्टर, आदि) लाइसेंस को प्रोग्रामेटिकली पहचान सकें:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors
```

अगर आप ReelVault का एक मॉडिफाइड वर्शन डिस्ट्रीब्यूट करते हैं — या कोई प्रोग्राम जो Rust कोर को लाइब्रेरी के रूप में लिंक करता है — तो GPL आपको अपना सोर्स उन्हीं शर्तों पर उपलब्ध कराने की आवश्यकता है। दायित्वों के पूरे सेट के लिए LICENSE फाइल देखें।
