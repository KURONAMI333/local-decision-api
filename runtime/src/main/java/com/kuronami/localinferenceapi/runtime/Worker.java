package com.kuronami.localinferenceapi.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import com.google.gson.*;
import org.graalvm.polyglot.*;

/** ゲームとは別のJVMで動く、同梱モデル専用のワーカー。ネットワークを使わない。 */
public final class Worker {
    private static final int MAX_FRAME = 1_048_576;
    private static final String HASH = "4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e";
    static InputStream resource(String name) throws IOException {
        var stream = Worker.class.getResourceAsStream("/" + name);
        if (stream == null) throw new IOException("Missing embedded resource: " + name);
        return stream;
    }
    static String read(String name) throws IOException {
        try (var stream = resource(name)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static boolean verified(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest()).equals(HASH);
    }
    private static Path extractModel() throws Exception {
        Path jar = Path.of(Worker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path model = jar.getParent().resolve("model-" + HASH + ".onnx");
        if (verified(model)) return model;
        Path temporary = Files.createTempFile(jar.getParent(), "model-", ".part");
        try {
            try (var source = resource("localinferenceapi/model/model.onnx")) { Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING); }
            if (!verified(temporary)) throw new IOException("Embedded model checksum mismatch");
            try { Files.move(temporary, model, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, model, StandardCopyOption.REPLACE_EXISTING); }
            return model;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void send(DataOutputStream output, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("Response too large");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--worker")) throw new IllegalArgumentException("Expected --worker");
        // stdoutはフレーム専用。ライブラリやPythonの出力を混ぜない。
        var protocol = new DataOutputStream(new BufferedOutputStream(System.out));
        System.setOut(System.err);
        try (var input = new DataInputStream(new BufferedInputStream(System.in));
             var host = new ModelHost(extractModel());
             var python = Context.newBuilder("python")
                 .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT).allowArrayAccess(true).build())
                 .option("engine.WarnInterpreterOnly", "false").out(System.err).err(System.err).build()) {
            python.getBindings("python").putMember("host", host);
            python.getBindings("python").putMember("calibration_json", read("localinferenceapi/model/calibrator.json"));
            python.eval("python", read("adapter.py"));
            Value decide = python.getBindings("python").getMember("decide");
            send(protocol, "{\"ready\":true}");
            while (true) {
                int length;
                try { length = input.readInt(); } catch (EOFException end) { break; }
                if (length < 1 || length > MAX_FRAME) throw new IOException("Invalid frame length");
                byte[] request = input.readNBytes(length);
                if (request.length != length) throw new EOFException("Incomplete frame");
                try { send(protocol, decide.execute(new String(request, StandardCharsets.UTF_8)).asString()); }
                catch (PolyglotException | IllegalArgumentException error) {
                    JsonObject response = new JsonObject();
                    response.addProperty("error", error.getMessage());
                    send(protocol, response.toString());
                }
            }
        } finally { protocol.close(); }
    }
}
