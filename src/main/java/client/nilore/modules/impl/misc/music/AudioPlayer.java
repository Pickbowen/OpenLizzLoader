package client.nilore.modules.impl.misc.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AudioPlayer {
    public enum State { STOPPED, PLAYING, PAUSED, LOADING }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private volatile float volume = 0.8f;
    private volatile SongInfo currentSong;
    private volatile String currentUrl;
    private volatile SourceDataLine currentLine;
    private volatile Thread playbackThread;
    private volatile boolean paused;

    private static final int SPECTRUM_BARS = 32;
    private static final int ANALYSIS_WINDOW = 1024;
    private final AtomicLong playbackGeneration = new AtomicLong();
    private final AtomicReference<float[]> spectrumSnapshot = new AtomicReference<>(new float[0]);
    private final float[] analysisSamples = new float[ANALYSIS_WINDOW];
    private int analysisSampleCount;

    // time-based progress tracking
    private volatile long playStartMs;
    private volatile long pauseStartMs;
    private volatile long totalPausedMs;

    // seek support
    private volatile long seekTargetMs = -1;
    private volatile long seekDisplayMs = -1;
    private volatile long bytesConsumedFromStream = 0;

    // melodify seamless next-track preloading
    public static final long PRELOAD_LEAD_MS = 20000;
    private volatile boolean melodifyEnabled;
    private volatile Runnable nearEndListener;
    private volatile boolean nearEndFired;
    private volatile PreloadedTrack preloaded;
    private volatile boolean usePreload;

    // crossfade: overlap fade-out/fade-in between current and preloaded next track
    public static final long CROSSFADE_MS = 12000;
    private volatile boolean crossfadeActive;
    private volatile boolean crossfadeTakeover;
    private volatile SourceDataLine crossfadeLine;
    private volatile SourceDataLine pendingLine;
    private volatile long preloadOffsetBytes;
    private volatile long pendingPreloadOffset;
    private volatile Runnable crossfadeCallback;
    private volatile Runnable onCrossfadeTrackListener;

    public void play(SongInfo song, String url) {
        play(song, url, null, false);
    }

    public void play(SongInfo song, String url, Runnable onNaturalEnd) {
        play(song, url, onNaturalEnd, false);
    }

    public void play(SongInfo song, String url, Runnable onNaturalEnd, boolean usePreload) {
        long offset = usePreload ? pendingPreloadOffset : 0;
        this.pendingPreloadOffset = 0;
        playInternalWithState(song, url, onNaturalEnd, usePreload, offset);
    }

    private void playInternalWithState(SongInfo song, String url, Runnable onNaturalEnd,
                                       boolean usePreload, long preloadOffset) {
        stop();
        long generation = playbackGeneration.incrementAndGet();
        this.currentSong = song;
        this.currentUrl = url;
        this.usePreload = usePreload;
        this.preloadOffsetBytes = preloadOffset;
        this.nearEndFired = false;
        this.crossfadeTakeover = false;
        this.crossfadeCallback = onNaturalEnd;
        this.state.set(State.LOADING);
        this.paused = false;
        this.totalPausedMs = 0;
        this.playStartMs = System.currentTimeMillis();
        this.seekTargetMs = -1;
        this.seekDisplayMs = -1;
        playbackThread = new Thread(() -> playInternal(url, generation, song, onNaturalEnd), "MusicPlayer-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void pause() {
        if (state.get() == State.PLAYING && currentLine != null) {
            paused = true;
            pauseStartMs = System.currentTimeMillis();
            currentLine.stop();
            state.set(State.PAUSED);
        }
    }

    public void resume() {
        if (state.get() == State.PAUSED && currentLine != null) {
            paused = false;
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            currentLine.start();
            state.set(State.PLAYING);
        }
    }

    public void stop() {
        playbackGeneration.incrementAndGet();
        spectrumSnapshot.set(new float[0]);
        analysisSampleCount = 0;
        state.set(State.STOPPED);
        paused = false;
        seekTargetMs = -1;
        seekDisplayMs = -1;
        crossfadeActive = false;
        crossfadeTakeover = true;
        crossfadeLine = null;
        currentSong = null;
        currentUrl = null;
        if (currentLine != null) {
            try { currentLine.stop(); } catch (Exception ignored) {}
            try { currentLine.flush(); } catch (Exception ignored) {}
            try { currentLine.close(); } catch (Exception ignored) {}
            currentLine = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void togglePause() {
        if (state.get() == State.PLAYING) {
            pause();
        } else if (state.get() == State.PAUSED) {
            resume();
        }
    }

    // ---- Melodify seamless next-track preloading ----

    public void setMelodifyEnabled(boolean enabled) {
        this.melodifyEnabled = enabled;
        if (!enabled) {
            clearPreload();
        }
    }

    public boolean isMelodifyEnabled() {
        return melodifyEnabled;
    }

    public void setNearEndListener(Runnable listener) {
        this.nearEndListener = listener;
    }

    public void setOnCrossfadeTrackListener(Runnable listener) {
        this.onCrossfadeTrackListener = listener;
    }

    public boolean isPreloadedFor(SongInfo song) {
        PreloadedTrack pre = preloaded;
        return pre != null && pre.pcm != null && song != null && pre.song.id == song.id;
    }

    public String getPreloadedUrl(SongInfo song) {
        PreloadedTrack pre = preloaded;
        return (pre != null && song != null && pre.song.id == song.id) ? pre.url : null;
    }

    public void clearPreload() {
        this.preloaded = null;
    }

    public void preloadNext(SongInfo song, String url) {
        if (song == null || url == null || url.isBlank()) return;
        if (isPreloadedFor(song)) return;
        clearPreload();
        long generation = playbackGeneration.get();
        Thread thread = new Thread(() -> {
            PreloadedTrack track = null;
            try {
                track = decodePreload(song, url, generation);
            } catch (Exception e) {
                System.err.println("[MusicPlayer] Preload failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (track != null && generation == playbackGeneration.get()
                    && state.get() != State.STOPPED && melodifyEnabled) {
                this.preloaded = track;
                System.out.println("[MusicPlayer] Preloaded: " + song.name);
            }
        }, "MusicPlayer-Preload");
        thread.setDaemon(true);
        thread.start();
    }

    private PreloadedTrack decodePreload(SongInfo song, String url, long generation) throws Exception {
        MusicHttp.StreamResponse response = MusicHttp.getInputStream(URI.create(url));
        BufferedInputStream bis = new BufferedInputStream(response.body());
        AudioInputStream rawStream = AudioSystem.getAudioInputStream(bis);
        try {
            AudioFormat baseFormat = rawStream.getFormat();
            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );
            AudioInputStream ais = AudioSystem.getAudioInputStream(decoded, rawStream);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = ais.read(buf)) != -1) {
                    if (generation != playbackGeneration.get() || state.get() == State.STOPPED) {
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                if (song.duration <= 0) {
                    long frames = rawStream.getFrameLength();
                    if (frames > 0) {
                        song.duration = (long) (frames / baseFormat.getSampleRate() * 1000);
                    }
                }
                return new PreloadedTrack(song, url, decoded, out.toByteArray());
            } finally {
                try { ais.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { rawStream.close(); } catch (Exception ignored) {}
        }
    }

    // ---- Crossfade between current track and preloaded next ----

    private void startCrossfade(PreloadedTrack track, long generation, long fadeMs) {
        if (track == null || track.pcm == null || track.pcm.length == 0) return;
        crossfadeActive = true;
        Thread thread = new Thread(() -> runCrossfade(track, generation, fadeMs), "MusicPlayer-Crossfade");
        thread.setDaemon(true);
        thread.start();
    }

    private void runCrossfade(PreloadedTrack track, long generation, long fadeMs) {
        SourceDataLine bLine = null;
        long consumed = 0;
        boolean fadeCompleted = false;
        try {
            bLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, track.format));
            bLine.open(track.format);
            crossfadeLine = bLine;
            bLine.start();
            ByteArrayInputStream in = new ByteArrayInputStream(track.pcm);
            byte[] buf = new byte[4096];
            int channels = track.format.getChannels();
            long start = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) != -1) {
                if (generation != playbackGeneration.get() || state.get() == State.STOPPED
                        || crossfadeLine != bLine) {
                    break;
                }
                long elapsed = System.currentTimeMillis() - start;
                float t = Math.max(0f, Math.min(1f, (float) elapsed / fadeMs));
                applyDigitalGain(buf, n, volume * t, channels);
                analyzePcm(buf, n, channels, generation);
                bLine.write(buf, 0, n);
                consumed += n;
                // keep the continue-offset in sync at all times, so a natural
                // end of the current track (even before the fade completes)
                // resumes the preload from exactly where we stopped.
                this.pendingPreloadOffset = consumed;
                SourceDataLine main = currentLine;
                if (main != null && main != bLine) {
                    setLineGain(main, volume * (1f - t));
                }
                if (t >= 1f) {
                    fadeCompleted = true;
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Crossfade failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            crossfadeLine = null;
            crossfadeActive = false;
            // If the fade finished first, take over playback of the preloaded
            // track right here on this thread, reusing bLine. No new thread,
            // no new SourceDataLine, no gap: the buffered audio keeps flowing.
            if (fadeCompleted && generation == playbackGeneration.get()
                    && consumed > 0) {
                long gen = playbackGeneration.incrementAndGet();
                this.pendingPreloadOffset = consumed;
                this.currentSong = track.song;
                this.currentUrl = track.url;
                this.usePreload = true;
                this.preloadOffsetBytes = consumed;
                this.nearEndFired = false;
                this.crossfadeTakeover = false;
                this.paused = false;
                this.totalPausedMs = 0;
                this.playStartMs = System.currentTimeMillis();
                this.seekTargetMs = -1;
                this.seekDisplayMs = -1;
                this.playbackThread = Thread.currentThread();
                this.state.set(State.PLAYING);
                // hand the crossfade line to the resumed playback
                this.pendingLine = bLine;
                bLine = null;
                // let the UI switch to the new track (queue index / lyrics)
                Runnable notifier = onCrossfadeTrackListener;
                if (notifier != null) {
                    try { notifier.run(); } catch (Exception e) {
                        System.err.println("[MusicPlayer] Crossfade track switch failed: " + e.getMessage());
                    }
                }
                playInternal(track.url, gen, track.song, crossfadeCallback);
                return;
            }
            if (bLine != null) {
                // drain the line's buffer before closing so the preloaded audio
                // already fed to the device keeps playing; otherwise the takeover
                // drops that tail and the transition stutters.
                try { bLine.drain(); } catch (Exception ignored) {}
                try { bLine.stop(); } catch (Exception ignored) {}
                try { bLine.flush(); } catch (Exception ignored) {}
                try { bLine.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void setLineGain(SourceDataLine line, float gain) {
        if (line == null) return;
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float g = Math.max(0.0001f, Math.min(1f, gain));
                float dB = (float) (Math.log(g) / Math.log(10.0) * 20.0);
                dB = Math.max(gainControl.getMinimum(), Math.min(dB, gainControl.getMaximum()));
                gainControl.setValue(dB);
            }
        } catch (Exception ignored) { }
    }

    private void applyDigitalGain(byte[] pcm, int length, float gain, int channels) {
        int frameSize = channels * 2;
        int frames = length / frameSize;
        for (int frame = 0; frame < frames; frame++) {
            int frameOffset = frame * frameSize;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * 2;
                short sample = (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
                short scaled = (short) (sample * gain);
                pcm[offset] = (byte) (scaled & 0xFF);
                pcm[offset + 1] = (byte) ((scaled >>> 8) & 0xFF);
            }
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0f, Math.min(1f, vol));
        applyVolume();
    }

    public void seekToMs(long targetMs) {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return;
        State s = state.get();
        if (s != State.PLAYING && s != State.PAUSED) return;
        targetMs = Math.max(0, Math.min(targetMs, song.duration));
        seekDisplayMs = targetMs;
        seekTargetMs = targetMs;
    }

    public void prevSongFallback() {
        SongInfo song = currentSong;
        if (song == null) return;
        seekToMs(0);
    }

    public void nextSongFallback() {
        // No-op without queue context
    }

    public float getVolume() { return volume; }
    public State getState() { return state.get(); }
    public SongInfo getCurrentSong() { return currentSong; }

    public float getProgress() {
        SongInfo song = currentSong;
        if (song == null || song.duration <= 0) return 0f;
        long pendingSeek = seekDisplayMs;
        if (pendingSeek >= 0) {
            return Math.max(0f, Math.min(1f, (float) pendingSeek / song.duration));
        }
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0f;
        long now = System.currentTimeMillis();
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        long elapsed = now - playStartMs - paused;
        return Math.max(0f, Math.min(1f, (float) elapsed / song.duration));
    }

    public long getCurrentPositionMs() {
        if (currentSong == null) return 0;
        long pendingSeek = seekDisplayMs;
        if (pendingSeek >= 0) return pendingSeek;
        long now = System.currentTimeMillis();
        State s = state.get();
        if (s == State.STOPPED || s == State.LOADING) return 0;
        long paused = (s == State.PAUSED) ? totalPausedMs + (now - pauseStartMs) : totalPausedMs;
        return Math.max(0, now - playStartMs - paused);
    }

    public float[] getSpectrumSnapshot() {
        float[] snapshot = spectrumSnapshot.get();
        return snapshot.length == 0 ? snapshot : snapshot.clone();
    }

    private void analyzePcm(byte[] buffer, int length, int channels, long generation) {
        if (generation != playbackGeneration.get() || channels <= 0) return;
        synchronized (analysisSamples) {
            int frameSize = channels * 2;
            for (int offset = 0; offset + frameSize <= length; offset += frameSize) {
                float sample = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int index = offset + channel * 2;
                    short value = (short) ((buffer[index] & 0xFF) | (buffer[index + 1] << 8));
                    sample += value / 32768f;
                }
                analysisSamples[analysisSampleCount++] = sample / channels;
                if (analysisSampleCount == ANALYSIS_WINDOW) {
                    publishSpectrum(generation);
                    analysisSampleCount = 0;
                }
            }
        }
    }

    private void publishSpectrum(long generation) {
        if (generation != playbackGeneration.get()) return;
        float[] result = new float[SPECTRUM_BARS];
        for (int band = 0; band < SPECTRUM_BARS; band++) {
            double real = 0;
            double imaginary = 0;
            double frequency = (band + 1) / (double) (SPECTRUM_BARS + 1);
            for (int sample = 0; sample < ANALYSIS_WINDOW; sample++) {
                double angle = 2 * Math.PI * frequency * sample;
                real += analysisSamples[sample] * Math.cos(angle);
                imaginary -= analysisSamples[sample] * Math.sin(angle);
            }
            double magnitude = Math.sqrt(real * real + imaginary * imaginary) / ANALYSIS_WINDOW;
            result[band] = (float) Math.max(0, Math.min(1, Math.log1p(magnitude * 24) / Math.log(25)));
        }
        spectrumSnapshot.set(result);
    }

    private void playInternal(String url, long generation, SongInfo song, Runnable onNaturalEnd) {
        SourceDataLine localLine = null;
        boolean reachedEnd = false;
        boolean naturalEnd = false;
        boolean usePreload = this.usePreload;
        this.usePreload = false;
        try {
            System.out.println("[MusicPlayer] Starting playback: " + url);
            AudioInputStream rawStream = null;
            AudioFormat decoded = null;
            InputStream pcmSource = null;
            long preloadSkipMs = 0;
            if (usePreload) {
                PreloadedTrack pre = preloaded;
                if (pre != null && pre.song.id == song.id && pre.pcm != null) {
                    decoded = pre.format;
                    ByteArrayInputStream bin = new ByteArrayInputStream(pre.pcm);
                    long offset = preloadOffsetBytes;
                    this.preloadOffsetBytes = 0;
                    if (offset > 0) {
                        long skipped = 0;
                        byte[] skipBuf = new byte[8192];
                        while (skipped < offset) {
                            if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;
                            int s = bin.read(skipBuf, 0, (int) Math.min(skipBuf.length, offset - skipped));
                            if (s < 0) break;
                            skipped += s;
                        }
                        long frameSize = decoded.getFrameSize();
                        long sampleRate = (long) decoded.getSampleRate();
                        if (frameSize > 0 && sampleRate > 0) {
                            preloadSkipMs = skipped * 1000L / (sampleRate * frameSize);
                        }
                    }
                    pcmSource = bin;
                    clearPreload();
                }
            }
            if (decoded == null) {
                MusicHttp.StreamResponse response = MusicHttp.getInputStream(URI.create(url));
                BufferedInputStream bis = new BufferedInputStream(response.body());
                rawStream = AudioSystem.getAudioInputStream(bis);
                AudioFormat baseFormat = rawStream.getFormat();

                decoded = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false
                );

                pcmSource = AudioSystem.getAudioInputStream(decoded, rawStream);

                // compute duration from audio stream
                if (currentSong != null) {
                    long frames = rawStream.getFrameLength();
                    if (frames > 0) {
                        currentSong.duration = (long)(frames / baseFormat.getSampleRate() * 1000);
                    } else {
                        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                        if (contentLength > 0) {
                            int bytesPerSec = (int)(baseFormat.getSampleRate() * baseFormat.getFrameSize());
                            if (bytesPerSec > 0) {
                                currentSong.duration = (contentLength * 1000L) / bytesPerSec;
                            }
                        }
                    }
                    System.out.println("[MusicPlayer] Duration: " + currentSong.duration + "ms");
                }
            }

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded);
            SourceDataLine handed = pendingLine;
            if (handed != null && handed.getFormat().matches(decoded)) {
                // reuse the crossfade line handed over at the transition so the
                // buffered audio keeps playing without opening a new line
                pendingLine = null;
                localLine = handed;
                currentLine = localLine;
                applyVolume();
                if (seekTargetMs < 0 && !paused) {
                    try { localLine.start(); } catch (Exception ignored) {}
                }
            } else {
                if (handed != null) {
                    pendingLine = null;
                    try { handed.stop(); } catch (Exception ignored) {}
                    try { handed.flush(); } catch (Exception ignored) {}
                    try { handed.close(); } catch (Exception ignored) {}
                }
                localLine = (SourceDataLine) AudioSystem.getLine(info);
                localLine.open(decoded);
                currentLine = localLine;
                applyVolume();
                if (seekTargetMs < 0 && !paused) {
                    localLine.start();
                }
            }

            long displayedPosition = seekDisplayMs;
            if (preloadSkipMs > 0) {
                // resuming the preloaded track from an offset: seed the clock so
                // progress/lyrics line up with the actual audio position
                playStartMs = System.currentTimeMillis() - preloadSkipMs;
            } else {
                playStartMs = System.currentTimeMillis() - Math.max(0, displayedPosition);
            }
            totalPausedMs = 0;
            state.set(State.PLAYING);
            bytesConsumedFromStream = 0;

            int bytesPerFrame = decoded.getFrameSize();
            int sampleRate = (int) decoded.getSampleRate();
            boolean fadeInPending = false;

            long initialSeek = seekTargetMs;
            if (initialSeek >= 0) {
                seekTargetMs = -1;
                // 先取整数帧数再乘帧大小，保证跳过量是帧对齐的，否则 16-bit 采样错位会产生噪音
                long bytesToSkip = (long) (initialSeek / 1000.0 * sampleRate) * bytesPerFrame;
                byte[] skipBuffer = new byte[8192];
                while (bytesConsumedFromStream < bytesToSkip) {
                    long remaining = bytesToSkip - bytesConsumedFromStream;
                    int skipped = pcmSource.read(skipBuffer, 0, (int) Math.min(skipBuffer.length, remaining));
                    if (skipped < 0) break;
                    bytesConsumedFromStream += skipped;
                }
                playStartMs = System.currentTimeMillis() - initialSeek;
                totalPausedMs = 0;
                seekDisplayMs = -1;
                fadeInPending = true;
                if (paused) {
                    pauseStartMs = System.currentTimeMillis();
                } else {
                    localLine.start();
                }
            }

            byte[] buffer = new byte[4096];
            byte[] pcmBuffer = new byte[buffer.length + bytesPerFrame - 1];
            int pendingPcmLength = 0;
            int bytesRead;
            while (true) {
                if (crossfadeTakeover) break;
                bytesRead = pcmSource.read(buffer, 0, buffer.length);
                if (bytesRead == -1) {
                    reachedEnd = true;
                    break;
                }
                bytesConsumedFromStream += bytesRead;
                if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;

                // Handle seek
                long seek = seekTargetMs;
                if (seek >= 0) {
                    seekTargetMs = -1;
                    long absoluteTargetBytes = (long) (seek / 1000.0 * sampleRate) * bytesPerFrame;
                    long relativeSkip = absoluteTargetBytes - bytesConsumedFromStream;

                    localLine.stop();
                    localLine.flush();
                    pendingPcmLength = 0;
                    analysisSampleCount = 0;
                    spectrumSnapshot.set(new float[0]);

                    if (relativeSkip > 0) {
                        // Forward seek: skip bytes in current stream
                        long skipped = 0;
                        byte[] skipBuf = new byte[8192];
                        while (skipped < relativeSkip) {
                            if (Thread.currentThread().isInterrupted() || state.get() == State.STOPPED) break;
                            long toRead = Math.min(skipBuf.length, relativeSkip - skipped);
                            int n = pcmSource.read(skipBuf, 0, (int) toRead);
                            if (n < 0) break;
                            skipped += n;
                            bytesConsumedFromStream += n;
                        }
                    } else if (relativeSkip < 0) {
                        // Backward seek: can't rewind HTTP stream, restart from URL
                        seekTargetMs = seek; // preserve for restart
                        break;
                    }

                    playStartMs = System.currentTimeMillis() - seek;
                    totalPausedMs = 0;
                    seekDisplayMs = -1;
                    fadeInPending = true;
                    if (paused) {
                        pauseStartMs = System.currentTimeMillis();
                    } else {
                        localLine.start();
                    }
                    continue;
                }

                if (paused) {
                    Thread.sleep(50);
                    continue;
                }

                // Melodify: preload next track when approaching the end
                if (melodifyEnabled && !nearEndFired && nearEndListener != null
                        && song != null && song.duration > 0) {
                    long position = Math.max(0, System.currentTimeMillis() - playStartMs - totalPausedMs);
                    if (song.duration - position <= PRELOAD_LEAD_MS) {
                        nearEndFired = true;
                        try {
                            nearEndListener.run();
                        } catch (Exception e) {
                            System.err.println("[MusicPlayer] Preload trigger failed: " + e.getMessage());
                        }
                    }
                }
                // Melodify: start crossfade when preload is ready and close to the end
                if (melodifyEnabled && !crossfadeActive && !crossfadeTakeover
                        && preloaded != null && song != null && song.duration > 0
                        && generation == playbackGeneration.get()) {
                    long position = Math.max(0, System.currentTimeMillis() - playStartMs - totalPausedMs);
                    long remaining = song.duration - position;
                    if (remaining <= CROSSFADE_MS) {
                        long fadeMs = Math.max(500, Math.min(CROSSFADE_MS, remaining));
                        startCrossfade(preloaded, generation, fadeMs);
                    }
                }

                System.arraycopy(buffer, 0, pcmBuffer, pendingPcmLength, bytesRead);
                int pcmLength = pendingPcmLength + bytesRead;
                int completeLength = pcmLength - pcmLength % bytesPerFrame;
                if (completeLength > 0) {
                    if (fadeInPending) {
                        applyFadeIn(pcmBuffer, completeLength, decoded.getChannels());
                        fadeInPending = false;
                    }
                    analyzePcm(pcmBuffer, completeLength, decoded.getChannels(), generation);
                    localLine.write(pcmBuffer, 0, completeLength);
                }
                pendingPcmLength = pcmLength - completeLength;
                if (pendingPcmLength > 0) {
                    System.arraycopy(pcmBuffer, completeLength, pcmBuffer, 0, pendingPcmLength);
                }
            }

            if (pcmSource != null) {
                try { pcmSource.close(); } catch (Exception ignored) {}
            }
            if (rawStream != null) {
                try { rawStream.close(); } catch (Exception ignored) {}
            }

            // Check for pending backward seek — restart stream from URL
            long pendingSeek = seekTargetMs;
            if (pendingSeek >= 0 && state.get() != State.STOPPED) {
                seekTargetMs = pendingSeek;
                System.out.println("[MusicPlayer] Backward seek to " + pendingSeek + "ms, restarting stream");
                playInternal(url, generation, song, onNaturalEnd); // recursive restart
                return;
            }

            naturalEnd = reachedEnd
                    && state.get() == State.PLAYING
                    && generation == playbackGeneration.get();
            if (naturalEnd) {
                state.set(State.STOPPED);
                spectrumSnapshot.set(new float[0]);
            }
            System.out.println("[MusicPlayer] Playback finished");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            if (state.get() != State.STOPPED && generation == playbackGeneration.get()) {
                state.set(State.STOPPED);
                spectrumSnapshot.set(new float[0]);
            }
        } finally {
            if (localLine != null) {
                try {
                    if (naturalEnd) {
                        localLine.drain();
                    } else {
                        localLine.stop();
                        localLine.flush();
                    }
                } catch (Exception ignored) {}
                try { localLine.close(); } catch (Exception ignored) {}
                if (currentLine == localLine) currentLine = null;
            }
            if (naturalEnd && generation == playbackGeneration.get() && onNaturalEnd != null) {
                onNaturalEnd.run();
            }
        }
    }

    private void applyFadeIn(byte[] pcm, int length, int channels) {
        int frameSize = channels * 2;
        int frames = Math.min(length / frameSize, 1024);
        for (int frame = 0; frame < frames; frame++) {
            float gain = frame / (float) frames;
            int frameOffset = frame * frameSize;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * 2;
                short sample = (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
                short faded = (short) (sample * gain);
                pcm[offset] = (byte) (faded & 0xFF);
                pcm[offset + 1] = (byte) ((faded >>> 8) & 0xFF);
            }
        }
    }

    private void applyVolume() {
        if (currentLine != null && currentLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) currentLine.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log(Math.max(volume, 0.0001)) / Math.log(10.0) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(dB, gain.getMaximum())));
        }
    }

    private record PreloadedTrack(SongInfo song, String url, AudioFormat format, byte[] pcm) { }
}
