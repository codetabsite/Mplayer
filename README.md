# MPlayer ♪

Minimal Bir MP3 Çalar Android İçin.  
`com.tdev.mplayr` — no ads, no bloat.

---

## Build

```bash
./gradlew assembleDebug
```

## Signed Release

### 1 — Generate a keystore (one-time)

```bash
keytool -genkey -v \
  -keystore mplayr.jks \
  -alias mplayr \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

### 2 — Add secrets to GitHub repo

Go to **Settings → Secrets → Actions** and add:

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 -i mplayr.jks` |
| `KEYSTORE_PASSWORD` | your keystore password |
| `KEY_ALIAS` | `mplayr` |
| `KEY_PASSWORD` | your key password |

### 3 — Push to `main`

GitHub Actions builds, signs, and uploads the APK as an artifact.  
Tag a commit `v1.0` to auto-create a GitHub Release with the APK attached.

---

**Permissions:** `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 12-)
