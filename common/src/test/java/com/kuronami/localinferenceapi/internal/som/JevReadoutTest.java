package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevReadout の単体試験 — jevk5 prompt.py / gguf.py 移植のシリアライズ
 * parity、option 展開、校正 softmax、knockout 集約、prompt budget、
 * answer object の wire shape を fake Io(決定的 logprob を返す)で検査する。
 * 実 llama-server との e2e は LlamaNativeSmokeTest が担う。
 */
class JevReadoutTest {

    /** 決定的な Io — tokenize は固定/カスタム長、completion は固定の
     *  top_logprobs map を返す。completionCalls で pass 数を観測する。 */
    private static final class FakeIo implements JevReadout.Io {
        final Map<String, Double> top;
        ToIntFunction<String> len = s -> 64;
        int completionCalls;
        int tokenizeCalls;

        FakeIo(Map<String, Double> top) { this.top = top; }

        @Override public int[] tokenize(String content) {
            tokenizeCalls++;
            return new int[Math.max(1, len.applyAsInt(content))];
        }

        @Override public LlamaClient.Completion completion(int[] tokens, int topK) {
            completionCalls++;
            return new LlamaClient.Completion(top, tokens.length);
        }
    }

    private static JsonObject choiceQ(String... keys) {
        JsonObject criteria = new JsonObject();
        for (String k : keys) criteria.addProperty(k, k);
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions", "pick one");
        q.add("criteria", criteria);
        return q;
    }

    private static JsonObject scoreQ(String... descriptions) {
        JsonObject q = new JsonObject();
        q.addProperty("type", "score");
        q.addProperty("instructions", "urgency?");
        com.google.gson.JsonArray criteria = new com.google.gson.JsonArray();
        for (String d : descriptions) criteria.add(d);
        q.add("criteria", criteria);
        return q;
    }

    private static JsonObject noulQ() {
        JsonObject q = new JsonObject();
        q.addProperty("type", "noul");
        q.addProperty("instructions", "user threatens to leave");
        return q;
    }

    // ---------- pyJsonString: Python json.dumps(ensure_ascii=False) parity

    @Test void pyJsonStringMatchesPythonDumpsEscapes() {
        assertEquals("\"plain\"", JevReadout.pyJsonString("plain"));
        assertEquals("\"a\\\"b\"", JevReadout.pyJsonString("a\"b"));
        assertEquals("\"a\\\\b\"", JevReadout.pyJsonString("a\\b"));
        assertEquals("\"tab\\tnl\\n\"", JevReadout.pyJsonString("tab\tnl\n"));
        // 日本語など非 ASCII は ensure_ascii=False で生のまま
        assertEquals("\"日本語\"", JevReadout.pyJsonString("日本語"));
    }

    @Test void pyJsonStringEscapesControlCharsLikePython() {
        // Python: json.dumps("a\x07b\x1fc", ensure_ascii=False)
        //   -> '"a\\u0007b\\u001fc"' (小文字 hex の \\uXXXX 形)
        // ソース内に生の \\uXXXX 連続は javac の Unicode escape 前処理と
        // 衝突するため、期待値は連結で組み立てる。
        String input = "a" + (char) 0x07 + "b" + (char) 0x1f + "c";
        String expected = "\"a" + "\\" + "u0007" + "b" + "\\" + "u001f" + "c\"";
        assertEquals(expected, JevReadout.pyJsonString(input));
    }

    // ---------- promptText: CHAT_TEMPLATE 展開の parity ----------

    @Test void promptTextMatchesUpstreamChatTemplate() {
        String p = JevReadout.promptText("state text", "pick one",
                List.of("alpha", "beta"));
        assertTrue(p.startsWith("<|im_start|>system\n" + JevReadout.SYSTEM
                + "<|im_end|>\n<|im_start|>user\n"), "system+user opening");
        // user payload は Python json.dumps(ensure_ascii=False) と byte 一致
        assertTrue(p.contains("{\"evidence\": \"state text\", \"criterion\": "
                + "\"pick one\", \"options\": ["
                + "{\"letter\": \"A\", \"description\": \"alpha\"}, "
                + "{\"letter\": \"B\", \"description\": \"beta\"}]}"));
        assertTrue(p.endsWith("<|im_end|>\n<|im_start|>assistant\n"
                + "<think>\n\n</think>\n\n"), "assistant think-block tail");
    }

    // ---------- decisionOptions ----------

    @Test void decisionOptionsChoiceUsesCriteriaKeysInOrder() {
        List<Map.Entry<String, String>> o =
                JevReadout.decisionOptions(choiceQ("billing", "technical"));
        assertEquals(List.of("billing", "technical"),
                o.stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("billing: billing", "technical: technical"),
                o.stream().map(Map.Entry::getValue).toList());
    }

    @Test void decisionOptionsChoiceFallsBackToKeyOnEmptyDescription() {
        JsonObject criteria = new JsonObject();
        criteria.addProperty("x", ""); // 空 description → key そのもの
        JsonObject q = choiceQ();
        q.add("criteria", criteria);
        assertEquals("x: x",
                JevReadout.decisionOptions(q).get(0).getValue());
    }

    @Test void decisionOptionsScoreNumbersArrayPositions() {
        List<Map.Entry<String, String>> o =
                JevReadout.decisionOptions(scoreQ("low", "high"));
        assertEquals(List.of("0", "1"),
                o.stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("0: low", "1: high"),
                o.stream().map(Map.Entry::getValue).toList());
    }

    @Test void decisionOptionsNoulDefaultsToTrueFalse() {
        List<Map.Entry<String, String>> o = JevReadout.decisionOptions(noulQ());
        assertEquals(List.of("true", "false"),
                o.stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("true: The proposition is true.",
                        "false: The proposition is false."),
                o.stream().map(Map.Entry::getValue).toList());
    }

    @Test void decisionOptionsNoulHonorsCustomCriteria() {
        JsonObject criteria = new JsonObject();
        criteria.addProperty("true", "supported by evidence");
        JsonObject q = noulQ();
        q.add("criteria", criteria);
        List<Map.Entry<String, String>> o = JevReadout.decisionOptions(q);
        assertEquals("true: supported by evidence", o.get(0).getValue());
        assertEquals("false: The proposition is false.", o.get(1).getValue());
    }

    @Test void decisionOptionsRejectsUnknownType() {
        JsonObject q = new JsonObject();
        q.addProperty("type", "bogus");
        q.addProperty("instructions", "i");
        q.add("criteria", new JsonObject());
        assertThrows(IllegalArgumentException.class,
                () -> JevReadout.decisionOptions(q));
    }

    // ---------- probabilities: 単一 pass の校正 softmax ----------

    @Test void probabilitiesCalibratesLetterLogprobsAtTemperature() throws IOException {
        // A,B は観測、C は top-k 圏外 → floor = min(seen) - MISSING_MARGIN
        Map<String, Double> seen = new LinkedHashMap<>();
        seen.put("A", -0.5);
        seen.put("B", -1.0);
        seen.put("the", -0.8); // 非 letter token — floor 計算だけが使う
        FakeIo io = new FakeIo(seen);

        JevReadout.Probs p = JevReadout.probabilities(io, "state",
                choiceQ("x", "y", "z"));

        // lp = [-0.5, -1.0, floor=-3.0]、softmax((lp - max) / 1.22)
        double wb = Math.exp(-0.5 / JevReadout.TEMPERATURE);
        double wc = Math.exp(-2.5 / JevReadout.TEMPERATURE);
        double sum = 1.0 + wb + wc;
        assertEquals(1.0 / sum, p.probabilities().get("x"), 1e-9);
        assertEquals(wb / sum, p.probabilities().get("y"), 1e-9);
        assertEquals(wc / sum, p.probabilities().get("z"), 1e-9);
        assertEquals(1, io.completionCalls, "≤16 option は 1 pass");
        // letter 別生 logprob が logits として透過される
        assertEquals(List.of(-0.5, -1.0, -3.0), p.letterLogprobs());
        assertFalse(p.truncated());
    }

    @Test void probabilitiesKnockoutCoversOver16Options() throws IOException {
        // 全 letter を -i*0.5 にする fake — group pass と決勝 pass 共用
        Map<String, Double> seen = new LinkedHashMap<>();
        for (int i = 0; i < 16; i++) {
            seen.put(String.valueOf(JevReadout.LETTERS.charAt(i)), -0.5 * i);
        }
        FakeIo io = new FakeIo(seen);

        JsonObject criteria = new JsonObject();
        for (int i = 0; i < 17; i++) criteria.addProperty("k" + i, "k" + i);
        JsonObject q = choiceQ();
        q.add("criteria", criteria);

        JevReadout.Probs p = JevReadout.probabilities(io, "state", q);

        assertEquals(17, p.probabilities().size());
        double total = p.probabilities().values().stream()
                .mapToDouble(d -> d).sum();
        assertEquals(1.0, total, 1e-9, "結合分布は正規化される");
        for (double v : p.probabilities().values()) {
            assertTrue(v > 0 && Double.isFinite(v));
        }
        assertEquals(3, io.completionCalls,
                "17 option = 2 group pass + 決勝 1 pass");
        // letter A が各 pass で最大 → 先頭 option が argmax
        double best = p.probabilities().values().stream()
                .mapToDouble(d -> d).max().orElseThrow();
        assertEquals(best, p.probabilities().get("k0"), 1e-12);
        // knockout では letter logprob を透過しない — 次元合わせの zeros
        assertEquals(17, p.letterLogprobs().size());
        assertTrue(p.letterLogprobs().stream().allMatch(l -> l == 0.0));
    }

    @Test void probabilitiesTruncatesOversizeState() throws IOException {
        Map<String, Double> seen = new LinkedHashMap<>();
        seen.put("A", -0.1);
        seen.put("B", -1.0);
        FakeIo io = new FakeIo(seen);
        io.len = String::length; // token 数 ≈ char 数 — 8000 char state は超過

        JevReadout.Probs p = JevReadout.probabilities(io, "x".repeat(8000),
                choiceQ("a", "b"));

        assertTrue(p.truncated(), "超過 state は切詰めで truncated");
        assertEquals(2, p.probabilities().size());
        assertTrue(io.tokenizeCalls > 2, "二分探索で複数回 tokenize される");
        assertTrue(p.inputTokens() > 0);
    }

    @Test void probabilitiesRejectsMissingTypeOrInstructions() {
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.add("criteria", new JsonObject()); // instructions 欠落
        FakeIo io = new FakeIo(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> JevReadout.probabilities(io, "s", q));

        JsonObject q2 = new JsonObject();
        q2.addProperty("instructions", "i"); // type 欠落
        assertThrows(IllegalArgumentException.class,
                () -> JevReadout.probabilities(io, "s", q2));
    }

    // ---------- answer: wire shape ----------

    private static JevReadout.Probs probs(Map<String, Double> map,
                                          boolean truncated) {
        List<Double> logits = new ArrayList<>();
        for (int i = 0; i < map.size(); i++) logits.add(-i * 0.5);
        return new JevReadout.Probs(map, 200, truncated, logits);
    }

    @Test void answerChoiceShape() {
        JsonObject a = JevReadout.answer(choiceQ("x", "y"),
                probs(Map.of("x", 0.7, "y", 0.3), false));
        assertEquals("choice", a.get("type").getAsString());
        assertEquals("x", a.get("choice").getAsString());
        assertEquals(0.7, a.getAsJsonObject("probabilities").get("x").getAsDouble());
        assertEquals(2, a.getAsJsonArray("logits").size());
        assertEquals(200, a.get("input_tokens").getAsInt());
        assertEquals(0.7, a.get("confidence").getAsDouble());
        assertFalse(a.has("truncated"), "truncated は true の時だけ出す");
    }

    @Test void answerScoreShape() {
        JsonObject a = JevReadout.answer(scoreQ("l", "h"),
                probs(Map.of("0", 0.25, "1", 0.75), true));
        assertEquals("score", a.get("type").getAsString());
        assertEquals(0.75, a.get("score").getAsDouble(), 1e-9); // Σ index·p
        assertEquals(1, a.get("selected").getAsInt());
        assertTrue(a.get("truncated").getAsBoolean());
    }

    @Test void answerNoulShape() {
        JsonObject a = JevReadout.answer(noulQ(),
                probs(Map.of("true", 0.6, "false", 0.4), false));
        assertEquals("noul", a.get("type").getAsString());
        assertEquals(0.6, a.get("noul").getAsDouble(), 1e-9);
    }
}
