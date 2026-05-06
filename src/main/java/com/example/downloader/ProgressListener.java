package com.example.downloader;

@FunctionalInterface
public interface ProgressListener {

    /**
     * Receives a progress event. Invoked from a single dispatcher virtual
     * thread, so implementations need not be thread-safe with respect to
     * each other. Exceptions thrown here are caught, logged once to
     * {@code System.err} with the event class name, and dropped — they do
     * not interrupt the download.
     */
    void onProgress(ProgressEvent event);

    ProgressListener NO_OP = event -> {};
}
