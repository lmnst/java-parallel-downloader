package com.example.downloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.Consumer;

/**
 * Thread-safe sink that writes ByteBuffers sequentially into a FileChannel
 * at a fixed starting offset. Each write appends after the previous one within
 * this chunk's region.
 */
final class ChunkSink implements Consumer<ByteBuffer> {

    private final FileChannel channel;
    private long position;

    public ChunkSink(FileChannel channel, long startOffset) {
        this.channel  = channel;
        this.position = startOffset;
    }

    @Override
    public void accept(ByteBuffer buf) {
        // wrap in a checked call so Consumer<ByteBuffer> can be used directly in lambdas
        try {
            write(buf);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public void write(ByteBuffer buf) throws IOException {
        // FileChannel.write(buf, position) is specified as thread-safe for concurrent
        // writes at different positions, so multiple ChunkSink instances on the same
        // channel do not need external synchronization.
        while (buf.hasRemaining()) {
            int written = channel.write(buf, position);
            position += written;
        }
    }

}
