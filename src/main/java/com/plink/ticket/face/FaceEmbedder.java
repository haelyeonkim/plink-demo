package com.plink.ticket.face;

import java.util.List;

/**
 * Extraction seam. Production points this at the separate face service, which holds the
 * model and its own keys; the ticket service only ever sees vectors and scores.
 */
public interface FaceEmbedder {

    /** Grades the capture and returns a feature vector, or reports why it was rejected. */
    FaceVector embed(List<byte[]> frames);

    /**
     * Liveness decision for a capture, optionally against a server-issued challenge.
     * Returns a score in [0,1]; the caller applies the session's threshold. This has to
     * stay server-side: a browser that reports its own liveness verdict is trivially
     * rewritten.
     */
    double liveness(List<byte[]> frames, String challenge);

    String algoVersion();
}
