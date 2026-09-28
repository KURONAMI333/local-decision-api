package com.kuronami.localinferenceapi.runtime;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** ゲームとは別のJVMで動く、同梱Layaモデル専用のワーカー。ネットワークを使わない。 */
public final class Worker {
    private static final int MAX_FRAME = 1_048_576;
    private static final String GRAPH_HASH = "599756d6506db9659279f4ac7871045801f90539844fbda6cd2918b6316e2d07";
    private static final String DATA_HASH = "e5ac4bfe0503361dacac825a91a82ae860e3a5d38dfb021dcf0bd37369573b44";
    private static final String GRAPH = "laya_q8e8.onnx", DATA = "laya_q8e8.onnx.data";

    static InputStream resource(String name) throws IOException {
        InputStream stream = Worker.class.getResourceAsStream("/" + name);
        if (stream == null) throw new IOException("Missing embedded resource: " + name);
        return stream;
    }

    private static boolean verified(Path path, String expected) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest()).equals(expected);
    }

    private static void extract(Path directory, String filename, String expected) throws Exception {
        Path output = directory.resolve(filename);
        if (verified(output, expected)) return;
        Path temporary = Files.createTempFile(directory, filename + "-", ".part");
        try {
            try (var source = resource("localinferenceapi/model/" + filename)) {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!verified(temporary, expected)) throw new IOException("Embedded model checksum mismatch: " + filename);
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Path extractModel() throws Exception {
        Path jar = Path.of(Worker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path directory = jar.getParent().resolve("model-" + GRAPH_HASH);
        Files.createDirectories(directory);
        // 外部dataを先に置き、graphを最後に公開する。
        extract(directory, DATA, DATA_HASH);
        extract(directory, GRAPH, GRAPH_HASH);
        return directory.resolve(GRAPH);
    }

    private static String required(JsonObject request, String name) {
        JsonElement value = request.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid " + name);
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 32_768) throw new IllegalArgumentException("Invalid " + name);
        return text;
    }

    private static List<String> options(JsonObject request, String name) {
        JsonArray array = request.getAsJsonArray(name);
        if (array == null || array.isEmpty() || array.size() > 24) throw new IllegalArgumentException("Invalid " + name);
        var values = new ArrayList<String>(array.size());
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid " + name);
            String label = value.getAsString();
            if (label.isBlank() || label.length() > 4_096) throw new IllegalArgumentException("Invalid " + name);
            values.add(label);
        }
        return values;
    }

    private static double temperature(JsonObject config, String kind, int k) {
        String bucket = k <= 2 ? "2" : k <= 5 ? "3-5" : k <= 10 ? "6-10" : "11+";
        JsonObject byOptions = config.getAsJsonObject("temperature_by_options");
        JsonElement specific = byOptions.get(kind + ":" + bucket);
        int type = switch (kind) { case "choice" -> 0; case "score" -> 1; default -> 2; };
        double value = specific == null ? config.getAsJsonArray("temperature").get(type).getAsDouble() : specific.getAsDouble();
        return Double.isFinite(value) ? Math.max(0.5, Math.min(5.0, value)) : 1.0;
    }

    private static double[] probabilities(float[] logits, double temperature) {
        double maximum = Arrays.stream(toDoubles(logits)).max().orElseThrow() / temperature;
        double[] result = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) sum += result[i] = Math.exp(logits[i] / temperature - maximum);
        for (int i = 0; i < result.length; i++) result[i] /= sum;
        return result;
    }

    private static double[] toDoubles(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    private static JsonArray array(double[] values) {
        JsonArray result = new JsonArray();
        for (double value : values) result.add(value);
        return result;
    }

    private static JsonObject decide(ModelHost host, JsonObject config, JsonObject request) throws Exception {
        String kind = request.has("kind") ? required(request, "kind") : "choice";
        String state = required(request, "context");
        String question;
        List<String> labels;
        double[] values = null;
        if (kind.equals("choice")) {
            question = required(request, "question");
            labels = options(request, "choices");
            if (labels.size() == 1) {
                JsonObject result = new JsonObject();
                result.addProperty("selected", 0);
                result.add("probabilities", array(new double[] {1.0}));
                result.add("logits", array(new double[] {0.0}));
                return result;
            }
        } else if (kind.equals("score")) {
            question = required(request, "question");
            JsonArray levels = request.getAsJsonArray("levels");
            if (levels == null || levels.size() < 2 || levels.size() > 10) throw new IllegalArgumentException("Invalid levels");
            labels = new ArrayList<>();
            values = new double[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                JsonObject level = levels.get(i).getAsJsonObject();
                labels.add("level " + i + ": " + required(level, "description"));
                values[i] = level.get("value").getAsDouble();
                if (!Double.isFinite(values[i]) || i > 0 && values[i] <= values[i - 1])
                    throw new IllegalArgumentException("Invalid level value");
            }
        } else if (kind.equals("noul")) {
            question = required(request, "proposition");
            labels = List.of("false: no, the statement does not hold", "true: yes, the statement holds");
        } else throw new IllegalArgumentException("Unknown request kind");
        ModelHost.Inference inference = host.infer(kind, state, question, labels);
        float[] logits = inference.logits();
        double[] probabilities = probabilities(logits, temperature(config, kind, labels.size()));
        int selected = 0;
        for (int i = 1; i < probabilities.length; i++) if (probabilities[i] > probabilities[selected]) selected = i;
        JsonObject response = new JsonObject();
        if (inference.truncated()) response.addProperty("truncated", true);
        if (kind.equals("choice")) {
            response.addProperty("selected", selected);
            response.add("probabilities", array(probabilities));
            response.add("logits", array(toDoubles(logits)));
        } else if (kind.equals("score")) {
            double score = 0;
            for (int i = 0; i < values.length; i++) score += values[i] * probabilities[i];
            response.addProperty("score", score);
            response.addProperty("selectedLevel", selected);
            response.add("probabilities", array(probabilities));
        } else response.addProperty("trueProbability", probabilities[1]);
        return response;
    }

    private static void send(DataOutputStream output, JsonObject message) throws IOException {
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("Response too large");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--worker")) throw new IllegalArgumentException("Expected --worker");
        var protocol = new DataOutputStream(new BufferedOutputStream(System.out));
        System.setOut(System.err);
        JsonObject config;
        try (var stream = resource("localinferenceapi/model/laya-config.json")) {
            config = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        try (var input = new DataInputStream(new BufferedInputStream(System.in));
             var host = new ModelHost(extractModel())) {
            JsonObject ready = new JsonObject();
            ready.addProperty("ready", true);
            send(protocol, ready);
            while (true) {
                int length;
                try { length = input.readInt(); } catch (EOFException end) { break; }
                if (length < 1 || length > MAX_FRAME) throw new IOException("Invalid frame length");
                byte[] request = input.readNBytes(length);
                if (request.length != length) throw new EOFException("Incomplete frame");
                try { send(protocol, decide(host, config, JsonParser.parseString(new String(request, StandardCharsets.UTF_8)).getAsJsonObject())); }
                catch (IllegalArgumentException | JsonParseException error) {
                    JsonObject response = new JsonObject();
                    response.addProperty("error", error.getMessage());
                    send(protocol, response);
                }
            }
        } finally { protocol.close(); }
    }
}
