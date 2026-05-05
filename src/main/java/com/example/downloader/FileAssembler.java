package com.example.downloader;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Manages the temporary .part file used during download assembly.
 *
 * Lifecycle: open → [create sinks, write chunks] → commit (fsync + atomic move)
 *            or → abort (delete temp file, never touch destination)
 */
final class FileAssembler implements Closeable {

    private final Path destination;
    private final Path tempFile;
    private final FileChannel channel;

    private boolean closed = false;

    FileAssembler(Path destination) throws IOException {
        if (Files.isDirectory(destination)) {
            throw new IOException("destination is a directory: " + destination);
        }
        this.destination = destination;
        Path dir = destination.getParent();
        if (dir == null) dir = Path.of(".");
        this.tempFile = Files.createTempFile(dir, destination.getFileName() + ".", ".part");
        this.channel  = FileChannel.open(tempFile,
                StandardOpenOption.WRITE, StandardOpenOption.READ);
    }

    /** Returns a sink that writes at the given byte offset in the temp file. */
    ChunkSink sinkAt(long offset) {
        return new ChunkSink(channel, offset);
    }

    /**
     * Fsyncs the temp file and atomically moves it to the destination.
     * After this call the assembler is closed; do not call again.
     */
    void commit() throws IOException {
        checkOpen();
        channel.force(true); // fsync data + metadata
        channel.close();
        closed = true;
        Files.move(tempFile, destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Deletes the temp file without touching the destination.
     * Safe to call after commit() or multiple times.
     */
    void abort() {
        if (!closed) {
            closed = true;
            try { channel.close(); } catch (IOException ignored) {}
        }
        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
    }

    /** Calls abort() if not already committed; implements try-with-resources. */
    @Override
    public void close() {
        if (!closed) abort();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("FileAssembler already closed");
    }

    Path tempFile() { return tempFile; }
}
