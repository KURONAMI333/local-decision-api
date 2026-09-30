package com.kuronami.localinferenceapi.internal;

import java.io.*;

/** 実プロセスの pipe / EOF / 終了を検査する小さな相手。モデルの性能を模倣する試験ではない。 */
public final class FakeWorker {
    public static void main(String[] args) throws Exception {
        var input = new DataInputStream(System.in);
        var output = new DataOutputStream(System.out);
        if (args[0].equals("startup-hang")) Thread.sleep(30_000);
        if (args[0].equals("startup-crash")) return;
        WorkerClient.writeFrame(output, "{\"ready\":true}");
        int seen = 0;
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
                    String kind = request.has("kind") ? request.get("kind").getAsString() : "choice";
                    if (kind.equals("score")) {
                        if (request.getAsJsonArray("levels").size() != 3) throw new AssertionError("Score levels not transmitted");
                        WorkerClient.writeFrame(output, "{\"score\":1.5,\"selectedLevel\":2,\"probabilities\":[0.1,0.3,0.6]}");
                        continue;
                    }
                    if (kind.equals("noul")) {
                        if (!request.has("proposition")) throw new AssertionError("Noul proposition not transmitted");
                        WorkerClient.writeFrame(output, "{\"trueProbability\":0.8}");
                        continue;
                    }
                    int count = request.getAsJsonArray("choices").size();
                    var probabilities = new com.google.gson.JsonArray();
                    var logits = new com.google.gson.JsonArray();
                    for (int i = 0; i < count; i++) { probabilities.add(1.0 / count); logits.add(0); }
                    var result = new com.google.gson.JsonObject();
                    // "probe" モードは2連続要求の2件目だけ 1 を返す(DeveloperProbe の検査列に合わせる)。
                    result.addProperty("selected", args[0].equals("probe") && seen++ > 0 ? 1 : 0);
                    result.add("probabilities", probabilities);
                    result.add("logits", logits);
                    WorkerClient.writeFrame(output, result.toString());
                }
            }
        }
    }
}
