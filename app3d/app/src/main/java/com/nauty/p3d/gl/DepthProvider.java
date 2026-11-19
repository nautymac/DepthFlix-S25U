package com.nauty.p3d.gl;

import java.nio.ByteBuffer;

/**
 * An AI depth estimator for 2D sources (2D -> 3D by depth instead of the shear ramp).
 *
 * The renderer hands it a small square RGBA snapshot of the picture whenever it is free,
 * and picks up the newest depth map when one is ready; inference runs on its own thread,
 * so the video keeps its frame rate and the depth follows a few frames behind.
 *
 * Orientation: both buffers have row 0 at the BOTTOM of the picture (GL order), so the
 * depth map lines up with the source texture coordinates as they are.
 */
public interface DepthProvider {
    /** False = use the shear ramp (the usual 2D -> 3D). Any thread. */
    boolean active();

    /** Size of the snapshot it wants. */
    int inputWidth();

    int inputHeight();

    /** True when it can take a new snapshot now (the previous one is done). */
    boolean wantsFrame();

    /** GL thread: a snapshot, inputWidth() x inputHeight() RGBA bytes. Copied before returning. */
    void submit(ByteBuffer rgba);

    /** Size of the depth maps it produces. */
    int depthWidth();

    int depthHeight();

    /**
     * GL thread: the newest depth map (depthWidth() x depthHeight() bytes, 255 = near) if a new one
     * has arrived since the last call, otherwise null.
     */
    ByteBuffer takeDepth();
}
