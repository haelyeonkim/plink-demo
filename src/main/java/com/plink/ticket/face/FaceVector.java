package com.plink.ticket.face;

/** A face feature vector with the quality score the capture was graded at. */
public class FaceVector {
    public final float[] values;
    public final String algoVersion;
    public final double quality;

    public FaceVector(float[] values, String algoVersion, double quality) {
        this.values = values;
        this.algoVersion = algoVersion;
        this.quality = quality;
    }

    /** Cosine similarity; both sides are L2-normalised at extraction time. */
    public static double similarity(float[] a, float[] b) {
        if (a.length != b.length) return -1;
        double dot = 0, left = 0, right = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            left += a[i] * a[i];
            right += b[i] * b[i];
        }
        if (left == 0 || right == 0) return -1;
        return dot / (Math.sqrt(left) * Math.sqrt(right));
    }
}
