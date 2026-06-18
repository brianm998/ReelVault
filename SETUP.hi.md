> [अंग्रेज़ी मूल](SETUP.md)

# ReelVault डेवलपमेंट सेटअप

## पूर्वावश्यकताएँ

### सिस्टम आवश्यकताएँ

- **macOS 11+**, **Linux (Ubuntu 20.04+)**, या **Windows 10+**
- **Rust 1.70+** ([rustup के ज़रिए इंस्टॉल करें](https://rustup.rs/))
- **FFmpeg और FFprobe** (वीडियो एनालिसिस और थंबनेल जनरेशन के लिए)

### FFmpeg इंस्टॉल करें

**macOS:**
```bash
brew install ffmpeg
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install ffmpeg
```

**Windows:**
https://ffmpeg.org/download.html से डाउनलोड करें या उपयोग करें:
```bash
choco install ffmpeg
```

### Rust इंस्टॉल करें

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

वेरिफाई करें:
```bash
rustc --version
cargo --version
```

## कोर बिल्ड करें

```bash
cd core
cargo build --release
```

कंपाइल्ड बाइनरी `core/target/release/reelvault-core` पर होगी।

## कोर डेमन चलाएं

```bash
./core/target/release/reelvault-core
```

डेमन:
- `~/.reelvault/catalog.db` पर एक डेटाबेस बनाएगा
- एक थंबनेल कैश डायरेक्टरी बनाएगा
- gRPC कनेक्शन के लिए `127.0.0.1:50051` पर सुनेगा

## टेस्ट चलाएं

```bash
cd core
cargo test
```

## डेवलपमेंट कमांड

**कंपाइल किए बिना कोड चेक करें:**
```bash
cargo check
```

**कोड फॉर्मेट करें:**
```bash
cargo fmt
```

**कोड लिंट करें:**
```bash
cargo clippy
```

**डिबग सिंबल के साथ बिल्ड करें:**
```bash
cargo build
```

## फ्रंटएंड कनेक्ट करें

एक बार कोर डेमन चलने के बाद, फ्रंटएंड `http://127.0.0.1:50051` पर gRPC के ज़रिए कनेक्ट हो सकते हैं।

proto परिभाषाएँ `core/proto/reelvault.proto` में हैं और प्रत्येक फ्रंटएंड भाषा के लिए क्लाइंट कोड जेनरेट करने के लिए उपयोग की जानी चाहिए।

## समस्या निवारण

**"ffmpeg नहीं मिला"**
- सुनिश्चित करें कि FFmpeg इंस्टॉल है और आपके PATH में है
- जांचें: `which ffmpeg && which ffprobe`

**डेटाबेस लॉक्ड**
- एक समय में केवल एक डेमन इंस्टेंस चलना चाहिए
- मौजूदा प्रोसेस को किल करें: `pkill reelvault-core`

**पोर्ट पहले से उपयोग में है**
- अगर 50051 ऑक्यूपाइड है तो `core/src/main.rs` में पोर्ट बदलें
- या प्रोसेस किल करें: `lsof -ti:50051 | xargs kill`

**Linux पर बिल्ड समस्याएं**
- अतिरिक्त dev डिपेंडेंसी इंस्टॉल करें: `sudo apt-get install build-essential libssl-dev`

## अगले कदम

1. **macOS क्लाइंट**: SwiftUI फ्रंटएंड इम्प्लीमेंट करें (post-MVP)
2. **डेस्कटॉप क्लाइंट**: Kotlin Compose फ्रंटएंड इम्प्लीमेंट करें (MVP प्राथमिकता)
3. **टेस्टिंग**: gRPC API के लिए इंटीग्रेशन टेस्ट जोड़ें
4. **CI/CD**: बिल्ड और रिलीज़ के लिए GitHub Actions सेटअप करें
