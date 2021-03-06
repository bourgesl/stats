/*******************************************************************************
 *                 jMCS project ( http://www.jmmc.fr/dev/jmcs )
 *******************************************************************************
 * Copyright (c) 2020, CNRS. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of the CNRS nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL CNRS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package fr.jmmc.jmcs.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple class to provide utility statistical functions and samples for circular distributions
 * @author bourgesl
 */
public final class StatUtils {

    /** Class logger */
    private static final Logger logger = LoggerFactory.getLogger(StatUtils.class.getName());
    /** precision expected on mean */
    private final static double EPSILON_MEAN = 5e-4;
    /** precision expected on stddev */
    private final static double EPSILON_VARIANCE = 5e-5;
    /** number of samples */
    public final static int N_SAMPLES = 1024;
    /** normalization factor = 1/N_SAMPLES */
    public final static double SAMPLING_FACTOR_MEAN = 1d / N_SAMPLES;
    /** normalization factor for variance = 1 / (N_SAMPLES - 1) (bessel correction) */
    public final static double SAMPLING_FACTOR_VARIANCE = 1d / (N_SAMPLES - 1);
    /** initial cache size = number of baselines (15 for 6 telescopes) */
    private final static int INITIAL_CAPACITY = 15;
    /** singleton */
    private static StatUtils INSTANCE = null;

    /**
     * Return singleton (lazy)
     * @return singleton
     */
    public synchronized static StatUtils getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new StatUtils();
        }
        return INSTANCE;
    }

    /* members */
    /** current index in the distribution cache */
    private int current = 0;
    /** cached distributions */
    private final ArrayList<ComplexDistribution> cache;

    private StatUtils() {
        this.cache = new ArrayList<ComplexDistribution>(INITIAL_CAPACITY);
        prepare(INITIAL_CAPACITY);
    }

    public synchronized void prepare(final int count) {
        final int needed = count - cache.size();
        if (needed > 0) {
            logger.info("prepare: {} needed distributions", needed);
            final long start = System.nanoTime();

            for (int i = 0; i < needed; i++) {
                /* create a new random generator to have different seed (single thread) */
                final Random random = new Random();
                cache.add(ComplexDistribution.create(random));
            }

            logger.info("prepare done: {} ms.", 1e-6d * (System.nanoTime() - start));
        }
    }

    public synchronized ComplexDistribution get() {
        final int idx = current;
        final ComplexDistribution distrib = cache.get(idx);

        this.current = (idx + 1) % cache.size();

        return distrib;
    }

    public static final class ComplexDistribution {

        private final double[][] samples = new double[2][N_SAMPLES];
        private final double[][] moments = new double[2][4];

        public static ComplexDistribution create(final Random random) {
            final ComplexDistribution distrib = new ComplexDistribution();

            final long start = System.nanoTime();

            int n = 0;
            do {
                distrib.generate(random);
                n++;
            } while (!distrib.test());

            logger.info("done: {} ms ({} iterations).", 1e-6d * (System.nanoTime() - start), n);

            distrib.computeMoments();

            return distrib;
        }

        private ComplexDistribution() {
            super();
        }

        private void generate(final Random random) {
            final double[] distRe = samples[0];
            final double[] distIm = samples[1];

            // bivariate distribution (complex normal):
            for (int n = 0; n < N_SAMPLES; n++) {
                // generate nth sample:
                distRe[n] = random.nextGaussian();
                distIm[n] = random.nextGaussian();
            }
        }

        private boolean test() {
            final double snr = 100.0;

            final double ref_amp = 1.0;
            final double err = ref_amp / snr;

            final double ref_re = ref_amp / Math.sqrt(2);

            final double norm = ref_amp * ref_amp;

            final double[] distRe = samples[0];
            final double[] distIm = samples[1];

            double re, im, sample, diff;
            double sum = 0.0;
            double sum_diff = 0.0;
            double sum_diff_square = 0.0;

            // bivariate distribution (complex normal):
            for (int n = 0; n < N_SAMPLES; n++) {

                // update nth sample:
                re = ref_re + (err * distRe[n]);
                im = ref_re + (err * distIm[n]);

                // compute norm=re^2+im^2:
                sample = re * re + im * im;

                // Compensated-summation variant for better numeric precision:
                sum += sample;
                diff = sample - norm;
                sum_diff += diff;
                sum_diff_square += diff * diff;
            }

            // mean(norm):
            final double mean = SAMPLING_FACTOR_MEAN * sum;

            // variance(norm):
            // note: this algorithm ensures correctness (stable) even if the mean used in diff is wrong !
            final double variance = SAMPLING_FACTOR_VARIANCE * (sum_diff_square - (SAMPLING_FACTOR_MEAN * (sum_diff * sum_diff)));

            final double ratio_avg = mean / norm;

            // d(v2) = 2v * dv 
            final double errNorm = 2.0 * ref_amp * err;
            final double ratio_variance = variance / (errNorm * errNorm);

            final boolean good = (Math.abs(ratio_avg - 1.0) < EPSILON_MEAN)
                    && (Math.abs(ratio_variance - 1.0) < EPSILON_VARIANCE);

            if (good && logger.isDebugEnabled()) {
                logger.debug("Sampling[" + N_SAMPLES + "] snr=" + snr + " (err(re,im)= " + err + ")"
                        + " avg= " + mean + " norm= " + norm + " ratio: " + ratio_avg
                        + " stddev= " + Math.sqrt(variance) + " err(norm)= " + errNorm + " ratio: " + ratio_variance
                        + " good = " + good);
            }

            return good;
        }

        public double[][] getSamples() {
            return samples;
        }

        public double[][] getMoments() {
            return moments;
        }

        private void computeMoments() {
            moments(samples[0], moments[0]);
            moments(samples[1], moments[1]);
        }
    }

    public static double min(final double[] array) {
        double min = Double.POSITIVE_INFINITY;

        for (int n = 0; n < array.length; n++) {
            if (array[n] < min) {
                min = array[n];
            }
        }
        return min;
    }

    public static double max(final double[] array) {
        double max = Double.NEGATIVE_INFINITY;

        for (int n = 0; n < array.length; n++) {
            if (array[n] > max) {
                max = array[n];
            }
        }
        return max;
    }

    public static double mean(final double[] array) {
        double sample, sum = 0.0;
        int ns = 0;
        for (int n = 0; n < array.length; n++) {
            sample = array[n];
            // No Compensated-summation (double):
            if (!Double.isNaN(sample)) {
                sum += sample;
                ns++;
            }
        }
        return (ns != 0) ? (sum / ns) : 0.0;
    }

    public static double[] moments(final double[] array) {
        final double[] moments = new double[4];
        moments(array, moments);
        return moments;
    }

    public static void moments(final double[] array, final double[] moments) {
        final double mean = mean(array);

        double sample, diff;
        double sum_diff = 0.0;
        double sum_diff2 = 0.0;

        for (int n = 0; n < array.length; n++) {
            sample = array[n];
            // Compensated-summation variant for better numeric precision:
            diff = sample - mean;
            sum_diff += diff;
            sum_diff2 += diff * diff;
        }

        // variance(norm):
        // note: this algorithm ensures correctness (stable) even if the mean used in diff is wrong !
        final double variance = (sum_diff2 - (SAMPLING_FACTOR_MEAN * (sum_diff * sum_diff))) / (array.length - 1);

        final double stddev = Math.sqrt(variance);

        // Moments ordre 3 et 4:
        double sum_diff3 = 0.0;
        double sum_diff4 = 0.0;

        for (int n = 0; n < array.length; n++) {
            sample = array[n];
            // Compensated-summation variant for better numeric precision:
            diff = (sample - mean) / stddev;
            sum_diff3 += diff * diff * diff;
            sum_diff4 += diff * diff * diff * diff;
        }

        final double asymetry = sum_diff3 / array.length;
        final double kurtosis = (sum_diff4 / array.length) - 3.0; // normalised

        // output:
        moments[0] = mean;
        moments[1] = variance;
        moments[2] = asymetry;
        moments[3] = kurtosis;
    }

    // --- TEST ---
    public static void main(String[] args) throws IOException {
        final boolean TEST_SUM = true;
        final boolean TEST_DIST = true;
        final boolean DO_DUMP = false;

        // Test kahan sum:
        if (TEST_SUM) {
            final double[] values = new double[10 * 1024 * 1024];
            testSum(values, 1.0e-8);
            testSum(values, 1.0);
            testSum(values, 1.0e8);
        }

        // Precompute distributions:
        StatUtils.getInstance();

        if (DO_DUMP) {
            // dump all distributions:
            for (int n = 0; n < INITIAL_CAPACITY; n++) {
                ComplexDistribution d = StatUtils.getInstance().get();
                System.out.println("get(): " + d);

                // Get the complex distribution for this row:
                final double[] distRe = d.getSamples()[0];
                final double[] distIm = d.getSamples()[1];

                System.out.println("moments(re): " + Arrays.toString(d.getMoments()[0]));
                System.out.println("moments(im): " + Arrays.toString(d.getMoments()[1]));

                final StringBuilder sb = new StringBuilder(4096);
                sb.append("# RE\tIM\n");

                for (int i = 0; i < N_SAMPLES; i++) {
                    sb.append(distRe[i]).append("\t").append(distIm[i]).append('\n');
                }
                final File file = new File("dist_" + N_SAMPLES + "_" + n + ".txt");

                System.out.println("Writing: " + file.getAbsolutePath());
                writeFile(file, sb.toString());
            }
        }

        final double[][] means = new double[2][INITIAL_CAPACITY];
        final double[][] vars = new double[2][INITIAL_CAPACITY];

        for (int n = 0; n < INITIAL_CAPACITY; n++) {
            ComplexDistribution d = StatUtils.getInstance().get();
            System.out.println("get(): " + d);

            System.out.println("moments(re): " + Arrays.toString(d.getMoments()[0]));
            System.out.println("moments(im): " + Arrays.toString(d.getMoments()[1]));

            means[0][n] = d.getMoments()[0][0];
            means[1][n] = d.getMoments()[1][0];

            vars[0][n] = d.getMoments()[0][1];
            vars[1][n] = d.getMoments()[1][1];
        }

        System.out.println("moments(mean) (re): " + Arrays.toString(moments(means[0])));
        System.out.println("moments(mean) (im): " + Arrays.toString(moments(means[1])));

        System.out.println("moments(variance) (re): " + Arrays.toString(moments(vars[0])));
        System.out.println("moments(variance) (im): " + Arrays.toString(moments(vars[1])));

        for (double snr = 10.0; snr > 1e-2;) {
            /* for (double amp = 1.0; amp > 5e-6; amp /= 10.0) */
            final double amp = 0.00137;
            {

                System.out.println("--- SNR: " + snr + " @ AMP = " + amp + "---");

                if (false) {
                    System.out.println("VISAMP");
                    for (int i = 0; i < INITIAL_CAPACITY; i++) {
                        ComplexDistribution d = StatUtils.getInstance().get();
                        if (TEST_DIST) {
                            test(amp, snr, true, d.getSamples());
                        } else {
                            test(amp, snr, true);
                        }
                    }
                }
                if (true) {
                    System.out.println("VIS2:");
                    for (int i = 0; i < INITIAL_CAPACITY; i++) {
                        ComplexDistribution d = StatUtils.getInstance().get();
                        if (TEST_DIST) {
                            test(amp, snr, false, d.getSamples());
                        } else {
                            test(amp, snr, false);
                        }
                    }
                }
            }
            if (snr > 2.5) {
                snr -= 1.0;
            } else {
                snr -= 0.1;
            }
        }
    }

    private static void test(final double visRef, final double snr, final boolean amp) {
        /* create a new random generator to have different seed (single thread) */
        final Random random = new Random();

        final double[][] samples = new double[2][];
        samples[0] = new double[N_SAMPLES];
        samples[1] = new double[N_SAMPLES];

        for (int n = 0; n < N_SAMPLES; n++) {
            // update nth sample:
            samples[0][n] = random.nextGaussian();
            samples[1][n] = random.nextGaussian();
        }
        test(visRef, snr, amp, samples);
    }

    private static void test(final double visRef, final double snr, final boolean amp, double[][] samples) {
        double visErr = visRef / snr;
        System.out.println("circular err: " + visErr);

        double exp_ref = visRef;
        double exp_err = visErr;
        final double visRe = exp_ref / Math.sqrt(2);
        final double visIm = visRe;

        if (!amp) {
            // d(v2) = 2v * dv 
            exp_err = 2.0 * visRef * visErr;
            exp_ref = visRef * visRef;
        }

        if (false) {
            // try error correction ?
            if (!amp) {
                if (exp_err / exp_ref >= 1) {
                    System.out.println("exp_err: " + exp_err);
                    System.out.println("exp_ref: " + exp_ref);
                    visErr = Math.sqrt(exp_err / 2.0); // = SQRT(half deviation)
                    System.out.println("Fixed circular err: " + visErr);
                }
            }
        }

        double sample, diff;
        int n;
        double re, im;
        double vis_acc = 0.0;
        double vis_diff_acc = 0.0;
        double vis_sq_diff_acc = 0.0;

        // bivariate distribution (complex normal):
        for (n = 0; n < N_SAMPLES; n++) {
            // update nth sample:
            re = visRe + visErr * samples[0][n];
            im = visIm + visErr * samples[1][n];

            // compute squared amplitude:
            sample = re * re + im * im;
            if (amp) {
                // compute vis amp:
                sample = Math.sqrt(sample);
            }

            // Compensated-summation variant for better numeric precision:
            vis_acc += sample;
            diff = sample - exp_ref;
            vis_diff_acc += diff;
            vis_sq_diff_acc += diff * diff;
        }

        // average on amplitude:
        double avg = SAMPLING_FACTOR_MEAN * vis_acc;

        // standard deviation on amplitude:
        // note: this algorithm ensures correctness (stable) even if the mean used in diff is wrong !
        double stddev = Math.sqrt(SAMPLING_FACTOR_VARIANCE * (vis_sq_diff_acc - (SAMPLING_FACTOR_MEAN * (vis_diff_acc * vis_diff_acc))));

        System.out.println("Sampling[" + N_SAMPLES + "] avg= " + avg + " vs expected ref= " + exp_ref + " ratio: " + (avg / exp_ref));
        System.out.println("Sampling[" + N_SAMPLES + "] stddev= " + stddev + " vs expected Err= " + exp_err + " ratio: " + (stddev / exp_err));
    }

    // sum tests
    private static void testSum(final double[] values, final double val) {
        Arrays.fill(values, val);
        values[0] = 1.0;

        final double naive = naiveSum(values);
        System.out.println("naiveSum[1 + " + val + " x " + values.length + "]: " + naive);
        final double kahan = kahanSum(values);
        System.out.println("kahanSum[1 + " + val + " x " + values.length + "]: " + kahan);
        System.out.println("delta: " + (naive - kahan));
    }

    private static double naiveSum(double[] values) {
        final double[] state = new double[1]; // sum
        state[0] = 0.0;
        for (int i = 0; i < values.length; i++) {
            state[0] += values[i];
        }
        return state[0];
    }

    private static double kahanSum(double[] values) {
        final double[] state = new double[2]; // sum | error
        state[0] = 0.0;
        state[1] = 0.0;
        for (int i = 0; i < values.length; i++) {
            final double y = values[i] - state[1];
            final double t = state[0] + y;
            state[1] = (t - state[0]) - y;
            state[0] = t;
        }
        return state[0];
    }

    // --- utility functions ---
    private static void writeFile(final File file, final String content) throws IOException {
        final Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
        try {
            w.write(content);
        } finally {
            try {
                w.close();
            } catch (IOException ioe) {
                logger.debug("IO close failure.", ioe);
            }
        }
    }
}
