package com.kuronami.localinferenceapi.internal;

import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerClientTest {
    @TempDir Path temp;
    static final DecisionRequest REQUEST = new DecisionRequest("A card was lost.", "What next?", List.of("Report it", "Ignore it"));

    Process launch(String mode) throws IOException {
        String classpath = String.join(File.pathSeparator, List.of(FakeWorker.class, WorkerClient.class, JsonParser.class).stream()
                .map(type -> { try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception error) { throw new IllegalStateException(error); } }).toList());
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new ProcessBuilder(java.toString(), "-cp", classpath, FakeWorker.class.getName(), mode)
                .redirectError(ProcessBuilder.Redirect.appendTo(temp.resolve("fake.log").toFile())).start();
    }

    WorkerClient client(String mode) { return new WorkerClient(() -> launch(mode), Duration.ofSeconds(5), Duration.ofSeconds(5)); }

    @Test void inputIsImmutableAndRejectsDelimiters() {
        var mutable = new ArrayList<>(List.of("A", "B"));
        var request = new DecisionRequest("context", "question", mutable);
        mutable.set(0, "changed");
        assertEquals("A", request.choices().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> request.choices().add("C"));
        for (String text : List.of("", " ", "x<<LABEL>>y", "x<<SEP>>y", "x\u0000y", "x\ud800", "[MASK]")) {
            assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(text, "question", List.of("A")));
        }
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("c", "q", Collections.nCopies(25, "A")));
    }

    @Test void startsLazilyAndReusesOneProcess() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (var client = new WorkerClient(() -> { launches.incrementAndGet(); return launch("ok"); }, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            assertEquals(0, launches.get());
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            assertEquals(2, client.decide(REQUEST).get(10, TimeUnit.SECONDS).probabilities().size());
            assertEquals(1, launches.get());
        }
    }

    @Test void retriesOnlyOneWorkerExitBeforeReady() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (var client = new WorkerClient(() -> launch(
                launches.incrementAndGet() == 1 ? "startup-crash" : "ok"),
                Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            assertEquals(2, launches.get());
            assertFalse(client.isClosed());
        }
        launches.set(0);
        try (var client = new WorkerClient(() -> {
            launches.incrementAndGet();
            return launch("startup-crash");
        }, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            assertThrows(ExecutionException.class,
                    () -> client.decide(REQUEST).get(10, TimeUnit.SECONDS));
            assertEquals(2, launches.get(), "startup retry must be bounded");
            assertTrue(client.isClosed());
        }
    }

    @Test void unloadsAfterIdleAndStartsAgainOnNextRequest() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        AtomicReference<Process> first = new AtomicReference<>();
        try (var client = new WorkerClient(() -> {
            Process process = launch("ok");
            if (launches.incrementAndGet() == 1) first.set(process);
            return process;
        }, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(100))) {
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            assertTrue(first.get().waitFor(5, TimeUnit.SECONDS), "idle worker must exit");
            assertFalse(client.isClosed(), "idle unload is not a model failure");
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            assertEquals(2, launches.get());
        }
    }

    @Test void requestDuringIdleShutdownReturnsPromptlyAndWaitsForOldProcessExit() throws Exception {
        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        AtomicReference<Process> first = new AtomicReference<>();
        try (var client = new WorkerClient(() -> {
            Process raw = launch("ok");
            if (launches.incrementAndGet() != 1) return raw;
            first.set(raw);
            return new Process() {
                @Override public OutputStream getOutputStream() { return raw.getOutputStream(); }
                @Override public InputStream getInputStream() { return raw.getInputStream(); }
                @Override public InputStream getErrorStream() { return raw.getErrorStream(); }
                @Override public int waitFor() throws InterruptedException { return raw.waitFor(); }
                @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return raw.waitFor(timeout, unit); }
                @Override public int exitValue() { return raw.exitValue(); }
                @Override public boolean isAlive() { return raw.isAlive(); }
                @Override public void destroy() {
                    stopping.countDown();
                    try { releaseStop.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    raw.destroy();
                }
                @Override public Process destroyForcibly() { raw.destroyForcibly(); return this; }
            };
        }, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(100))) {
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            assertTrue(stopping.await(5, TimeUnit.SECONDS), "idle shutdown must start");
            long start = System.nanoTime();
            var next = client.decide(REQUEST);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500,
                    "submitting a request must not wait for process shutdown");
            assertEquals(1, launches.get(), "new worker must not start before old worker exits");
            releaseStop.countDown();
            assertEquals(0, next.get(10, TimeUnit.SECONDS).selected());
            assertFalse(first.get().isAlive());
            assertEquals(2, launches.get());
        } finally { releaseStop.countDown(); }
    }

    @Test void supportsAllThreePrimitivesOnOneWorker() throws Exception {
        var levels = List.of(new ScoreLevel("not relevant", 0), new ScoreLevel("somewhat relevant", 1), new ScoreLevel("strongly relevant", 2));
        try (var client = client("ok")) {
            assertEquals(0, client.decide(REQUEST).get(10, TimeUnit.SECONDS).selected());
            var score = client.score(new ScoreRequest("query and item", "How relevant is this item?", levels)).get(10, TimeUnit.SECONDS);
            assertEquals(1.5, score.score());
            assertEquals(2, score.selectedLevel());
            assertEquals(3, score.probabilities().size());
            var noul = client.noul(new NoulRequest("query and item", "This item is relevant")).get(10, TimeUnit.SECONDS);
            assertEquals(0.8, noul.trueProbability());
        }
    }

    @Test void validatesScoreAndNoulInputsAndResponseShape() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ScoreRequest("c", "q", List.of(new ScoreLevel("only", 0))));
        assertThrows(IllegalArgumentException.class, () -> new ScoreRequest("c", "q", List.of(new ScoreLevel("low", 1), new ScoreLevel("high", 1))));
        assertThrows(IllegalArgumentException.class, () -> new NoulRequest("c", "bad <<LABEL>> input"));
        assertThrows(IOException.class, () -> WorkerClient.parseScoreResult(JsonParser.parseString("{\"score\":null,\"selectedLevel\":null,\"probabilities\":[0.1,0.9]}").getAsJsonObject(), 2));
        assertThrows(IOException.class, () -> WorkerClient.parseNoulResult(JsonParser.parseString("{\"trueProbability\":null}").getAsJsonObject()));
    }

    @Test void modelInputRejectionDoesNotRestartWorker() throws Exception {
        try (var client = client("error")) {
            assertInstanceOf(IllegalArgumentException.class, failure(client.decide(REQUEST)));
            assertFalse(client.isClosed());
        }
    }

    @Test void limitsWaitingQueueAndCloseCompletesEveryFuture() throws Exception {
        CountDownLatch launched = new CountDownLatch(1);
        AtomicReference<Process> process = new AtomicReference<>();
        var client = new WorkerClient(() -> { var child = launch("hang"); process.set(child); launched.countDown(); return child; },
                Duration.ofSeconds(10), Duration.ofSeconds(30));
        var pending = new ArrayList<CompletableFuture<DecisionResult>>();
        try {
            pending.add(client.decide(REQUEST));
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++) pending.add(client.decide(REQUEST));
            assertInstanceOf(RejectedExecutionException.class, failure(client.decide(REQUEST)));
        } finally { client.close(); }
        for (var future : pending) assertInstanceOf(CancellationException.class, failure(future));
        assertTrue(process.get().waitFor(5, TimeUnit.SECONDS));
        assertTrue(client.decide(REQUEST).isCompletedExceptionally());
    }

    @Test void blockedFailureCallbackCannotStrandOtherRequests() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        var client = client("hang");
        var first = client.decide(REQUEST);
        first.whenComplete((result, error) -> {
            callbackEntered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var second = client.decide(REQUEST);
        try {
            client.close();
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure(second));
        } finally { release.countDown(); client.close(); }
    }

    @Test void blockedSuccessCallbackDoesNotStopNextInference() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        try (var client = new WorkerClient(() -> launchAfter("ok", start), Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            var first = client.decide(REQUEST);
            var callback = first.thenAccept(result -> blockCallback(callbackEntered, release));
            start.countDown();
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, client.decide(REQUEST).get(3, TimeUnit.SECONDS).selected());
                assertFalse(callback.isDone(), "The first callback must still be blocked");
            } finally { release.countDown(); }
            callback.get(5, TimeUnit.SECONDS);
        } finally { start.countDown(); release.countDown(); }
    }

    @Test void blockedInputRejectionCallbackDoesNotStopNextSuccessfulInference() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        var rejected = new DecisionRequest("reject this input", REQUEST.question(), REQUEST.choices());
        try (var client = new WorkerClient(() -> launchAfter("reject-selected-input", start), Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            var first = client.decide(rejected);
            var callback = first.handle((result, error) -> {
                assertInstanceOf(IllegalArgumentException.class, error);
                blockCallback(callbackEntered, release);
                return null;
            });
            start.countDown();
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, client.decide(REQUEST).get(3, TimeUnit.SECONDS).selected());
                assertFalse(callback.isDone(), "The rejection callback must still be blocked");
                assertFalse(client.isClosed());
            } finally { release.countDown(); }
            callback.get(5, TimeUnit.SECONDS);
        } finally { start.countDown(); release.countDown(); }
    }

    @Test void closeRacingWithResponseNotificationLeavesNoUnfinishedRequests() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<Process> process = new AtomicReference<>();
        var client = new WorkerClient(() -> {
            var child = launchAfter("ok", start);
            process.set(child);
            return child;
        }, Duration.ofSeconds(5), Duration.ofSeconds(5));
        var first = client.decide(REQUEST);
        var blocked = first.thenAccept(result -> blockCallback(callbackEntered, release));
        var requests = new ArrayList<CompletableFuture<DecisionResult>>();
        var completions = new ArrayList<CompletableFuture<Void>>();
        start.countDown();
        try {
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            // 確定済みの通知が止まっている間に、後続の応答受理と close を競合させる。
            for (int i = 0; i < 16; i++) {
                var future = client.decide(REQUEST);
                requests.add(future);
                completions.add(future.handle((result, error) -> {
                    notifications.incrementAndGet();
                    if (error != null) assertInstanceOf(CancellationException.class, error);
                    else assertNotNull(result);
                    return null;
                }));
            }
            client.close();
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(requests.size(), notifications.get());
            assertTrue(requests.stream().allMatch(CompletableFuture::isDone));
            assertFalse(blocked.isDone(), "close must not wait for the accepted response callback");
            assertTrue(process.get().waitFor(5, TimeUnit.SECONDS));
        } finally { release.countDown(); start.countDown(); client.close(); }
        blocked.get(5, TimeUnit.SECONDS);
        assertEquals(requests.size(), notifications.get());
    }

    private Process launchAfter(String mode, CountDownLatch start) throws IOException {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IOException("Test launch gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Test launch interrupted", interrupted);
        }
        return launch(mode);
    }

    private static void blockCallback(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Callback release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Callback interrupted", interrupted);
        }
    }

    @Test void startupTimeoutCompletesPendingAndDoesNotRetry() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (var client = new WorkerClient(() -> { launches.incrementAndGet(); return launch("startup-hang"); }, Duration.ofMillis(300), Duration.ofSeconds(5))) {
            assertInstanceOf(TimeoutException.class, failure(client.decide(REQUEST)));
            assertInstanceOf(TimeoutException.class, failure(client.decide(REQUEST)));
            assertEquals(1, launches.get());
        }
    }

    @Test void inferenceTimeoutStopsWorker() throws Exception {
        try (var client = new WorkerClient(() -> launch("hang"), Duration.ofSeconds(5), Duration.ofMillis(300))) {
            assertInstanceOf(TimeoutException.class, failure(client.decide(REQUEST)));
            assertTrue(client.isClosed());
        }
    }

    @Test void oversizedResponseAndCrashFailAllPending() throws Exception {
        for (String mode : List.of("bad-frame", "crash")) {
            try (var client = client(mode)) {
                var first = client.decide(REQUEST);
                var second = client.decide(REQUEST);
                assertInstanceOf(IllegalStateException.class, failure(first));
                assertInstanceOf(IllegalStateException.class, failure(second));
            }
        }
    }

    @Test void responseMustMatchOptionsAndValidIndex() throws Exception {
        assertThrows(IOException.class, () -> WorkerClient.parseResult(JsonParser.parseString(
                "{\"selected\":0,\"probabilities\":[1.0],\"logits\":[0]}").getAsJsonObject(), 2));
        assertThrows(IOException.class, () -> WorkerClient.parseResult(JsonParser.parseString(
                "{\"selected\":2,\"probabilities\":[0.2,0.8],\"logits\":[0,1]}").getAsJsonObject(), 2));
        assertThrows(IOException.class, () -> WorkerClient.parseResult(JsonParser.parseString(
                "{\"selected\":null,\"probabilities\":[0.2,0.8],\"logits\":[0,1]}").getAsJsonObject(), 2));
        assertTrue(WorkerClient.parseResult(JsonParser.parseString(
                "{\"selected\":0,\"probabilities\":[0.2,0.8],\"logits\":[0,1],\"truncated\":true}").getAsJsonObject(), 2).truncated());
        assertFalse(WorkerClient.parseResult(JsonParser.parseString(
                "{\"selected\":0,\"probabilities\":[0.2,0.8],\"logits\":[0,1]}").getAsJsonObject(), 2).truncated());
    }

    @Test void cacheRepairsCorruptionAndLeavesNoPartialFile() throws Exception {
        byte[] content = "fixture jar bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path cached = RuntimeCache.extract(temp, () -> new ByteArrayInputStream(content));
        Files.writeString(cached, "corrupt");
        assertEquals(cached, RuntimeCache.extract(temp, () -> new ByteArrayInputStream(content)));
        assertArrayEquals(content, Files.readAllBytes(cached));
        assertThrows(IOException.class, () -> RuntimeCache.extract(temp, () -> null));
        try (var files = Files.list(temp)) { assertTrue(files.noneMatch(path -> path.toString().endsWith(".part"))); }
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); fail("Expected failure"); return null; }
        catch (ExecutionException failure) { return failure.getCause(); }
        catch (CancellationException cancelled) { return cancelled; }
    }
}
