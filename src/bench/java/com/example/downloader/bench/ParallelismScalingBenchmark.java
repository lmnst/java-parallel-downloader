package com.example.downloader.bench;

import com.example.downloader.Downloader;
import com.example.downloader.DownloaderOptions;
import com.example.downloader.DownloadResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Measures how wall-clock download time scales with the {@code parallelism}
 * setting against an in-process HTTP fixture. The fixture is a JDK
 * {@link HttpServer} on the loopback interface that supports HEAD plus
 * range-aware GET (200 with no Range header, 206 with one). A configurable
 * per-request delay simulates RTT without needing tc/netem or Docker.
 *
 * <p>Run via {@code ./gradlew jmh}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms256m", "-Xmx512m"})
public class ParallelismScalingBenchmark {

    private static final long FILE_SIZE = 4L * 1024 * 1024;
    private static final long CHUNK_SIZE = 256L * 1024;

    @Param({"1", "4", "8"})
    public int parallelism;

    @Param({"0", "5"})
    public long delayMs;

    private HttpServer server;
    private URI url;
    private Path tmpDir;
    private Path dest;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        byte[] corpus = new byte[(int) FILE_SIZE];
        new Random(0xC0DECAFEL).nextBytes(corpus);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/file", new RangeHandler(corpus, delayMs));
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
        tmpDir = Files.createTempDirectory("jmh-pdl-");
        dest = tmpDir.resolve("out.bin");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        server.stop(0);
        if (tmpDir != null) {
            Files.walkFileTree(tmpDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Benchmark
    public DownloadResult download() throws InterruptedException {
        DownloaderOptions opts = DownloaderOptions.builder()
                .chunkSize(CHUNK_SIZE)
                .parallelism(parallelism)
                .build();
        try (Downloader d = new Downloader(opts)) {
            return d.download(url, dest);
        }
    }

    private static final class RangeHandler implements HttpHandler {
        private final byte[] body;
        private final long delayMs;

        RangeHandler(byte[] body, long delayMs) {
            this.body = body;
            this.delayMs = delayMs;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            String method = exchange.getRequestMethod();
            String range = exchange.getRequestHeaders().getFirst("Range");
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("ETag", "\"v1\"");

            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            if (range == null) {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }

            // Range: bytes=<start>-<end>
            int eq = range.indexOf('=');
            int dash = range.indexOf('-', eq + 1);
            long start = Long.parseLong(range.substring(eq + 1, dash));
            long end = Long.parseLong(range.substring(dash + 1));
            int len = (int) (end - start + 1);
            exchange.getResponseHeaders().add(
                    "Content-Range", "bytes " + start + "-" + end + "/" + body.length);
            exchange.sendResponseHeaders(206, len);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body, (int) start, len);
            }
        }
    }
}
