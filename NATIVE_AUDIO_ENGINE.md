# Glossy Native C++ Audio Engine

This tree contains a real native playback path. It is intentionally separate from the existing Media3/ExoPlayer path.

## Native path

```text
stream URL
   -> Android NDK AMediaExtractor (HTTP(S) URI)
   -> native audio-track selection / demux
   -> AMediaCodec decoder (AAC/Opus/etc. supported by the device)
   -> PCM16 -> Float32 conversion
   -> single-producer/single-consumer PCM ring buffer
   -> Oboe Float32 output callback
   -> native EQ / bass / stereo / limiter DSP
   -> Android audio device
```

The native engine owns playback state, decoder lifecycle, PCM buffering, output stream lifecycle, pause/resume, seek requests, volume and playback position. No Media3 `AudioProcessor` is required by the native playback path.

## Why Android NDK codecs are used

FFmpeg is not used. The decoder is `AMediaCodec` and the container/sample extraction is `AMediaExtractor`. Android exposes these APIs from the NDK and they can consume HTTP(S) media sources. This keeps the playback engine native while using the platform's supported AAC/Opus implementations instead of shipping a second codec stack.

## JNI surface

`NativeEngine` exposes:

- `playUrl`
- `pausePlayback`
- `resumePlayback`
- `stopPlayback`
- `seekPlayback`
- `setPlaybackVolume`
- `getPlaybackPosition`
- `isPlaybackActive`

The existing DSP JNI surface remains available for the Media3 fallback/DSP path.

## Important integration note

The existing Glossy service still has a large ExoPlayer queue/state-management surface. The native engine is deliberately exposed as an independent playback engine rather than silently replacing every service operation. The final engine selector should route play/pause/seek/volume and stream URL ownership to this native handle when selected, while retaining ExoPlayer as the fallback engine.

Do not describe the current repository as device-build-verified unless it has been built with the project's Android SDK/NDK and exercised on a real device.
