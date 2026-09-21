package com.kuronami.localinferenceapi.runtime;
import ai.onnxruntime.*;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.graalvm.polyglot.*;
import java.nio.file.*;
import java.util.*;

/** Pythonから呼ばれるCPU推論。ゲームの型や状態は持たない。 */
public final class ModelHost implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    ModelHost(Path model) throws Exception {
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toString(), options);
        }
        try (var stream = Worker.resource("localinferenceapi/model/tokenizer.json")) {
            tokenizer = HuggingFaceTokenizer.newInstance(stream, Map.of("truncation", "false", "padding", "false"));
        }
    }
    @HostAccess.Export public float[] infer(String prompt) throws Exception {
        var encoding = tokenizer.encode(prompt);
        if (encoding.getIds().length > 512) throw new IllegalArgumentException("Input exceeds 512 tokens");
        try (var ids = OnnxTensor.createTensor(environment, new long[][]{encoding.getIds()});
             var mask = OnnxTensor.createTensor(environment, new long[][]{encoding.getAttentionMask()});
             var result = session.run(Map.of("input_ids", ids, "attention_mask", mask))) {
            return ((float[][]) result.get(0).getValue())[0].clone();
        }
    }
    public void close() throws Exception { tokenizer.close(); session.close(); }
}
