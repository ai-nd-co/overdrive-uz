package com.overdrive.app.od;

import android.content.Context;

/**
 * Stub for the proprietary "Od" authorization / native-resolve module that the public
 * Overdrive release does not ship. The consuming code (GpuStreamScaler, CameraDaemon,
 * GpuSurveillancePipeline) is explicitly written to be no-op-safe when this module is
 * unauthorized: resolve() zeroes its output, tryLoadLibrary() reports not-loaded, and
 * authorize() is idempotent. See the call sites, e.g.
 * GpuStreamScaler.resolveCoef ("No-op-safe: Od zeroes the output if unauthorized") and
 * GpuSurveillancePipeline ("Od.resolve() zeros its output forever" when unauthorized).
 *
 * This stub reproduces that documented unauthorized path so the app builds without the
 * proprietary module. Effect at runtime: the blind-spot GPU stitch receives all-zero
 * coefficients (black blind-spot stream), exactly the degraded behavior the surrounding
 * code already anticipates. Nothing on the daemon / automation path depends on it.
 *
 * If a licensed Od implementation is later provided, deleting this file and adding the
 * real module restores the feature with no other code changes.
 */
public final class Od {

    private Od() {}

    /**
     * Resolve sampler coefficients from the opaque input scalars. Unauthorized behavior:
     * zero the entire output array (blind-spot stitch goes black). odIn is length 11
     * (indices 0-9 raw params, index 10 per-side sign); odCoef is the output buffer.
     */
    public static void resolve(float[] odIn, float[] odCoef) {
        if (odCoef != null) {
            java.util.Arrays.fill(odCoef, 0.0f);
        }
    }

    /** Try to load the proprietary native library. Stub: not present, so report false. */
    public static boolean tryLoadLibrary(String nativeLibDir) {
        return false;
    }

    /** Authorize against the app context. Stub: idempotent no-op (stays unauthorized). */
    public static void authorize(Context context) {
        // No-op: without the proprietary module there is nothing to authorize.
    }
}
