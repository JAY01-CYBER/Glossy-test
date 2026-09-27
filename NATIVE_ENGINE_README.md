# Glossy Native Audio Engine

Glossy has two selectable playback paths:

- **Glossy Audio Engine** — native C++ decode/output path using Android NDK MediaCodec/MediaExtractor, a native Float32 PCM ring buffer, native resampling/DSP, and Oboe.
- **ExoPlayer** — Media3 fallback path.

## Native signal path

`Resolved stream URL -> Android NDK MediaExtractor -> MediaCodec -> PCM16/Float -> stereo -> 48 kHz resampler -> native DSP -> Float32 PCM ring -> Oboe -> Android audio output`

There is no FFmpeg dependency in the native build.

## Native hardening

The native player now:

- waits for real native initialization before reporting playback start success;
- aborts a timed-out native startup instead of leaving a hidden worker running;
- uses `AMediaDataSource_newUri` with HTTP request headers on API 29+ and falls back to the normal extractor URI path on older API levels;
- closes the custom media data source when stopping so blocked native I/O can be interrupted;
- derives the user-visible playback position from frames actually consumed by Oboe rather than decoder PTS, avoiding buffer-ahead position reporting;
- resets position/resampler/DSP state correctly on seek and decoder output-format changes;
- keeps realtime audio callback work allocation-free and mutex-free;
- keeps decoding, resampling and DSP work off the Oboe callback thread;
- uses 48 kHz Float32 stereo as the native processing/output format;
- keeps ExoPlayer available as an explicit fallback engine.

## Media3 integration model

The current application still uses its existing Media3 player/session infrastructure for queue/session/application integration. In native mode the ExoPlayer audio output is muted while the native engine produces the audible PCM stream. This is intentionally retained as the compatibility/fallback layer rather than pretending the current build is a completely separate Media3 `Player` implementation.

A fully independent native `Player`/MediaSession implementation would require replacing the application's ExoPlayer-centric queue/session APIs with a Media3 `SimpleBasePlayer` implementation and making queue transitions, session state, notification state, seek state, audio focus and external controller commands originate from that adapter. That is a separate architectural migration and must be validated on-device before being described as complete.

## Stream-resolution note

The native decoder consumes the resolved stream URL supplied by the application. YouTube/InnerTube stream URLs are time-sensitive and can depend on the selected client, headers, signature/throttling parameters, and current YouTube-side requirements. The resolver therefore remains responsible for producing a currently valid playable URL; the native decoder does not attempt to reimplement YouTube's changing extraction protocol.

## Validation status

Static source/ZIP checks were completed. The available build runner could not download the project's Gradle 9.6.1 distribution because external network access was unavailable, so an Android Gradle/NDK/device build has **not** been falsely claimed as verified here.


## Native decoder fallback

The native player uses Android NDK MediaExtractor/MediaCodec as the primary decoder. If that path cannot open/configure the stream, the same native player falls back to the bundled FFmpeg Prefab (`io.github.yearsyan:ffmpeg-ssl:7.1.5-r2`). FFmpeg decoded PCM is resampled to the engine output format, passed through the same Glossy DSP API, and rendered through Oboe. ExoPlayer remains the higher-level application fallback when the native player itself cannot start.
