package com.kuronami.localinferenceapi.internal.som;

/**
 * native worker の serving 状態を供給するゲート。router はこの1点だけを見て
 * native 経路へ進めるかを決める。実装は2系統を想定する:
 *
 *   - 製品配線: {@link UnavailableNativeGate}(常に NO_COMMIT。pin 済みの
 *     publisher manifest・ライセンス notice・実 upstream runtime 契約の
 *     3条件が揃うまで native は絶対に起動しない fail-closed 既定)
 *   - 将来の実 supervisor: staged artifact の commit marker・sha256・
 *     per-instance レコードを検査してから起動する som-windows-java-bootstrap
 *     の SomSupervisor 相当品。manifest が未同梱の間は製品コードに置かない。
 *
 * いずれの実装も router の seq スレッドからのみ呼ばれ、ゲーム/サーバー
 * スレッドをブロックしない。
 */
public interface SomNativeGate extends AutoCloseable {

    /**
     * launch gate / request 失敗の分類。SERVING 以外は router が CPU 経路へ
     * 退避させる。TAMPERED は trust failure として router に latch され、
     * そのセッションでは二度と native gate を試さない。
     */
    enum Availability {
        SERVING,
        NO_COMMIT,
        TAMPERED,
        NO_RUNTIME_ARTIFACT,
        LAUNCH_FAILED,
        STARTUP_TIMEOUT,
        CRASHED
    }

    /**
     * native worker が要求を受け付けられる状態なら SERVING を返す。
     * 起動試行そのものは実装側の規律(bounded retry・no auto-restart loop)に
     * 委ね、この呼出が例外を投げてはいけない。
     */
    Availability ensureServing();

    /** SERVING 時の loopback port。非 SERVING では意味を持たない。 */
    int port();

    /**
     * router が「現在の worker は wedged / 死んだ」と報告する経路。
     * 実装はプロセスを落とし、次の ensureServing() で一度だけ再起動する。
     */
    void invalidateWorker();

    @Override
    default void close() {}
}
