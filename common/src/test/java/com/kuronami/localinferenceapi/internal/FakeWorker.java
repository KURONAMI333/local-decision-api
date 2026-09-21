package com.kuronami.localinferenceapi.internal;

import java.io.*;

/** 実プロセスの pipe / EOF / 終了を検査する小さな相手。モデルの性能を模倣する試験ではない。 */
public final class FakeWorker {
    public static void main(String[] args) throws Exception {
        var input = new DataInputStream(System.in);
        var output = new DataOutputStream(System.out);
        if (args[0].equals("startup-hang")) Thread.sleep(30_000);
        WorkerClient.writeFrame(output, "{\"ready\":true}");
        while (true) {
            com.google.gson.JsonObject request;
            try { request = WorkerClient.readFrame(input); } catch (EOFException eof) { return; }
            switch (args[0]) {
                case "hang" -> Thread.sleep(30_000);
                case "bad-frame" -> { output.writeInt(Integer.MAX_VALUE); output.flush(); return; }
                case "crash" -> { return; }
                case "error" -> WorkerClient.writeFrame(output, "{\"error\":\"input too long\"}");
                default -> {
                    if (args[0].equals("reject-selected-input") && request.get("context").getAsString().equals("reject this input")) {
                        WorkerClient.writeFrame(output, "{\"error\":\"input too long\"}");
                        continue;
                    }
                    int count = request.getAsJsonArray("choices").size() + 1;
                    var probabilities = new com.google.gson.JsonArray();
                    var logits = new com.google.gson.JsonArray();
                    for (int i = 0; i < count; i++) { probabilities.add(1.0 / count); logits.add(0); }
                    var result = new com.google.gson.JsonObject();
                    result.addProperty("selected", 0);
                    result.add("probabilities", probabilities);
                    result.add("logits", logits);
                    WorkerClient.writeFrame(output, result.toString());
                }
            }
        }
    }
}
