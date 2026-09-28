package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.NoulResult;
import com.kuronami.localinferenceapi.api.ScoreLevel;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.kuronami.localinferenceapi.api.ScoreResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JEV `/v1/systemone` codec — フィールド名は Windows bench で capture した
 * 実 request/response(win_fixture_*.json・resp_8117_*.json)に基づく:
 *
 *   request : {"state": "...", "questions": {"q": {"type": "choice|score|noul",
 *               "instructions": "...", "criteria": {label: desc} | [desc] }}}
 *   response: {"answers": {"q": {"type": ..., "choice"|"score"|"noul": ...,
 *               "probabilities": {...}, ...}}, "usage": ...}
 *
 * 本 API は 1 call = 1 question なので、固定の質問名 {@value #Q} を使う。
 *
 * 正直な gap(invent せず表面に出す):
 *  - DecisionRequest は label 毎の description を持たないが JEV の criteria
 *    object は label→description を期待する。{label: label} で自己記述させる
 *    (より良い description は upstream 側の API 拡張が必要で、ここでは主張
 *    しない)。
 *  - JEV の score `criteria` は description しか運ばず、level の数値は
 *    adapter 独自拡張 `level_values` で運ぶ。upstream(laya.cpp r0002)は
 *    extra key を受理するが無視する(Mac live 実測: byte 同一応答)。
 *    そのため upstream の `score` 応答値は index 重み付け Σi·p_i であり
 *    呼出側の ScoreLevel.value を反映しない — decodeScore は wire の
 *    `score` を信用せず caller units で再計算する。`legend` は index→
 *    criteria の写像で decode には不要。
 *  - native 応答には `logits`・`truncated` が無い。CPU adapter 経由の応答は
 *    adapter 独自拡張として `logits`(配列)・`truncated`・`selected`(score
 *    index)を透過し、製品 record の semantics を round-trip で保存する。
 *    実 upstream 由来の応答にこれらが無い場合の既定は logits=zeros・
 *    truncated=false・selected=probabilities argmax。
 *  - `confidence`/`action.act_probability`/`usage` は upstream に存在するが
 *    製品側に対応 field が無いため drop する。
 */
public final class JevCodec {

    static final String Q = "q";

    private JevCodec() {}

    /** typed ↔ wire 変換の失敗 — request 単位のエラーであり worker 故障では
     *  ない(WorkerClient の "rejected request" 契約に揃える)。 */
    public static final class MappingException extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        MappingException(String msg) { super(msg); }
    }

    // ---------- typed → JEV ----------

    public static JsonObject encodeDecision(DecisionRequest req) {
        JsonObject criteria = new JsonObject();
        for (String label : req.choices()) criteria.addProperty(label, label);
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions", req.question());
        q.add("criteria", criteria);
        return wrap(req.context(), q);
    }

    public static JsonObject encodeScore(ScoreRequest req) {
        JsonArray criteria = new JsonArray();
        JsonArray values = new JsonArray();
        for (ScoreLevel lv : req.levels()) {
            criteria.add(lv.description());
            values.add(lv.value());
        }
        JsonObject q = new JsonObject();
        q.addProperty("type", "score");
        q.addProperty("instructions", req.question());
        q.add("criteria", criteria);
        // adapter 独自拡張: JEV criteria は level value を運ばないため、CPU
        // adapter が ScoreLevel を復元できるよう別キーで送る。upstream が
        // extra key を拒否するかは未検証(report に明記)。
        q.add("level_values", values);
        return wrap(req.context(), q);
    }

    public static JsonObject encodeNoul(NoulRequest req) {
        JsonObject q = new JsonObject();
        q.addProperty("type", "noul");
        q.addProperty("instructions", req.proposition());
        return wrap(req.context(), q);
    }

    private static JsonObject wrap(String state, JsonObject question) {
        JsonObject questions = new JsonObject();
        questions.add(Q, question);
        JsonObject body = new JsonObject();
        body.addProperty("state", state);
        body.add("questions", questions);
        return body;
    }

    // ---------- JEV → typed request(CPU adapter 側) ----------

    /**
     * JEV request body を、それが組み立てられた typed 要求へ復元する。
     * 戻り値は DecisionRequest | ScoreRequest | NoulRequest、呼出側が
     * instanceof で振り分ける。
     */
    public static Object decodeRequest(JsonObject body) throws MappingException {
        JsonObject q = questionObject(body);
        String type = text(q, "type");
        String state = text(body, "state");
        String instructions = text(q, "instructions");
        switch (type) {
            case "choice" -> {
                JsonObject criteria = object(q, "criteria");
                List<String> choices = new ArrayList<>();
                for (String key : criteria.keySet()) choices.add(key);
                if (choices.isEmpty()) throw new MappingException("choice criteria empty");
                return new DecisionRequest(state, instructions, choices);
            }
            case "score" -> {
                JsonArray criteria = array(q, "criteria");
                // adapter 独自拡張。不在時は index 値へ degrade する
                List<Double> values = new ArrayList<>();
                if (q.has("level_values") && q.get("level_values").isJsonArray()) {
                    for (JsonElement v : q.getAsJsonArray("level_values")) {
                        values.add(v.getAsDouble());
                    }
                }
                List<ScoreLevel> levels = new ArrayList<>();
                for (int i = 0; i < criteria.size(); i++) {
                    double v = i < values.size() ? values.get(i) : (double) i;
                    levels.add(new ScoreLevel(criteria.get(i).getAsString(), v));
                }
                return new ScoreRequest(state, instructions, levels);
            }
            case "noul" -> {
                return new NoulRequest(state, instructions);
            }
            default -> throw new MappingException("unsupported question type: " + type);
        }
    }

    // ---------- JEV response → typed result ----------

    public static DecisionResult decodeDecision(JsonObject response,
                                                DecisionRequest req)
            throws MappingException {
        JsonObject a = answer(response, "choice");
        String label = text(a, "choice");
        int selected = req.choices().indexOf(label);
        if (selected < 0) {
            throw new MappingException("native chose label not in request: " + label);
        }
        JsonObject probs = object(a, "probabilities");
        List<Double> ordered = new ArrayList<>();
        for (String c : req.choices()) {
            if (!probs.has(c) || !probs.get(c).isJsonPrimitive()) {
                throw new MappingException("probabilities missing label: " + c);
            }
            ordered.add(probs.get(c).getAsDouble());
        }
        // upstream は logits を運ばない → 既定は次元合わせの zeros(製品
        // record が probs/logits 同次元を要求するための placeholder であり、
        // semantic な logit ではない)。CPU adapter は自前拡張 "logits" で
        // 実値を透過する。
        List<Double> logits = new ArrayList<>(java.util.Collections.nCopies(ordered.size(), 0.0));
        JsonElement passthrough = a.get("logits");
        if (passthrough != null) logits = numbers(passthrough, ordered.size(), "logits");
        return new DecisionResult(selected, ordered, logits, flag(a, "truncated"));
    }

    public static ScoreResult decodeScore(JsonObject response,
                                          ScoreRequest req) throws MappingException {
        JsonObject a = answer(response, "score");
        JsonObject probs = object(a, "probabilities");
        List<Double> ordered = new ArrayList<>();
        int argmax = 0;
        for (int i = 0; i < req.levels().size(); i++) {
            String k = String.valueOf(i);
            if (!probs.has(k) || !probs.get(k).isJsonPrimitive()) {
                throw new MappingException("score probabilities missing index " + i);
            }
            double p = probs.get(k).getAsDouble();
            ordered.add(p);
            if (p > ordered.get(argmax)) argmax = i;
        }
        // upstream の `score` は index 重み付け(Σ i·p_i)であり呼出側の
        // ScoreLevel.value を知らない — wire の値は使わず、製品 contract
        // (各段階の value を確率で重み付けした平均)をここで再計算する。
        // CPU adapter 経路では worker が同じ値を返すので冪等。
        // upstream は確率を4桁で丸めるため厳密な誤差 ~1e-4 が残る。
        double score = 0;
        for (int i = 0; i < req.levels().size(); i++) {
            score += req.levels().get(i).value() * ordered.get(i);
        }
        // upstream は selected index を運ばない → argmax が既定。CPU adapter
        // は自前拡張 "selected" で worker 応答の selectedLevel を透過する。
        int selected = argmax;
        JsonElement passthrough = a.get("selected");
        if (passthrough != null) {
            if (!passthrough.isJsonPrimitive() || !passthrough.getAsJsonPrimitive().isNumber()) {
                throw new MappingException("invalid selected passthrough");
            }
            try {
                selected = passthrough.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException nonInteger) {
                throw new MappingException("invalid selected passthrough");
            }
            if (selected < 0 || selected >= ordered.size()) {
                throw new MappingException("selected passthrough outside request");
            }
        }
        return new ScoreResult(score, selected, ordered, flag(a, "truncated"));
    }

    public static NoulResult decodeNoul(JsonObject response) throws MappingException {
        JsonObject a = answer(response, "noul");
        return new NoulResult(number(a, "noul"), flag(a, "truncated"));
    }

    // ---------- typed result → JEV answer(CPU adapter 側) ----------

    /** typed 結果を JEV 形の answer body {"answers":{"q":{}}} に包む。 */
    public static JsonObject encodeAnswer(Object typedResult, JsonObject origRequest)
            throws MappingException {
        JsonObject a = new JsonObject();
        if (typedResult instanceof DecisionResult d) {
            List<String> choices = decodeRequestChoices(origRequest);
            a.addProperty("type", "choice");
            if (d.selected() != null) {
                a.addProperty("choice", choices.get(d.selected()));
            }
            JsonObject probs = new JsonObject();
            for (int i = 0; i < choices.size(); i++) {
                probs.addProperty(choices.get(i),
                        i < d.probabilities().size() ? d.probabilities().get(i) : 0.0);
            }
            a.add("probabilities", probs);
            // adapter 独自拡張: CPU worker の実 logits/truncated を透過する
            JsonArray logits = new JsonArray();
            for (double l : d.logits()) logits.add(l);
            a.add("logits", logits);
            a.addProperty("truncated", d.truncated());
        } else if (typedResult instanceof ScoreResult s) {
            a.addProperty("type", "score");
            a.addProperty("score", s.score());
            JsonObject probs = new JsonObject();
            for (int i = 0; i < s.probabilities().size(); i++) {
                probs.addProperty(String.valueOf(i), s.probabilities().get(i));
            }
            a.add("probabilities", probs);
            a.addProperty("selected", s.selectedLevel());
            a.addProperty("truncated", s.truncated());
        } else if (typedResult instanceof NoulResult n) {
            a.addProperty("type", "noul");
            a.addProperty("noul", n.trueProbability());
            a.addProperty("truncated", n.truncated());
        } else {
            throw new MappingException("cannot encode result type: " + typedResult);
        }
        JsonObject answers = new JsonObject();
        answers.add(Q, a);
        JsonObject body = new JsonObject();
        body.add("answers", answers);
        return body;
    }

    private static List<String> decodeRequestChoices(JsonObject origRequest)
            throws MappingException {
        JsonObject criteria = object(questionObject(origRequest), "criteria");
        return new ArrayList<>(criteria.keySet());
    }

    // ---------- internals ----------

    private static JsonObject questionObject(JsonObject body) throws MappingException {
        JsonObject questions = object(body, "questions");
        if (!questions.has(Q) || !questions.get(Q).isJsonObject()) {
            throw new MappingException("missing questions." + Q);
        }
        return questions.getAsJsonObject(Q);
    }

    private static JsonObject answer(JsonObject response, String expectedType)
            throws MappingException {
        JsonObject answers = object(response, "answers");
        if (!answers.has(Q) || !answers.get(Q).isJsonObject()) {
            throw new MappingException("response missing answers." + Q);
        }
        JsonObject a = answers.getAsJsonObject(Q);
        String type = a.has("type") && a.get("type").isJsonPrimitive()
                ? a.get("type").getAsString() : expectedType;
        if (!expectedType.equals(type)) {
            throw new MappingException(
                    "answer type " + type + " does not match " + expectedType);
        }
        return a;
    }

    private static JsonObject object(JsonObject o, String key) throws MappingException {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            throw new MappingException("missing object field: " + key);
        }
        return o.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject o, String key) throws MappingException {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            throw new MappingException("missing array field: " + key);
        }
        return o.getAsJsonArray(key);
    }

    private static String text(JsonObject o, String key) throws MappingException {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()
                || !o.get(key).getAsJsonPrimitive().isString()) {
            throw new MappingException("missing string field: " + key);
        }
        return o.get(key).getAsString();
    }

    private static double number(JsonObject o, String key) throws MappingException {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()
                || !o.get(key).getAsJsonPrimitive().isNumber()) {
            throw new MappingException("missing number field: " + key);
        }
        return o.get(key).getAsDouble();
    }

    /** adapter 独自拡張の配列 passthrough。形状が合わなければ MappingException。 */
    private static List<Double> numbers(JsonElement el, int expected, String field)
            throws MappingException {
        if (!el.isJsonArray() || el.getAsJsonArray().size() != expected) {
            throw new MappingException("passthrough " + field + " has wrong shape");
        }
        List<Double> values = new ArrayList<>(expected);
        for (JsonElement v : el.getAsJsonArray()) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw new MappingException("passthrough " + field + " is not numeric");
            }
            values.add(v.getAsDouble());
        }
        return values;
    }

    /** adapter 独自拡張の真偽 passthrough。bool 以外は MappingException。 */
    private static boolean flag(JsonObject o, String key) throws MappingException {
        JsonElement el = o.get(key);
        if (el == null) return false;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new MappingException("passthrough " + key + " is not boolean");
        }
        return el.getAsBoolean();
    }
}
