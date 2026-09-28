package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.api.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JEV codec 試験。decode は Windows bench で capture した実 laya.cpp 応答
 * (resp_native_sample.json)を fixture として検査する。adapter 独自拡張
 * (logits/truncated/selected passthrough)の round-trip も検査する。
 */
class JevCodecTest {

    // ---------- encode 形状 ----------

    @Test void decisionEncodeMatchesFixtureShape() {
        var body = JevCodec.encodeDecision(
                new DecisionRequest("ctx", "which dept?",
                        List.of("billing", "technical", "sales", "other")));
        assertEquals("ctx", body.get("state").getAsString());
        JsonObject q = body.getAsJsonObject("questions").getAsJsonObject("q");
        assertEquals("choice", q.get("type").getAsString());
        assertEquals("which dept?", q.get("instructions").getAsString());
        JsonObject criteria = q.getAsJsonObject("criteria");
        assertEquals(Set.of("billing", "technical", "sales", "other"), criteria.keySet());
    }

    @Test void scoreEncodeListsDescriptionsAndCarriesLevelValues() {
        var body = JevCodec.encodeScore(new ScoreRequest("ctx", "urgency?",
                List.of(new ScoreLevel("not urgent", 0.0),
                        new ScoreLevel("soon", 1.0),
                        new ScoreLevel("blocking", 2.0))));
        JsonObject q = body.getAsJsonObject("questions").getAsJsonObject("q");
        assertEquals("score", q.get("type").getAsString());
        assertEquals(List.of("not urgent", "soon", "blocking"),
                q.getAsJsonArray("criteria").asList().stream()
                        .map(e -> e.getAsString()).toList());
        // adapter 独自拡張 — ScoreLevel 復元に必要
        assertEquals(List.of(0.0, 1.0, 2.0),
                q.getAsJsonArray("level_values").asList().stream()
                        .map(e -> e.getAsDouble()).toList());
    }

    @Test void noulEncodeOmitsCriteria() {
        var body = JevCodec.encodeNoul(new NoulRequest("ctx", "user threatens to leave"));
        JsonObject q = body.getAsJsonObject("questions").getAsJsonObject("q");
        assertEquals("noul", q.get("type").getAsString());
        assertEquals("user threatens to leave", q.get("instructions").getAsString());
        assertFalse(q.has("criteria"));
    }

    // ---------- decode: 実 capture ----------

    private static JsonObject realAnswers() throws IOException {
        String raw;
        try (InputStream in = JevCodecTest.class.getResourceAsStream("/resp_native_sample.json")) {
            assertNotNull(in, "resp_native_sample.json missing from test resources");
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("answers");
    }

    private static JsonObject wrapAnswer(JsonObject answer) {
        JsonObject answers = new JsonObject();
        answers.add("q", answer);
        JsonObject body = new JsonObject();
        body.add("answers", answers);
        return body;
    }

    @Test void decodeRealCapturedChoice() throws Exception {
        JsonObject body = wrapAnswer(realAnswers().getAsJsonObject("department"));
        var req = new DecisionRequest("ctx", "dept?",
                List.of("billing", "technical", "sales", "other"));
        DecisionResult r = JevCodec.decodeDecision(body, req);
        assertEquals(0, r.selected());
        assertEquals(List.of(1.0, 0.0, 0.0, 0.0), r.probabilities());
        // upstream は logits を運ばない — adapter は次元合わせ zeros(意味のある
        // logit でなく record の次元制約を満たす placeholder)
        assertEquals(List.of(0.0, 0.0, 0.0, 0.0), r.logits());
        assertFalse(r.truncated());
    }

    @Test void decodeRealCapturedScore() throws Exception {
        JsonObject body = wrapAnswer(realAnswers().getAsJsonObject("urgency"));
        var req = new ScoreRequest("ctx", "urgency?",
                List.of(new ScoreLevel("not urgent", 0.0),
                        new ScoreLevel("soon", 1.0),
                        new ScoreLevel("blocking", 2.0)));
        ScoreResult r = JevCodec.decodeScore(body, req);
        // score は Σ level.value·p で再計算される(wire の index 加重 1.7921 ではなく
        // 丸め済み probs からの再計算値 1.7922 — upstream は4桁丸めなので ~1e-4 誤差)
        assertEquals(0.1866 + 2 * 0.8028, r.score(), 1e-9);
        assertEquals(2, r.selectedLevel());
        assertEquals(List.of(0.0107, 0.1866, 0.8028), r.probabilities());
    }

    @Test void decodeNativeScoreRecomputesCallerUnits() throws Exception {
        // laya.cpp r0002 macOS arm64(Core ML build、--cpu backend)へ
        // level_values=[10,50,90] 付きの ScoreRequest を実際に投げて得た応答
        // (2026-09-25 キャプチャ、run/resp_mac_probe_score_lv.json と同一)。
        // upstream は level_values を受理するが無視し、`score` は index 加重
        // (0.9069 = 0·.1381+1·.8169+2·.045)で返す。decode は wire の score を
        // 信用せず caller units(Σ level.value·p = 46.276)へ再計算する。
        JsonObject body;
        try (InputStream in = JevCodecTest.class.getResourceAsStream("/resp_native_score_index.json")) {
            assertNotNull(in, "resp_native_score_index.json missing from test resources");
            body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
        var req = new ScoreRequest("ctx", "urgency?",
                List.of(new ScoreLevel("low", 10), new ScoreLevel("medium", 50),
                        new ScoreLevel("high", 90)));
        ScoreResult r = JevCodec.decodeScore(body, req);
        assertEquals(10 * 0.1381 + 50 * 0.8169 + 90 * 0.045, r.score(), 1e-9);
        assertEquals(1, r.selectedLevel());
        assertEquals(List.of(0.1381, 0.8169, 0.045), r.probabilities());
        assertFalse(r.truncated());
    }

    @Test void decodeRealCapturedNoul() throws Exception {
        JsonObject body = wrapAnswer(realAnswers().getAsJsonObject("churn_risk"));
        NoulResult r = JevCodec.decodeNoul(body);
        assertEquals(0.0447, r.trueProbability(), 1e-9);
        assertFalse(r.truncated());
    }

    // ---------- mapping エラーは invent せず失敗させる ----------

    @Test void decodeErrorsOnUnknownChoiceLabel() {
        JsonObject a = new JsonObject();
        a.addProperty("type", "choice");
        a.addProperty("choice", "nonexistent");
        JsonObject probs = new JsonObject();
        probs.addProperty("nonexistent", 1.0);
        a.add("probabilities", probs);
        assertThrows(JevCodec.MappingException.class, () -> JevCodec.decodeDecision(
                wrapAnswer(a), new DecisionRequest("c", "q", List.of("billing", "other"))));
    }

    @Test void decodeErrorsOnMissingProbabilityLabel() {
        JsonObject a = new JsonObject();
        a.addProperty("type", "choice");
        a.addProperty("choice", "billing");
        JsonObject probs = new JsonObject();
        probs.addProperty("billing", 1.0); // "other" が欠ける
        a.add("probabilities", probs);
        assertThrows(JevCodec.MappingException.class, () -> JevCodec.decodeDecision(
                wrapAnswer(a), new DecisionRequest("c", "q", List.of("billing", "other"))));
    }

    @Test void decodeErrorsOnAnswerTypeMismatch() {
        JsonObject a = new JsonObject();
        a.addProperty("type", "choice");
        a.addProperty("choice", "x");
        a.add("probabilities", new JsonObject());
        assertThrows(JevCodec.MappingException.class, () -> JevCodec.decodeNoul(wrapAnswer(a)));
    }

    @Test void decodeErrorsOnMalformedPassthrough() {
        // adapter 独自拡張が壊れた形の時は黙らず MappingException
        JsonObject a = new JsonObject();
        a.addProperty("type", "choice");
        a.addProperty("choice", "billing");
        JsonObject probs = new JsonObject();
        probs.addProperty("billing", 1.0);
        probs.addProperty("other", 0.0);
        a.add("probabilities", probs);
        a.addProperty("truncated", "yes"); // bool でない
        var req = new DecisionRequest("c", "q", List.of("billing", "other"));
        assertThrows(JevCodec.MappingException.class, () -> JevCodec.decodeDecision(wrapAnswer(a), req));

        a.addProperty("truncated", false);
        com.google.gson.JsonArray shortLogits = new com.google.gson.JsonArray();
        shortLogits.add(0.0);
        a.add("logits", shortLogits); // 次元不一致
        assertThrows(JevCodec.MappingException.class, () -> JevCodec.decodeDecision(wrapAnswer(a), req));
    }

    // ---------- typed result → JEV answer round-trip ----------

    @Test void answerEncodePreservesLogitsAndTruncated() throws Exception {
        var req = new DecisionRequest("ctx", "q?", List.of("a", "b", "c"));
        var result = new DecisionResult(1, List.of(0.1, 0.8, 0.1),
                List.of(-1.5, 2.25, -1.5), true);
        JsonObject body = JevCodec.encodeAnswer(result, JevCodec.encodeDecision(req));
        JsonObject a = body.getAsJsonObject("answers").getAsJsonObject("q");
        assertEquals("choice", a.get("type").getAsString());
        assertEquals("b", a.get("choice").getAsString());
        assertTrue(a.get("truncated").getAsBoolean());
        // decode が passthrough を拾って製品 record を完全復元する
        DecisionResult decoded = JevCodec.decodeDecision(body, req);
        assertEquals(result.selected(), decoded.selected());
        assertEquals(result.probabilities(), decoded.probabilities());
        assertEquals(result.logits(), decoded.logits());
        assertEquals(result.truncated(), decoded.truncated());
    }

    @Test void answerEncodePreservesSelectedAndTruncatedForScoreAndNoul() throws Exception {
        var scoreReq = new ScoreRequest("ctx", "q?",
                List.of(new ScoreLevel("low", 0.0), new ScoreLevel("high", 1.0)));
        // worker の selectedLevel が argmax と違うケースでも passthrough で保存する
        var score = new ScoreResult(0.4, 0, List.of(0.4, 0.6), true);
        JsonObject scoreBody = JevCodec.encodeAnswer(score, JevCodec.encodeScore(scoreReq));
        ScoreResult decodedScore = JevCodec.decodeScore(scoreBody, scoreReq);
        assertEquals(0, decodedScore.selectedLevel());
        // score は decode 時に Σ level.value·p で再計算(0·0.4+1·0.6 = 0.6)
        assertEquals(0.6, decodedScore.score(), 1e-9);
        assertTrue(decodedScore.truncated());

        var noul = new NoulResult(0.7, true);
        JsonObject noulBody = JevCodec.encodeAnswer(noul, JevCodec.encodeNoul(
                new NoulRequest("ctx", "p?")));
        NoulResult decodedNoul = JevCodec.decodeNoul(noulBody);
        assertEquals(0.7, decodedNoul.trueProbability(), 1e-9);
        assertTrue(decodedNoul.truncated());
    }
}
