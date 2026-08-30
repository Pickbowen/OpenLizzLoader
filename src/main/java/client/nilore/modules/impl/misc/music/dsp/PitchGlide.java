package client.nilore.modules.impl.misc.music.dsp;

/**
 * Pitch glide (portamento) over a PCM buffer — an independent implementation of
 * the "frequency sliding / glide transition" effect (no code taken from the
 * unlicensed audio_transport project).
 *
 * Resamples interleaved 16-bit PCM while linearly sliding the playback ratio
 * from fromRatio to toRatio across the whole buffer. ratio < 1 lowers pitch,
 * ratio > 1 raises it. Gliding 0.94 -> 1.0 makes the start of a track "pull
 * up" into normal pitch, the classic DJ / seamless-transition move.
 */
public final class PitchGlide {

    private PitchGlide() { }

    /**
     * @param pcm       interleaved 16-bit little-endian PCM
     * @param channels  channel count
     * @param fromRatio start playback ratio (e.g. 0.92)
     * @param toRatio   end playback ratio (e.g. 1.0)
     * @return glided PCM (length changes with the average ratio)
     */
    public static byte[] glide(byte[] pcm, int channels, float fromRatio, float toRatio) {
        if (pcm == null || pcm.length < 4) return pcm;
        int frameSize = channels * 2;
        if (frameSize <= 0) return pcm;
        int frames = pcm.length / frameSize;
        if (frames <= 2) return pcm;

        float avg = (fromRatio + toRatio) * 0.5f;
        int outFrames = Math.max(1, (int) (frames / avg));
        byte[] out = new byte[outFrames * frameSize];

        double srcPos = 0.0;
        for (int o = 0; o < outFrames; o++) {
            double t = outFrames <= 1 ? 0.0 : (double) o / (outFrames - 1);
            double ratio = fromRatio + (toRatio - fromRatio) * t;
            int i0 = (int) srcPos;
            if (i0 >= frames) i0 = frames - 1;
            int i1 = Math.min(frames - 1, i0 + 1);
            double frac = srcPos - i0;

            for (int ch = 0; ch < channels; ch++) {
                int off0 = i0 * frameSize + ch * 2;
                int off1 = i1 * frameSize + ch * 2;
                short s0 = (short) ((pcm[off0] & 0xFF) | (pcm[off0 + 1] << 8));
                short s1 = (short) ((pcm[off1] & 0xFF) | (pcm[off1 + 1] << 8));
                short s = (short) (s0 + (short) ((s1 - s0) * frac));
                int oo = o * frameSize + ch * 2;
                out[oo] = (byte) (s & 0xFF);
                out[oo + 1] = (byte) ((s >>> 8) & 0xFF);
            }
            srcPos += ratio;
        }
        return out;
    }
}
