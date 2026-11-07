# TLDR

An Android app that puts a small floating bubble over every app. Tap it and you get a Vietnamese summary of
whatever is on screen: a web page in Chrome, a Facebook post, an article, a chat thread.

## How it works

| Way in | What gets summarized |
| --- | --- |
| Tap the floating **TL** bubble | The visible text of the app on screen, read through Android's Accessibility API. In a browser it also reads the address bar, so you can then tap **Tóm tắt toàn trang** to download and summarize the whole page. |
| **Share → TLDR** from any app | A shared link is downloaded and summarized as a full page; shared text is summarized directly. |
| Select text → **Tóm tắt** | Just the selected text. |

The summary follows a fixed format, in Vietnamese: a one-line **TL;DR**, then **Ý chính** (key points), then
**Chi tiết đáng chú ý** (numbers, dates, names). For social posts it also covers who posted and, if comments
are visible, **Bình luận nổi bật**. You can rewrite the prompt in the app.

Summaries come from the AI provider you pick, with your own API key:

- **Claude** (Anthropic): default `claude-opus-5-5`; `claude-haiku-5-5` is much cheaper and fine for short posts.
- **Gemini** (Google): default `gemini-3.5-flash-lite` (fastest, has a free tier); the app falls back to other Gemini models when one is overloaded.

The key is stored encrypted on the phone. Screen text is sent to that provider only when you tap the bubble
(or share/select text). Nothing else leaves the device.

## Install

1. Download `tldr-debug-apk` from the latest successful **Build APK** run under the repo's Actions tab, unzip
   it, and open the `.apk` on your phone (allow installing from your browser/files app when asked).
2. Open TLDR, pick Claude or Gemini, paste your API key, and tap **Thử tóm tắt một đoạn mẫu** to check it works.
3. Tap **Mở cài đặt Trợ năng** → Downloaded apps → TLDR → turn it on.
   On Android 13+ the switch may be greyed out for apps installed outside Play: go to
   App info → TLDR → ⋮ → **Allow restricted settings**, then try again.
4. The bubble appears. Drag it anywhere; it snaps to the edge. Long-press it to hide it (turn it back on in the app).

## Limits

- Only text that is on screen (or already loaded in the view) is read. Scroll to the part you care about, or
  expand "See more" on a Facebook post first.
- Text drawn as images, or inside apps that block accessibility, cannot be read.
- Full-page download works for public pages. Facebook and other logged-in pages return a login wall, so the
  app falls back to the on-screen text.
- Google Play restricts apps that use the Accessibility API like this. This app is meant for personal sideloading.

## Project layout

- `summarizer/`: plain Kotlin module with the prompt, the Claude and Gemini clients, and web page text
  extraction. It has unit tests and builds without the Android SDK.
- `app/`: the Android app (bubble and screen reading in `TldrAccessibilityService`, summary dialog in
  `SummaryActivity`, settings in `MainActivity`).

```sh
./gradlew :summarizer:test       # unit tests
./gradlew :app:assembleDebug     # APK in app/build/outputs/apk/debug/
```
