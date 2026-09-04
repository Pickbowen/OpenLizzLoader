package client.nilore.modules.impl.misc.music.dsp;

/**
 * pychorus-style chorus / high-energy section detection (MIT algorithm,
 * reimplemented in Java). Pipeline faithful to vivjay30/pychorus:
 *
 * mono + downsampled PCM -> STFT (hann) -> chroma -> 3x3 Laplacian pre-filter
 * -> self-similarity matrix -> time-lag matrix -> horizontal Sobel line
 * detection.
 *
 * Section search is the frame-aligned equivalent of pychorus' beat-tracked
 * chunking: the dominant repeat period is read from the self-similarity
 * diagonal, the track is chunked on that period with a phase-aligning pass,
 * and the most-repeated chunk (with adjacent similar chunks merged) is the
 * chorus.
 *
 * Returns the detected section (start / duration in seconds) plus a per-frame
 * repeat score that UI can use to mark chorus / high-energy regions.
 */
public final class ChorusDetector {

    private static final int TARGET_RATE = 22050;
    private static final int NFFT = 2048;
    private static final int HOP = 1024;
    private static final int MAX_FRAMES = 1500;
    private static final double MIN_CHORUS_SEC = 8.0;
    private static final double MAX_CHORUS_SEC = 90.0;
    // pychorus constants
    private static final float REPEAT_SIM_THRESHOLD = 0.8f;   // REPEAT_SIMILARITY_THRESHOLD
    private static final float MERGE_SIM_THRESHOLD = 0.6f;
    // repeat-period search window (seconds)
    private static final double MIN_PERIOD_SEC = 2.5;
    private static final double MAX_PERIOD_SEC = 60.0;
    private static final float PERIOD_MIN_SCORE = 0.25f;
    // weight of the Sobel boundary signal in the phase-aligning score
    private static final float BOUNDARY_WEIGHT = 0.05f;

    private ChorusDetector() { }

    public static final class Result {
        public final float chorusStartSec;
        public final float chorusDurationSec;
        public final float[] repeatScore;
        public final float hopSec;

        Result(float chorusStartSec, float chorusDurationSec, float[] repeatScore, float hopSec) {
            this.chorusStartSec = chorusStartSec;
            this.chorusDurationSec = chorusDurationSec;
            this.repeatScore = repeatScore;
            this.hopSec = hopSec;
        }
    }

    public static Result detect(float[] monoPcm, int sampleRate) {
        float[] pcm = toMonoRate(monoPcm, sampleRate, TARGET_RATE);
        int rate = TARGET_RATE;
        float hopSec = (float) HOP / rate;

        int rawFrames = Math.max(1, (pcm.length - NFFT) / HOP + 1);
        int step = 1;
        if (rawFrames > MAX_FRAMES) {
            step = (int) Math.ceil((double) rawFrames / MAX_FRAMES);
        }
        int frames = Math.max(1, (int) Math.ceil((double) rawFrames / step));

        // 1) STFT -> chroma (12 pitch classes)
        float[][] chroma = new float[frames][12];
        float[] window = hann(NFFT);
        float[] re = new float[NFFT];
        float[] im = new float[NFFT];
        for (int fi = 0; fi < frames; fi++) {
            int base = fi * step * HOP;
            for (int n = 0; n < NFFT; n++) {
                int idx = base + n;
                re[n] = idx < pcm.length ? pcm[idx] * window[n] : 0f;
                im[n] = 0f;
            }
            fft(re, im, false);
            float[] c = chroma[fi];
            for (int k = 1; k < NFFT / 2; k++) {
                float freq = (float) k * rate / NFFT;
                if (freq < 60f || freq > 5000f) continue;
                float midi = 69f + 12f * log2(freq / 440f);
                int pc = ((int) Math.round(midi)) % 12;
                if (pc < 0) pc += 12;
                float energy = re[k] * re[k] + im[k] * im[k];
                c[pc] += energy;
            }
            normalize12(c);
        }

        // 2) Laplacian pre-filter: enhances chroma edges (note onsets) so
        //    repeated sections appear as blocks in the self-similarity matrix.
        float[][] lp = laplacian(chroma);

        // 3) Self-similarity matrix: cosine similarity between every pair of frames.
        int n = frames;
        float[][] ssm = new float[n][n];
        for (int i = 0; i < n; i++) {
            float[] ci = lp[i];
            for (int j = i; j < n; j++) {
                float sim = cosine(ci, lp[j]);
                ssm[i][j] = sim;
                ssm[j][i] = sim;
            }
        }

        // 4) Time-lag matrix: tl[lag][t] = ssm[t][t+lag]. A repeated chorus
        //    shows up as strong horizontal bands at the repeat interval.
        //    lag 0 is pure self-correlation (tl[0][t] = 1) and only produces
        //    spurious edge peaks, so it is skipped.
        float[][] tl = new float[n][n];
        for (int t = 0; t < n; t++) {
            for (int lag = 1; lag < n && t + lag < n; lag++) {
                tl[lag][t] = ssm[t][t + lag];
            }
        }

        // 5) Horizontal Sobel filter: line detection for the start/end
        //    boundaries of repeated sections (pychorus' Sobel step). The
        //    strongest response per time frame marks repeated-section edges.
        float[][] sob = sobel(tl);
        float[] boundary = new float[n];
        for (int t = 0; t < n; t++) {
            float m = 0f;
            for (int lag = 0; lag < n; lag++) {
                float v = sob[lag][t];
                if (v > m) m = v;
            }
            boundary[t] = m;
        }

        // 6) Per-frame repeat score: how many other moments sound like this one.
        //    The chorus repeats most often, so its frames score highest.
        float[] repeatScore = new float[n];
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int lag = 1; lag < n && i + lag < n; lag++) {
                if (ssm[i][i + lag] > REPEAT_SIM_THRESHOLD) count++;
            }
            repeatScore[i] = count;
        }
        float[] smoothScore = smooth(repeatScore, Math.max(3, n / 200));

        // 7) Dominant repeat period from the self-similarity diagonal. The
        //    chorus is the most-repeated section, so its period is the strongest
        //    mean self-similarity within a sane musical window.
        float[] selfSim = smooth(selfSimilarityByLag(ssm), Math.max(3, n / 200));
        int minPeriod = Math.max(10, (int) (MIN_PERIOD_SEC / hopSec / step));
        int maxPeriod = Math.min(n / 2, (int) (MAX_PERIOD_SEC / hopSec / step));
        int period = 0;
        float periodScore = -1f;
        for (int lag = minPeriod; lag <= maxPeriod && lag < n; lag++) {
            if (selfSim[lag] > periodScore) {
                periodScore = selfSim[lag];
                period = lag;
            }
        }

        float startSec = 0f;
        float durSec = 0f;

        // 8) Chunk the track on the repeat period and align the phase so that
        //    the repeated chorus falls on whole chunks. The best shift maximises
        //    the phase-aligned similarity of neighbouring chunks.
        if (period > 0 && periodScore > PERIOD_MIN_SCORE) {
            int bestShift = 0;
            float bestPhaseScore = -1f;
            for (int shift = 0; shift < period; shift++) {
                int K = (n - shift) / period;
                if (K < 2) break;
                float score = 0f;
                for (int k = 0; k + 1 < K; k++) {
                    score += alignedSimilarity(ssm, shift + k * period, shift + (k + 1) * period, period);
                    // small bonus for chunk boundaries that sit on strong
                    // repeated-section edges (from the Sobel line detection)
                    score += BOUNDARY_WEIGHT
                            * (boundary[shift + k * period] + boundary[Math.min(n - 1, shift + (k + 1) * period)]);
                }
                if (score > bestPhaseScore) {
                    bestPhaseScore = score;
                    bestShift = shift;
                }
            }

            // 9) Repeat voting: the chorus is the chunk that phase-aligns with
            //    the most other chunks.
            int K = (n - bestShift) / period;
            int[] repeatCount = new int[K];
            for (int a = 0; a < K; a++) {
                for (int b = a + 1; b < K; b++) {
                    int a0 = bestShift + a * period;
                    int a1 = Math.min(n, bestShift + (a + 1) * period);
                    float sim = alignedSimilarity(ssm, a0, a1, (b - a) * period);
                    if (sim > REPEAT_SIM_THRESHOLD) {
                        repeatCount[a]++;
                        repeatCount[b]++;
                    }
                }
            }
            // Prefer the candidate CLOSEST to 0:00: any chunk that repeats as
            // often as the max (within a small slack, so a near-max repeat still
            // counts as a genuine candidate) is a valid chorus start; among those
            // pick the one whose merged section starts earliest.
            int maxRepeat = 0;
            for (int c : repeatCount) if (c > maxRepeat) maxRepeat = c;
            int best = -1;
            if (maxRepeat > 0) {
                int accept = maxRepeat <= 2 ? maxRepeat : maxRepeat - 1;
                int bestCandidate = -1;
                for (int k = 0; k < K; k++) {
                    if (repeatCount[k] >= accept) { bestCandidate = k; break; }
                }
                if (bestCandidate >= 0) {
                    int maxFrames = (int) (MAX_CHORUS_SEC / hopSec / step) + 1;
                    best = bestCandidate;
                    int bestStart = Integer.MAX_VALUE;
                    for (int k = bestCandidate; k < K; k++) {
                        if (repeatCount[k] < accept) continue;
                        int candLo = bestShift + k * period;
                        int candHi = Math.min(n, bestShift + (k + 1) * period);
                        // mimic the merge pass: extend backwards over similar
                        // chunks, then evaluate the merged section's start
                        int lo = candLo, hi = candHi;
                        boolean changed = true;
                        while (changed) {
                            changed = false;
                            if (lo - period >= 0 && hi - (lo - period) <= maxFrames
                                    && segmentSimilarity(ssm, lo - period, lo, lo, hi) > MERGE_SIM_THRESHOLD) {
                                lo -= period;
                                changed = true;
                            }
                        }
                        if (hi - lo >= (int) (MIN_CHORUS_SEC / hopSec / step) && lo < bestStart) {
                            bestStart = lo;
                            best = k;
                        }
                    }
                }
            }

            if (best >= 0) {
                // 10) Merge adjacent similar chunks so a multi-repeat chorus is
                //     returned as one whole section.
                int lo = bestShift + best * period;
                int hi = Math.min(n, bestShift + (best + 1) * period);
                int maxFrames = (int) (MAX_CHORUS_SEC / hopSec / step) + 1;
                boolean changed = true;
                while (changed) {
                    changed = false;
                    if (lo - period >= 0 && hi - (lo - period) <= maxFrames
                            && segmentSimilarity(ssm, lo - period, lo, lo, hi) > MERGE_SIM_THRESHOLD) {
                        lo -= period;
                        changed = true;
                        continue;
                    }
                    if (hi + period <= n && hi + period - lo <= maxFrames
                            && segmentSimilarity(ssm, hi, hi + period, lo, hi) > MERGE_SIM_THRESHOLD) {
                        hi += period;
                        changed = true;
                    }
                }
                float s = lo * hopSec * step;
                float e = hi * hopSec * step;
                float d = e - s;
                if (d >= MIN_CHORUS_SEC) {
                    startSec = s;
                    durSec = Math.min(d, (float) MAX_CHORUS_SEC);
                }
            }
        }

        // 11) Fallback: if the repeat-period path found nothing (sparse or
        //     non-repeating audio), fall back to the longest run of high repeat
        //     score, then to the strongest Sobel boundary.
        if (durSec <= 0f) {
            float mean = mean(smoothScore);
            float std = std(smoothScore);
            float threshold = mean + 0.6f * std;
            int bestStart = Integer.MAX_VALUE;
            int bestLen = 0;
            int runStart = -1;
            for (int i = 0; i <= n; i++) {
                boolean above = i < n && smoothScore[i] > threshold;
                if (above && runStart < 0) {
                    runStart = i;
                } else if (!above && runStart >= 0) {
                    int len = i - runStart;
                    float sec = len * hopSec * step;
                    // among runs of comparable length (≈ top repeat energy),
                    // prefer the one whose section starts CLOSEST to 0:00
                    if (sec >= MIN_CHORUS_SEC && sec <= MAX_CHORUS_SEC
                            && len >= bestLen * 0.85f && runStart < bestStart) {
                        bestStart = runStart;
                        bestLen = len;
                    }
                    runStart = -1;
                }
            }
            if (bestStart >= 0) {
                startSec = bestStart * hopSec * step;
                durSec = bestLen * hopSec * step;
            }
            // else: no stable chorus on non-repeating audio - return 0 and let
            // the caller use its default transition window.
        }

        return new Result(startSec, durSec, smoothScore, hopSec * step);
    }

    // ---- helpers ----

    private static float[] toMonoRate(float[] pcm, int inRate, int outRate) {
        if (pcm == null || pcm.length == 0) return new float[0];
        if (inRate == outRate) return pcm.clone();
        double ratio = (double) outRate / inRate;
        int outLen = Math.max(1, (int) (pcm.length * ratio));
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i / ratio;
            int i0 = (int) src;
            int i1 = Math.min(pcm.length - 1, i0 + 1);
            float frac = (float) (src - i0);
            out[i] = pcm[i0] * (1f - frac) + pcm[i1] * frac;
        }
        return out;
    }

    private static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5f * (1f - (float) Math.cos(2 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    private static void normalize12(float[] c) {
        float sum = 0f;
        for (float v : c) sum += v;
        if (sum <= 1e-9f) return;
        for (int i = 0; i < c.length; i++) c[i] /= sum;
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 1e-9f || nb <= 1e-9f) return 0f;
        return dot / (float) Math.sqrt(na * nb);
    }

    private static float[] smooth(float[] in, int win) {
        win = Math.max(1, win);
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            int lo = Math.max(0, i - win);
            int hi = Math.min(in.length - 1, i + win);
            float sum = 0f;
            for (int j = lo; j <= hi; j++) sum += in[j];
            out[i] = sum / (hi - lo + 1);
        }
        return out;
    }

    private static float mean(float[] a) {
        float s = 0f;
        for (float v : a) s += v;
        return a.length == 0 ? 0f : s / a.length;
    }

    private static float std(float[] a) {
        float m = mean(a);
        float s = 0f;
        for (float v : a) { float d = v - m; s += d * d; }
        return a.length == 0 ? 0f : (float) Math.sqrt(s / a.length);
    }

    private static float log2(float v) {
        return (float) (Math.log(v) / Math.log(2.0));
    }

    // Mean self-similarity at each time lag: how strongly frames tend to match
    // the frame `lag` later. The chorus repeat period shows up as a peak here.
    private static float[] selfSimilarityByLag(float[][] ssm) {
        int n = ssm.length;
        float[] out = new float[n];
        for (int lag = 1; lag < n; lag++) {
            float sum = 0f;
            int cnt = 0;
            for (int t = 0; t + lag < n; t++) {
                sum += ssm[t][t + lag];
                cnt++;
            }
            out[lag] = cnt > 0 ? sum / cnt : 0f;
        }
        return out;
    }

    // 3x3 Laplacian kernel (pychorus) with zero padding.
    private static float[][] laplacian(float[][] x) {
        int rows = x.length;
        int cols = x[0].length;
        float[][] out = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float sum = 0f;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = i + di, nj = j + dj;
                        float v = (ni >= 0 && ni < rows && nj >= 0 && nj < cols) ? x[ni][nj] : 0f;
                        sum += (di == 0 && dj == 0) ? -8f * v : v;
                    }
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    // Horizontal Sobel filter (detects vertical edges), zero padding, magnitude.
    private static final float[][] SOBEL_H = {{-1f, 0f, 1f}, {-2f, 0f, 2f}, {-1f, 0f, 1f}};

    private static float[][] sobel(float[][] x) {
        int rows = x.length;
        int cols = x[0].length;
        float[][] out = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float sum = 0f;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int ni = i + di, nj = j + dj;
                        float v = (ni >= 0 && ni < rows && nj >= 0 && nj < cols) ? x[ni][nj] : 0f;
                        sum += SOBEL_H[di + 1][dj + 1] * v;
                    }
                }
                out[i][j] = Math.abs(sum);
            }
        }
        return out;
    }

    // Evenly sample a frame range down to at most 24 frames to bound the cost of
    // pairwise similarity (repeated section boundaries can be many frames wide).
    private static int[] sampleRange(int a0, int a1) {
        int len = a1 - a0;
        if (len <= 0) return new int[0];
        if (len <= 24) {
            int[] r = new int[len];
            for (int i = 0; i < len; i++) r[i] = a0 + i;
            return r;
        }
        int[] out = new int[24];
        for (int k = 0; k < 24; k++) out[k] = a0 + (int) ((long) k * (len - 1) / 23);
        return out;
    }

    // Phase-aligned similarity: mean ssm[i][i+offset] for frames i in [a0,a1).
    // With an aligned repeat period this is ~1.0 for repeated (chorus) chunks
    // and low for unrelated content - unlike all-pairs averaging it is not
    // diluted by misaligned melody pairs (identical choruses score ~0.99 here).
    private static float alignedSimilarity(float[][] ssm, int a0, int a1, int offset) {
        int[] sa = sampleRange(a0, a1);
        float sum = 0f;
        int cnt = 0;
        for (int i : sa) {
            int j = i + offset;
            if (j >= 0 && j < ssm.length) {
                sum += ssm[i][j];
                cnt++;
            }
        }
        return cnt == 0 ? 0f : sum / cnt;
    }

    // How well every frame of A is matched somewhere in B (directional).
    private static float maxMatchSimilarity(float[][] ssm, int a0, int a1, int b0, int b1) {
        int[] sa = sampleRange(a0, a1);
        int[] sb = sampleRange(b0, b1);
        float sum = 0f;
        for (int i : sa) {
            float best = 0f;
            for (int j : sb) {
                float s = ssm[i][j];
                if (s > best) best = s;
            }
            sum += best;
        }
        return sa.length == 0 ? 0f : sum / sa.length;
    }

    // Bidirectional repeat similarity: true only when B is both fully contained
    // in A and contains A. A one-way match (e.g. an intro chord that also
    // appears in the chorus) does not count as a repeat.
    private static float segmentSimilarity(float[][] ssm, int a0, int a1, int b0, int b1) {
        return Math.min(maxMatchSimilarity(ssm, a0, a1, b0, b1),
                        maxMatchSimilarity(ssm, b0, b1, a0, a1));
    }

    // iterative radix-2 FFT, in-place
    private static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            float ang = (float) (2 * Math.PI / len) * (inverse ? 1f : -1f);
            float wRe = (float) Math.cos(ang), wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len / 2; k++) {
                    float uRe = re[i + k], uIm = im[i + k];
                    float vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm;
                    float vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe;
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm;
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm;
                    float tRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = tRe;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
        }
    }
}
