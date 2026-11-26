package io.folderaudio.player;

import java.io.IOException;

final class NativePlayer {
    static { System.loadLibrary("folderaudio"); }

    static final int FINISHED = 1;
    static final int DISCONNECTED = 2;
    static final int WAITING = 3;

    static native long open(int fd, long offset, long length, int policy, long position,
                            boolean enabled, float[] values) throws IOException;
    static native int step(long handle) throws IOException;
    static native void setEq(long handle, boolean enabled, float[] values);
    static native long[] info(long handle);
    static native void close(long handle);

    private NativePlayer() {}
}
