package com.kuronami.localinferenceapi.runtime;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import java.nio.file.Path;
import java.util.*;

/** Laya の公開 build_sequence と同じ入力列を作り、CPUで一問ずつ推論する。 */
public final class ModelHost implements AutoCloseable {
    private static final int CLS = 50281, SEP = 50282, MASK = 50284;
    private static final int MAX_LEN = 1024, HEAD_MAX_LEN = 256;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    /** logits と、参照SDKと同じ規則で入力の短縮が起きたかの印。 */
    record Inference(float[] logits, boolean truncated) {}

    ModelHost(Path model) throws Exception {
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toString(), options);
        }
        try (var stream = Worker.resource("localinferenceapi/model/laya-tokenizer.json")) {
            tokenizer = HuggingFaceTokenizer.newInstance(stream, Map.of("truncation", "false", "padding", "false"));
        }
    }

    private long[] tokens(String value) { return tokenizer.encode(value, false, false).getIds(); }

    private static void append(List<Long> target, long[] source, int limit) {
        for (int i = 0; i < Math.min(limit, source.length); i++) target.add(source[i]);
    }

    Inference infer(String type, String state, String instruction, List<String> options) throws Exception {
        int qtype = switch (type) {
            case "choice" -> 0;
            case "score" -> 1;
            case "noul" -> 2;
            default -> throw new IllegalArgumentException("Unknown question type");
        };
        if (options.isEmpty() || options.size() > 24) throw new IllegalArgumentException("Invalid option count");
        long[] head = tokens(type + " question: " + instruction.replace("[MASK]", " "));
        boolean truncated = false;
        var parts = new ArrayList<long[]>(options.size());
        int optionTokens = 0;
        for (String option : options) {
            long[] encoded = tokens(" " + option.replace("[MASK]", " "));
            if (encoded.length > 48) {
                encoded = Arrays.copyOf(encoded, 48);
                truncated = true;
            }
            parts.add(encoded);
            optionTokens += 1 + encoded.length;
        }
        int budget = HEAD_MAX_LEN - optionTokens;
        if (budget < 16) {
            // SDK の build_sequence と同じく、head に入り切らない時は各 option を均等に詰める。
            int per = Math.max(4, (HEAD_MAX_LEN - 16) / parts.size());
            optionTokens = 0;
            for (int i = 0; i < parts.size(); i++) {
                long[] part = parts.get(i);
                if (part.length > per - 1) {
                    part = Arrays.copyOf(part, per - 1);
                    parts.set(i, part);
                    truncated = true;
                }
                optionTokens += 1 + part.length;
            }
            budget = HEAD_MAX_LEN - optionTokens;
        }
        int headLimit = Math.max(8, budget);
        if (head.length > headLimit) truncated = true;
        List<Long> ids = new ArrayList<>();
        ids.add((long) CLS);
        append(ids, head, headLimit);
        ids.add((long) SEP);
        long[] markers = new long[options.size()];
        for (int i = 0; i < options.size(); i++) {
            markers[i] = ids.size();
            ids.add((long) MASK);
            append(ids, parts.get(i), parts.get(i).length);
        }
        ids.add((long) SEP);
        long[] body = tokens(state.replace("[MASK]", " "));
        int room = Math.max(0, MAX_LEN - ids.size() - 1);
        if (body.length > room) truncated = true;
        append(ids, body, room);
        ids.add((long) SEP);
        long[] sequence = ids.stream().mapToLong(Long::longValue).toArray();
        if (sequence.length > MAX_LEN) throw new IllegalArgumentException("Question exceeds model token limit");
        long[] attention = new long[sequence.length];
        Arrays.fill(attention, 1);
        boolean[] markerMask = new boolean[markers.length];
        Arrays.fill(markerMask, true);
        try (var inputIds = OnnxTensor.createTensor(environment, new long[][] {sequence});
             var attentionMask = OnnxTensor.createTensor(environment, new long[][] {attention});
             var markerPos = OnnxTensor.createTensor(environment, new long[][] {markers});
             var markerMasks = OnnxTensor.createTensor(environment, new boolean[][] {markerMask});
             var questionType = OnnxTensor.createTensor(environment, new long[] {qtype});
             var result = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask,
                     "marker_pos", markerPos, "marker_mask", markerMasks, "qtype", questionType))) {
            return new Inference(((float[][]) result.get("logits").orElseThrow().getValue())[0].clone(), truncated);
        }
    }

    @Override public void close() throws Exception { tokenizer.close(); session.close(); }
}
