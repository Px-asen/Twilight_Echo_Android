# Stream fixture

`phone-stream.mp3` is a 16-second MP3 (192 kbit/s) encoded from this repository's
`app/src/androidTest/assets/music/01-Golden-Hour.flac`. It lets the desktop interop
fixture exercise a real MP3 decoder as well as the original 42-second FLAC.

To regenerate with FFmpeg:

```powershell
ffmpeg -i app/src/androidTest/assets/music/01-Golden-Hour.flac -t 16 `
  -codec:a libmp3lame -b:a 192k -y tools/fixtures/phone-stream.mp3
```
