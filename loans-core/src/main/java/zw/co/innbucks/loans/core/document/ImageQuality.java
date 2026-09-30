package zw.co.innbucks.loans.core.document;

import java.awt.image.BufferedImage;

/**
 * How readable an image is, measured on its brightness at up to {@value #MEASURE_MAX_SIDE} pixels on the
 * long side, so a phone photo and a scan of the same page measure alike.
 *
 * @param contrast  the standard deviation of brightness (0–255): near zero, the image is one flat tone
 * @param sharpness the 99.9th percentile of the absolute Laplacian: how crisp the crispest edges are.
 *                  A percentile rather than the variance, so a page with little text on it is not taken
 *                  for a blurred one
 */
record ImageQuality(double contrast, double sharpness) {

    static final int MEASURE_MAX_SIDE = 1024;
    private static final double SHARPNESS_PERCENTILE = 0.999;
    /** The Laplacian of 0–255 brightness lies within ±1020; binned in halves. */
    private static final int HISTOGRAM_BINS = 2041;

    static ImageQuality measure(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int factor = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MEASURE_MAX_SIDE));
        int columns = Math.max(1, width / factor);
        int rows = Math.max(1, height / factor);
        double[] brightness = brightness(image, factor, columns, rows);
        return new ImageQuality(standardDeviation(brightness), sharpness(brightness, columns, rows));
    }

    /** Brightness averaged over factor × factor blocks; transparency is seen against white paper. */
    private static double[] brightness(BufferedImage image, int factor, int columns, int rows) {
        int width = image.getWidth();
        boolean transparent = image.getColorModel().hasAlpha();
        double[] brightness = new double[columns * rows];
        int[] line = new int[width];
        int usedWidth = Math.min(width, columns * factor);
        int usedHeight = Math.min(image.getHeight(), rows * factor);
        for (int y = 0; y < usedHeight; y++) {
            image.getRGB(0, y, width, 1, line, 0, width);
            int rowOffset = (y / factor) * columns;
            for (int x = 0; x < usedWidth; x++) {
                brightness[rowOffset + x / factor] += luminance(line[x], transparent);
            }
        }
        int block = Math.max(1, Math.min(factor, usedWidth) * Math.min(factor, usedHeight));
        for (int i = 0; i < brightness.length; i++) {
            brightness[i] /= block;
        }
        return brightness;
    }

    private static double luminance(int argb, boolean transparent) {
        double luminance = 0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF);
        if (!transparent) {
            return luminance;
        }
        // A signature pad's transparent background is black in its colour channels; composite it on white.
        double alpha = ((argb >>> 24) & 0xFF) / 255.0;
        return alpha * luminance + (1 - alpha) * 255;
    }

    private static double standardDeviation(double[] values) {
        double sum = 0;
        double squares = 0;
        for (double value : values) {
            sum += value;
            squares += value * value;
        }
        double mean = sum / values.length;
        return Math.sqrt(Math.max(0, squares / values.length - mean * mean));
    }

    private static double sharpness(double[] brightness, int columns, int rows) {
        if (columns < 3 || rows < 3) {
            return 0;
        }
        long[] histogram = new long[HISTOGRAM_BINS];
        long count = 0;
        for (int y = 1; y < rows - 1; y++) {
            for (int x = 1; x < columns - 1; x++) {
                int i = y * columns + x;
                double laplacian = brightness[i - columns] + brightness[i + columns] + brightness[i - 1]
                        + brightness[i + 1] - 4 * brightness[i];
                histogram[Math.min(HISTOGRAM_BINS - 1, (int) Math.round(Math.abs(laplacian) * 2))]++;
                count++;
            }
        }
        long target = (long) Math.ceil(count * SHARPNESS_PERCENTILE);
        long seen = 0;
        for (int bin = 0; bin < HISTOGRAM_BINS; bin++) {
            seen += histogram[bin];
            if (seen >= target) {
                return bin / 2.0;
            }
        }
        return (HISTOGRAM_BINS - 1) / 2.0;
    }
}
