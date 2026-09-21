# SOM Core

Minecraft 1.21.1 向けのローカル選択判定ライブラリ。現在は非公開の技術検証版です。Verdict 初代 151M のゲーム内判断品質は未合格であり、これだけでは mob の行動やゲーム内容を変えません。

配布 JAR に Python 実行環境、推論ライブラリ、モデルを同梱し、最初の判定時に利用中の Java で専用プロセスを起動します。Python の手動導入、モデルの手動配置、API キー、外部推論サーバーは不要です。

```java
import com.kuronami.somcore.api.DecisionRequest;
import com.kuronami.somcore.api.SomCore;

SomCore.decide(new DecisionRequest(
    "The villager is safe at home. It is night.",
    "What should the villager do?",
    java.util.List.of("Sleep", "Go outside")
)).thenAccept(result -> {
    // selected は 0 始まり。null なら選択保留。
    // 完了 callback がゲームスレッドで動く保証はない。
    // ワールドを変更する場合、server.execute(...) 等で戻すこと。
});
```

同期 callback は短時間で返してください。重い処理は `thenAcceptAsync`、ワールド操作はゲームスレッドの executor に渡します。失敗通知は別スレッドから行うことがあります。ゲーム行為の決定権と server/client 間の同期は、この API を使う側の責任です。

入力は不変の文字列スナップショットです。1〜24 個の異なる選択肢を渡せます。空文字列・過大な入力・モデル区切り・不正 Unicode は拒否します。さらにモデルの 512 token 上限は runtime 側で検査し、切り捨てません。

推論は 1 件ずつ、待機は最大 16 件。上限超過、起動失敗、タイムアウト、停止は future の例外完了になります。モデルが返す probabilities は推論スコアであり、ゲーム内での正しさの保証ではありません。起動・推論障害後の無限自動再試行はしません。次のサーバー開始またはクライアント接続で新しい worker を用意します。

キャッシュと worker ログはゲームディレクトリの `.somcore/runtime/` に保存します。初回起動の期限は 180 秒、各推論は 60 秒。サーバー停止またはクライアント切断時に worker と未完了 future を終了します。runtime は専用 Java プロセスのため、他の MOD が利用する GraalPy や JNI とクラスローダーを共有しません。

開発用の内部 runtime は `:runtime:shadowJar` で作られ、Fabric / NeoForge の成果物に `som/runtime.jar` として入ります。Fabric 版は Fabric API も nested mod として同梱し、既存の Fabric API がある場合は Fabric Loader の依存解決に委ねます。公開・配布の準備やゲーム内受入は完了していません。
