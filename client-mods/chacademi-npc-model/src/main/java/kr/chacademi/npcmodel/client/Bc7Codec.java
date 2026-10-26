package kr.chacademi.npcmodel.client;

// PortableVFX(portable-vfx-runtime) Bc7Codec 복사본 — 같은 저장소의 BC7 인코더를 NPC 텍스처에도 쓴다. 원본을 고치면 이쪽도 같이.

import java.util.stream.IntStream;

/**
 * BC7 (BPTC) block encoder/decoder for particle textures.
 *
 * <p>Every block is first encoded in mode 6 (one RGBA line, 7-bit endpoints plus a p-bit, 16 steps), which suits
 * soft, continuously shaded VFX art. Blocks that one line cannot represent well (two distinct colours, hard
 * edges) are retried with the two-subset modes 1 (opaque RGB, 3-bit indices) and 7 (RGBA, 2-bit indices) over the
 * best-predicted partitions, and the lowest-error encoding is kept. The encoder runs once per texture on the client
 * and its result is cached on disk. Input and output use the interchange's bottom-up RGBA row order, which is
 * exactly what {@code glTexImage2D} received before, so UVs are unchanged. Blocks were verified bit-exact against
 * an independent reference BC7 decoder.</p>
 */
final class Bc7Codec {
    static final int BLOCK_BYTES = 16;
    private static final int[] WEIGHTS = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
    /** Nearest mode-6 index for a projected position quantised to 0..64. */
    private static final byte[] NEAREST = new byte[65];
    private static final int[] WEIGHTS2 = {0, 21, 43, 64}, WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    /** Two-subset partition shapes (bit p set = pixel p in subset 1) and the subset-1 anchor pixel. */
    private static final int[] PARTITIONS = {0xcccc, 0x8888, 0xeeee, 0xecc8, 0xc880, 0xfeec, 0xfec8, 0xec80, 0xc800, 0xffec, 0xfe80, 0xe800, 0xffe8, 0xff00, 0xfff0, 0xf000, 0xf710, 0x008e, 0x7100, 0x08ce, 0x008c, 0x7310, 0x3100, 0x8cce, 0x088c, 0x3110, 0x6666, 0x366c, 0x17e8, 0x0ff0, 0x718e, 0x399c, 0xaaaa, 0xf0f0, 0x5a5a, 0x33cc, 0x3c3c, 0x55aa, 0x9696, 0xa55a, 0x73ce, 0x13c8, 0x324c, 0x3bdc, 0x6996, 0xc33c, 0x9966, 0x0660, 0x0272, 0x04e4, 0x4e40, 0x2720, 0xc936, 0x936c, 0x39c6, 0x639c, 0x9336, 0x9cc6, 0x817e, 0xe718, 0xccf0, 0x0fcc, 0x7744, 0xee22};
    private static final int[] ANCHORS = {15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2, 15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6, 6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15};
    /** Weighted squared error (16 pixels x 4 channels, x256 scale) above which a block is retried with two subsets (~RMS 3). */
    private static final long HARD_BLOCK_ERROR = 16 * 4 * 9 * 256L; // errors are scaled by 256 (colour weighted by alpha + 1)
    private static final int PARTITION_CANDIDATES = 6;
    /**
     * Alpha error weight. Fully transparent texels must stay exactly 0 (and opaque ones 255): additive particles carry
     * HDR tints and bloom, so even alpha 1/255 across a sprite's empty area shows as a faint rectangle around the quad.
     */
    private static final int ALPHA_WEIGHT = 16;
    /** Penalty that makes "an empty texel gains alpha" lose against any encoding that keeps it at 0. */
    private static final long EMPTY_LEAK = 1L << 40;
    /** Snaps the endpoint alphas to exactly 0 / 255 when the block has such texels and the p-bit allows it. */
    private static void snapAlpha(int[] low, int[] high, int lowPbit, int highPbit, boolean hasZero, boolean hasFull) {
        int[] min = low[3] <= high[3] ? low : high, max = min == low ? high : low;
        int minP = min == low ? lowPbit : highPbit, maxP = max == low ? lowPbit : highPbit;
        if (hasZero && minP == 0) min[3] = 0;
        if (hasFull && maxP == 1) max[3] = 255;
    }
    static {
        for (int t = 0; t <= 64; t++) {
            int best = 0;
            for (int i = 1; i < 16; i++) if (Math.abs(WEIGHTS[i] - t) < Math.abs(WEIGHTS[best] - t)) best = i;
            NEAREST[t] = (byte) best;
        }
    }
    private Bc7Codec() { }

    /** Textures whose visible-pixel PSNR after compression falls below this stay uncompressed RGBA8. */
    static final double MIN_VISIBLE_PSNR = 38.0;
    /** Bump whenever encoder output or the quality rule changes; it is part of the disk-cache file name. */
    static final int ENCODER_VERSION = 2;

    /**
     * Compresses when the result is visually transparent: returns BC7 blocks, or {@code null} when the texture is one
     * of the rare noisy/hard-edged ones BC7 cannot reproduce closely (those stay RGBA8, quality first).
     */
    static byte[] compressIfFaithful(byte[] rgba, int width, int height) {
        byte[] blocks = encode(rgba, width, height);
        byte[] decoded = decode(blocks, width, height);
        if (visiblePsnr(rgba, decoded) < MIN_VISIBLE_PSNR) return null;
        // Never let empty texels gain alpha (a faint quad outline under HDR tint/bloom).
        for (int i = 3; i < rgba.length; i += 4) if (rgba[i] == 0 && decoded[i] != 0) return null;
        return blocks;
    }

    /** PSNR of what the shader shows (premultiplied colour and alpha), over pixels that are visible in either image. */
    static double visiblePsnr(byte[] source, byte[] decoded) {
        double squared = 0; long samples = 0;
        for (int i = 0; i < source.length; i += 4) {
            int sa = source[i + 3] & 0xff, da = decoded[i + 3] & 0xff;
            if (sa == 0 && da == 0) continue;
            for (int c = 0; c < 3; c++) {
                double d = (source[i + c] & 0xff) * sa / 255.0 - (decoded[i + c] & 0xff) * da / 255.0;
                squared += d * d;
            }
            squared += (double) (sa - da) * (sa - da);
            samples += 4;
        }
        return squared == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 * samples / squared);
    }

    static int compressedBytes(int width, int height) {
        return ((width + 3) / 4) * ((height + 3) / 4) * BLOCK_BYTES;
    }

    /** Encodes bottom-up RGBA8 to BC7 blocks (block rows also bottom-up, matching the GL upload). */
    static byte[] encode(byte[] rgba, int width, int height) {
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("RGBA size mismatch");
        int bw = (width + 3) / 4, bh = (height + 3) / 4;
        byte[] out = new byte[bw * bh * BLOCK_BYTES];
        IntStream.range(0, bh).parallel().forEach(by -> {
            var encoder = new Encoder();
            int[] px = encoder.px;
            for (int bx = 0; bx < bw; bx++) {
                for (int i = 0; i < 16; i++) {
                    int x = Math.min(bx * 4 + (i & 3), width - 1), y = Math.min(by * 4 + (i >> 2), height - 1);
                    int s = (y * width + x) * 4;
                    for (int c = 0; c < 4; c++) px[i * 4 + c] = rgba[s + c] & 0xff;
                }
                encoder.encodeBlock(out, (by * bw + bx) * BLOCK_BYTES);
            }
        });
        return out;
    }

    /** Decodes blocks written by {@link #encode} (used when the GPU lacks BPTC and for verification). */
    static byte[] decode(byte[] blocks, int width, int height) {
        int bw = (width + 3) / 4, bh = (height + 3) / 4;
        if (blocks.length != bw * bh * BLOCK_BYTES) throw new IllegalArgumentException("BC7 size mismatch");
        byte[] rgba = new byte[width * height * 4];
        IntStream.range(0, bh).parallel().forEach(by -> {
            int[] px = new int[64];
            for (int bx = 0; bx < bw; bx++) {
                decodeBlock(blocks, (by * bw + bx) * BLOCK_BYTES, px);
                for (int i = 0; i < 16; i++) {
                    int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                    if (x >= width || y >= height) continue;
                    int d = (y * width + x) * 4;
                    for (int c = 0; c < 4; c++) rgba[d + c] = (byte) px[i * 4 + c];
                }
            }
        });
        return rgba;
    }

    // ---- encoder ---------------------------------------------------------------------------------

    /** Per-thread scratch so the hot loop allocates nothing. */
    private static final class Encoder {
        final int[] px = new int[64], palette = new int[64];
        final int[] idx = new int[16], bestIdx = new int[16], q0 = new int[4], q1 = new int[4], bestQ0 = new int[4], bestQ1 = new int[4];
        final double[] mean = new double[4], cov = new double[16], axis = new double[4], next = new double[4], e0 = new double[4], e1 = new double[4];

        void encodeBlock(byte[] out, int offset) {
            boolean constant = true, hasZero = false, hasFull = false;
            for (int i = 0; i < 16; i++) { hasZero |= px[i * 4 + 3] == 0; hasFull |= px[i * 4 + 3] == 255; }
            for (int i = 4; i < 64 && constant; i++) constant = px[i] == px[i & 3];
            java.util.Arrays.fill(mean, 0);
            for (int i = 0; i < 16; i++) for (int c = 0; c < 4; c++) mean[c] += px[i * 4 + c];
            for (int c = 0; c < 4; c++) mean[c] /= 16;
            if (constant) {
                System.arraycopy(mean, 0, e0, 0, 4); System.arraycopy(mean, 0, e1, 0, 4);
            } else principalLine();
            long bestError = Long.MAX_VALUE;
            for (int refine = 0; refine < 2; refine++) {
                for (int p = 0; p < 4; p++) {
                    int p0 = p & 1, p1 = p >> 1;
                    for (int c = 0; c < 4; c++) { q0[c] = quantise(e0[c], p0); q1[c] = quantise(e1[c], p1); }
                    snapAlpha(q0, q1, p0, p1, hasZero, hasFull);
                    long error = assign();
                    if (error < bestError) {
                        bestError = error;
                        System.arraycopy(q0, 0, bestQ0, 0, 4); System.arraycopy(q1, 0, bestQ1, 0, 4);
                        System.arraycopy(idx, 0, bestIdx, 0, 16);
                    }
                }
                if (bestError <= 16 * 256 || refine == 1 || !leastSquares()) break;
            }
            write(out, offset, bestQ0, bestQ1, bestIdx);
            if (bestError > HARD_BLOCK_ERROR) two.encode(px, out, offset, bestError);
        }
        final TwoSubset two = new TwoSubset();

        /** Endpoints at the extremes of the block's principal axis (power iteration on the covariance). */
        private void principalLine() {
            java.util.Arrays.fill(cov, 0);
            for (int i = 0; i < 16; i++) for (int a = 0; a < 4; a++) {
                double da = px[i * 4 + a] - mean[a];
                for (int b = a; b < 4; b++) cov[a * 4 + b] += da * (px[i * 4 + b] - mean[b]);
            }
            for (int a = 0; a < 4; a++) for (int b = 0; b < a; b++) cov[a * 4 + b] = cov[b * 4 + a];
            for (int a = 0; a < 4; a++) axis[a] = cov[a * 4] + cov[a * 4 + 1] + cov[a * 4 + 2] + cov[a * 4 + 3];
            if (Math.abs(axis[0]) + Math.abs(axis[1]) + Math.abs(axis[2]) + Math.abs(axis[3]) < 1e-9) java.util.Arrays.fill(axis, 1);
            for (int iteration = 0; iteration < 6; iteration++) {
                java.util.Arrays.fill(next, 0);
                for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) next[a] += cov[a * 4 + b] * axis[b];
                double length = Math.sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2] + next[3] * next[3]);
                if (length < 1e-9) break;
                for (int c = 0; c < 4; c++) axis[c] = next[c] / length;
            }
            double length = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2] + axis[3] * axis[3]);
            for (int c = 0; c < 4; c++) axis[c] /= length;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < 16; i++) {
                double t = 0;
                for (int c = 0; c < 4; c++) t += (px[i * 4 + c] - mean[c]) * axis[c];
                lo = Math.min(lo, t); hi = Math.max(hi, t);
            }
            for (int c = 0; c < 4; c++) { e0[c] = mean[c] + axis[c] * lo; e1[c] = mean[c] + axis[c] * hi; }
        }

        /** Least-squares endpoints for the current best indices; false when the system is singular. */
        private boolean leastSquares() {
            double aa = 0, ab = 0, bb = 0, ax0 = 0, ax1 = 0, ax2 = 0, ax3 = 0, bx0 = 0, bx1 = 0, bx2 = 0, bx3 = 0;
            for (int i = 0; i < 16; i++) {
                double w = WEIGHTS[bestIdx[i]] / 64.0, a = 1 - w;
                aa += a * a; ab += a * w; bb += w * w;
                ax0 += a * px[i * 4]; ax1 += a * px[i * 4 + 1]; ax2 += a * px[i * 4 + 2]; ax3 += a * px[i * 4 + 3];
                bx0 += w * px[i * 4]; bx1 += w * px[i * 4 + 1]; bx2 += w * px[i * 4 + 2]; bx3 += w * px[i * 4 + 3];
            }
            double det = aa * bb - ab * ab;
            if (Math.abs(det) < 1e-9) return false;
            double[] ax = {ax0, ax1, ax2, ax3}, bx = {bx0, bx1, bx2, bx3};
            for (int c = 0; c < 4; c++) {
                e0[c] = (ax[c] * bb - bx[c] * ab) / det;
                e1[c] = (bx[c] * aa - ax[c] * ab) / det;
            }
            return true;
        }

        private long assign() {
            for (int i = 0; i < 16; i++) for (int c = 0; c < 4; c++) palette[i * 4 + c] = interpolate(q0[c], q1[c], WEIGHTS[i]);
            int d0 = q1[0] - q0[0], d1 = q1[1] - q0[1], d2 = q1[2] - q0[2], d3 = q1[3] - q0[3];
            long dd = (long) d0 * d0 + (long) d1 * d1 + (long) d2 * d2 + (long) d3 * d3;
            long total = 0;
            for (int i = 0; i < 16; i++) {
                int r = px[i * 4], g = px[i * 4 + 1], b = px[i * 4 + 2], a = px[i * 4 + 3];
                int guess = 0;
                if (dd > 0) {
                    long dot = (long) (r - q0[0]) * d0 + (long) (g - q0[1]) * d1 + (long) (b - q0[2]) * d2 + (long) (a - q0[3]) * d3;
                    guess = NEAREST[(int) Math.max(0, Math.min(64, (dot * 64 + dd / 2) / dd))];
                }
                int best = guess; long bestError = Long.MAX_VALUE;
                // The colour projection ignores alpha weighting; empty/opaque texels also try both endpoints,
                // where the snapped exact 0/255 alpha lives.
                boolean edge = a == 0 || a == 255;
                for (int k = edge ? 0 : Math.max(0, guess - 1), end = edge ? 15 : Math.min(15, guess + 1); k <= end; k++) {
                    if (edge && k > 0 && k < 15 && Math.abs(k - guess) > 1) continue;
                    int o = k * 4;
                    long er = (sq(r - palette[o]) + sq(g - palette[o + 1]) + sq(b - palette[o + 2])) * (a + 1) + sq(a - palette[o + 3]) * ALPHA_WEIGHT * 256
                            + (a == 0 && palette[o + 3] != 0 ? EMPTY_LEAK : 0);
                    if (er < bestError) { bestError = er; best = k; }
                }
                idx[i] = best; total += bestError;
            }
            return total;
        }
    }

    /** Modes 1 and 7: two endpoint lines selected by one of 64 partition shapes. */
    private static final class TwoSubset {
        private int[] px;
        private final double[] mean = new double[4], cov = new double[16], axis = new double[4], next = new double[4];
        private final double[] lo = new double[4], hi = new double[4];
        private final double[] score = new double[64];
        private final int[] order = new int[64];
        // candidate (per subset) and best block state
        private final int[][] end = new int[4][4], bestEnd = new int[4][4];
        private final int[] pbit = new int[4], bestPbit = new int[4], index = new int[16], bestIndex = new int[16];
        private final int[] subsetEnd0 = new int[4], subsetEnd1 = new int[4], subsetIndex = new int[16], palette = new int[32];
        private final int[] trialEnd0 = new int[4], trialEnd1 = new int[4], trialIndex = new int[16];
        private final byte[] block = new byte[16];

        void encode(int[] pixels, byte[] out, int offset, long errorToBeat) {
            px = pixels;
            boolean opaque = true;
            for (int i = 0; i < 16 && opaque; i++) opaque = px[i * 4 + 3] == 255;
            for (int p = 0; p < 64; p++) { score[p] = residual(PARTITIONS[p], 0) + residual(PARTITIONS[p], 1); order[p] = p; }
            // partial selection sort of the best few predicted partitions
            for (int a = 0; a < PARTITION_CANDIDATES; a++) {
                int best = a;
                for (int b = a + 1; b < 64; b++) if (score[order[b]] < score[order[best]]) best = b;
                int t = order[a]; order[a] = order[best]; order[best] = t;
            }
            long bestError = errorToBeat; int bestMode = -1, bestPartition = 0;
            for (int c = 0; c < PARTITION_CANDIDATES; c++) {
                int partition = order[c];
                for (int mode : opaque ? new int[]{1, 7} : new int[]{7}) {
                    long error = 0;
                    for (int s = 0; s < 2 && error < bestError; s++) error += fitSubset(PARTITIONS[partition], s, mode);
                    if (error < bestError) {
                        bestError = error; bestMode = mode; bestPartition = partition;
                        for (int e = 0; e < 4; e++) { System.arraycopy(end[e], 0, bestEnd[e], 0, 4); bestPbit[e] = pbit[e]; }
                        System.arraycopy(index, 0, bestIndex, 0, 16);
                    }
                }
            }
            if (bestMode < 0) return;
            write(bestMode, bestPartition);
            System.arraycopy(block, 0, out, offset, 16);
        }

        /** Squared distance of the subset's pixels to their best-fit line (trace minus largest eigenvalue). */
        private double residual(int shape, int subset) {
            int n = moments(shape, subset);
            if (n < 2) return 0;
            double trace = cov[0] + cov[5] + cov[10] + cov[15];
            return Math.max(0, trace - largestEigen());
        }

        private int moments(int shape, int subset) {
            java.util.Arrays.fill(mean, 0); java.util.Arrays.fill(cov, 0);
            int n = 0;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) { n++; for (int c = 0; c < 4; c++) mean[c] += px[i * 4 + c]; }
            if (n == 0) return 0;
            for (int c = 0; c < 4; c++) mean[c] /= n;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) for (int a = 0; a < 4; a++) {
                double da = px[i * 4 + a] - mean[a];
                for (int b = a; b < 4; b++) cov[a * 4 + b] += da * (px[i * 4 + b] - mean[b]);
            }
            for (int a = 0; a < 4; a++) for (int b = 0; b < a; b++) cov[a * 4 + b] = cov[b * 4 + a];
            return n;
        }

        private double largestEigen() {
            for (int a = 0; a < 4; a++) axis[a] = cov[a * 4] + cov[a * 4 + 1] + cov[a * 4 + 2] + cov[a * 4 + 3];
            if (Math.abs(axis[0]) + Math.abs(axis[1]) + Math.abs(axis[2]) + Math.abs(axis[3]) < 1e-9) java.util.Arrays.fill(axis, 0.5);
            double lambda = 0;
            for (int iteration = 0; iteration < 6; iteration++) {
                java.util.Arrays.fill(next, 0);
                for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) next[a] += cov[a * 4 + b] * axis[b];
                double length = Math.sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2] + next[3] * next[3]);
                if (length < 1e-9) return 0;
                lambda = length;
                for (int c = 0; c < 4; c++) axis[c] = next[c] / length;
            }
            return lambda;
        }

        /** Encodes one subset in the given mode into end[2s], end[2s+1], pbit and index; returns its error. */
        private long fitSubset(int shape, int subset, int mode) {
            int n = moments(shape, subset);
            if (n == 0) return 0;
            boolean subsetZero = false, subsetFull = false;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) { subsetZero |= px[i * 4 + 3] == 0; subsetFull |= px[i * 4 + 3] == 255; }
            largestEigen();
            double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) {
                double t = 0;
                for (int c = 0; c < 4; c++) t += (px[i * 4 + c] - mean[c]) * axis[c];
                tMin = Math.min(tMin, t); tMax = Math.max(tMax, t);
            }
            for (int c = 0; c < 4; c++) { lo[c] = mean[c] + axis[c] * tMin; hi[c] = mean[c] + axis[c] * tMax; }
            int[] weights = mode == 1 ? WEIGHTS3 : WEIGHTS2;
            long best = Long.MAX_VALUE; int bestP0 = 0, bestP1 = 0;
            for (int refine = 0; refine < 2; refine++) {
                for (int p = 0; p < 4; p++) {
                    int p0 = p & 1, p1 = mode == 1 ? p0 : p >> 1; // mode 1 shares one p-bit per subset
                    if (mode == 1 && p > 1) break;
                    for (int c = 0; c < 4; c++) { trialEnd0[c] = endpoint(lo[c], mode, c, p0); trialEnd1[c] = endpoint(hi[c], mode, c, p1); }
                    if (mode == 7) snapAlpha(trialEnd0, trialEnd1, p0, p1, subsetZero, subsetFull);
                    long error = assign(shape, subset, weights);
                    if (error < best) {
                        best = error; bestP0 = p0; bestP1 = p1;
                        System.arraycopy(trialEnd0, 0, subsetEnd0, 0, 4); System.arraycopy(trialEnd1, 0, subsetEnd1, 0, 4);
                        for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) subsetIndex[i] = trialIndex[i];
                    }
                }
                if (refine == 1 || best == 0 || !leastSquares(shape, subset, weights)) break;
            }
            System.arraycopy(subsetEnd0, 0, end[subset * 2], 0, 4); System.arraycopy(subsetEnd1, 0, end[subset * 2 + 1], 0, 4);
            pbit[subset * 2] = bestP0; pbit[subset * 2 + 1] = bestP1;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) index[i] = subsetIndex[i];
            return best;
        }

        private boolean leastSquares(int shape, int subset, int[] weights) {
            double aa = 0, ab = 0, bb = 0; double[] ax = new double[4], bx = new double[4];
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) {
                double w = weights[subsetIndex[i]] / 64.0, a = 1 - w;
                aa += a * a; ab += a * w; bb += w * w;
                for (int c = 0; c < 4; c++) { ax[c] += a * px[i * 4 + c]; bx[c] += w * px[i * 4 + c]; }
            }
            double det = aa * bb - ab * ab;
            if (Math.abs(det) < 1e-9) return false;
            for (int c = 0; c < 4; c++) { lo[c] = (ax[c] * bb - bx[c] * ab) / det; hi[c] = (bx[c] * aa - ax[c] * ab) / det; }
            return true;
        }

        private long assign(int shape, int subset, int[] weights) {
            int levels = weights.length;
            for (int k = 0; k < levels; k++) for (int c = 0; c < 4; c++) palette[k * 4 + c] = interpolate(trialEnd0[c], trialEnd1[c], weights[k]);
            long total = 0;
            for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == subset) {
                long bestError = Long.MAX_VALUE; int best = 0;
                for (int k = 0; k < levels; k++) {
                    int o = k * 4;
                    long er = (sq(px[i * 4] - palette[o]) + sq(px[i * 4 + 1] - palette[o + 1]) + sq(px[i * 4 + 2] - palette[o + 2])) * (px[i * 4 + 3] + 1) + sq(px[i * 4 + 3] - palette[o + 3]) * ALPHA_WEIGHT * 256
                        + (px[i * 4 + 3] == 0 && palette[o + 3] != 0 ? EMPTY_LEAK : 0);
                    if (er < bestError) { bestError = er; best = k; }
                }
                trialIndex[i] = best; total += bestError;
            }
            return total;
        }

        /** Expanded 8-bit endpoint value nearest to {@code value} for the mode's precision and p-bit. */
        private static int endpoint(double value, int mode, int channel, int p) {
            if (mode == 1 && channel == 3) return 255;
            int bits = mode == 1 ? 7 : 6; // stored bits plus the p-bit
            int max = (1 << (bits - 1)) - 1, best = 0; double bestDistance = Double.MAX_VALUE;
            int guess = Math.max(0, Math.min(max, (int) Math.round((value / 255.0 * ((1 << bits) - 1) - p) / 2)));
            for (int q = Math.max(0, guess - 1); q <= Math.min(max, guess + 1); q++) {
                int expanded = expand(q << 1 | p, bits);
                double distance = Math.abs(expanded - value);
                if (distance < bestDistance) { bestDistance = distance; best = expanded; }
            }
            return best;
        }
        private static int expand(int value, int bits) { return (value << (8 - bits)) | (value >> (2 * bits - 8)); }
        /** Stored field (without the p-bit) for an expanded endpoint. */
        private static int stored(int expanded, int mode) { return (expanded >> (mode == 1 ? 1 : 2)) >> 1; }

        private void write(int mode, int partition) {
            int shape = PARTITIONS[partition], anchor1 = ANCHORS[partition];
            int indexBits = mode == 1 ? 3 : 2, top = 1 << (indexBits - 1), maxIndex = (1 << indexBits) - 1;
            for (int s = 0; s < 2; s++) {
                int anchor = s == 0 ? 0 : anchor1;
                if (bestIndex[anchor] >= top) { // anchor index loses its MSB: swap this subset's endpoints
                    int[] t = bestEnd[s * 2]; bestEnd[s * 2] = bestEnd[s * 2 + 1]; bestEnd[s * 2 + 1] = t;
                    int tp = bestPbit[s * 2]; bestPbit[s * 2] = bestPbit[s * 2 + 1]; bestPbit[s * 2 + 1] = tp;
                    for (int i = 0; i < 16; i++) if (((shape >> i) & 1) == s) bestIndex[i] = maxIndex - bestIndex[i];
                }
            }
            long[] word = {0, 0};
            int bit = mode == 1 ? put(word, 0, 1L << 1, 2) : put(word, 0, 1L << 7, 8);
            bit = put(word, bit, partition, 6);
            int fieldBits = mode == 1 ? 6 : 5, channels = mode == 1 ? 3 : 4;
            for (int c = 0; c < channels; c++) for (int e = 0; e < 4; e++) bit = put(word, bit, stored(bestEnd[e][c], mode), fieldBits);
            if (mode == 1) { bit = put(word, bit, bestPbit[0], 1); bit = put(word, bit, bestPbit[2], 1); }
            else for (int e = 0; e < 4; e++) bit = put(word, bit, bestPbit[e], 1);
            for (int i = 0; i < 16; i++) bit = put(word, bit, bestIndex[i], (i == 0 || i == anchor1) ? indexBits - 1 : indexBits);
            if (bit != 128) throw new IllegalStateException("BC7 mode " + mode + " wrote " + bit + " bits");
            for (int i = 0; i < 8; i++) { block[i] = (byte) (word[0] >>> (i * 8)); block[8 + i] = (byte) (word[1] >>> (i * 8)); }
        }
    }

    /** 7-bit endpoint for the given p-bit: value = q * 2 + p. */
    private static int quantise(double value, int pbit) {
        int q = (int) Math.round((value - pbit) / 2);
        return Math.max(0, Math.min(127, q)) * 2 + pbit;
    }

    private static long sq(int v) { return (long) v * v; }
    private static int interpolate(int e0, int e1, int weight) { return ((64 - weight) * e0 + weight * e1 + 32) >> 6; }

    private static void write(byte[] out, int offset, int[] e0, int[] e1, int[] idx) {
        int[] a = e0.clone(), b = e1.clone(), ix = idx.clone();
        if (ix[0] >= 8) { // anchor index must fit in 3 bits: swap the endpoints and mirror the indices
            int[] t = a; a = b; b = t;
            for (int i = 0; i < 16; i++) ix[i] = 15 - ix[i];
        }
        long[] word = {0, 0};
        int bit = put(word, 0, 1L << 6, 7); // mode 6: six zero bits then a one
        for (int c = 0; c < 4; c++) { bit = put(word, bit, a[c] >> 1, 7); bit = put(word, bit, b[c] >> 1, 7); }
        bit = put(word, bit, a[0] & 1, 1);
        bit = put(word, bit, b[0] & 1, 1);
        bit = put(word, bit, ix[0], 3);
        for (int i = 1; i < 16; i++) bit = put(word, bit, ix[i], 4);
        long lo = word[0], hi = word[1];
        for (int i = 0; i < 8; i++) { out[offset + i] = (byte) (lo >>> (i * 8)); out[offset + 8 + i] = (byte) (hi >>> (i * 8)); }
    }

    private static int put(long[] word, int bit, long value, int bits) {
        for (int i = 0; i < bits; i++, bit++) if (((value >>> i) & 1) != 0) word[bit >> 6] |= 1L << (bit & 63);
        return bit;
    }

    // ---- decoder ---------------------------------------------------------------------------------

    /** Decodes the modes this encoder writes (1, 6, 7); any other block decodes as transparent black. */
    private static void decodeBlock(byte[] in, int offset, int[] px) {
        long lo = 0, hi = 0;
        for (int i = 0; i < 8; i++) { lo |= (in[offset + i] & 0xffL) << (i * 8); hi |= (in[offset + 8 + i] & 0xffL) << (i * 8); }
        long[] word = {lo, hi};
        int mode = Long.numberOfTrailingZeros(lo);
        if (mode != 1 && mode != 6 && mode != 7) { java.util.Arrays.fill(px, 0); return; }
        int bit = mode + 1;
        int subsets = mode == 6 ? 1 : 2, endpoints = subsets * 2, shape = 0, anchor1 = -1;
        if (subsets == 2) { int partition = (int) get(word, bit, 6); bit += 6; shape = PARTITIONS[partition]; anchor1 = ANCHORS[partition]; }
        int fieldBits = mode == 1 ? 6 : mode == 6 ? 7 : 5, channels = mode == 1 ? 3 : 4, bits = fieldBits + 1;
        int[][] e = new int[endpoints][4];
        for (int c = 0; c < channels; c++) for (int k = 0; k < endpoints; k++) { e[k][c] = (int) get(word, bit, fieldBits); bit += fieldBits; }
        int[] p = new int[endpoints];
        if (mode == 1) { int s0 = (int) get(word, bit++, 1), s1 = (int) get(word, bit++, 1); p[0] = p[1] = s0; p[2] = p[3] = s1; }
        else for (int k = 0; k < endpoints; k++) p[k] = (int) get(word, bit++, 1);
        for (int k = 0; k < endpoints; k++) for (int c = 0; c < 4; c++)
            e[k][c] = c < channels ? expandField(e[k][c] << 1 | p[k], bits) : 255;
        int indexBits = mode == 1 ? 3 : mode == 6 ? 4 : 2;
        int[] weights = mode == 1 ? WEIGHTS3 : mode == 6 ? WEIGHTS : WEIGHTS2;
        for (int i = 0; i < 16; i++) {
            int n = (i == 0 || i == anchor1) ? indexBits - 1 : indexBits;
            int index = (int) get(word, bit, n); bit += n;
            int s = (shape >> i) & 1;
            for (int c = 0; c < 4; c++) px[i * 4 + c] = interpolate(e[s * 2][c], e[s * 2 + 1][c], weights[index]);
        }
    }
    private static int expandField(int value, int bits) { return bits == 8 ? value : (value << (8 - bits)) | (value >> (2 * bits - 8)); }

    private static long get(long[] word, int bit, int bits) {
        long value = 0;
        for (int i = 0; i < bits; i++, bit++) value |= ((word[bit >> 6] >>> (bit & 63)) & 1L) << i;
        return value;
    }
}
