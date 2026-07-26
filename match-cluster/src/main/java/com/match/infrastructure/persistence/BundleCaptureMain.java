// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.generated.BookDeltaEncoder;
import com.openexchange.cluster.BundleCapture;

/**
 * The matching engine's entry point into the shared bundle machinery.
 *
 * <p>This is the entire seam between the two: {@link BundleCapture} knows how to
 * replicate a snapshot and the log behind it out of a live archive, and knows
 * nothing about order books; this class supplies the one fact it cannot derive,
 * which wire schema this engine speaks.
 *
 * <p>The schema is payload in the manifest, not decoration. A snapshot
 * serialized by one build may not deserialize in another, so a bundle that
 * cannot name its schema is one a replay must refuse.
 */
public final class BundleCaptureMain {

    private BundleCaptureMain() {
    }

    public static void main(final String[] args) {
        BundleCapture.run(args, new BundleCapture.EngineSchema(
                BookDeltaEncoder.SCHEMA_ID,
                BookDeltaEncoder.SCHEMA_VERSION));
    }
}
