package android.media;

public final class MediaCodec {
    private MediaCodec() {}

    public static final class BufferInfo {
        public int flags;
        public int size;
        public long presentationTimeUs;
    }

    public static final class CodecException extends IllegalStateException {
        private CodecException() {}
    }
}
