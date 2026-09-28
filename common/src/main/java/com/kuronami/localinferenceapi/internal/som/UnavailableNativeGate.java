package com.kuronami.localinferenceapi.internal.som;

/**
 * 製品既定の native ゲート。常に NO_COMMIT を返し、native 起動を fail-closed
 * で閉じる。pin 済み publisher manifest の同梱・ライセンス notice・実
 * upstream runtime 契約の検証が揃うまで、この実装が製品配線に使われる。
 * staged artifact を探しにディスクを触ることさえしない。
 */
public final class UnavailableNativeGate implements SomNativeGate {

    @Override
    public Availability ensureServing() {
        return Availability.NO_COMMIT;
    }

    @Override
    public int port() {
        return -1;
    }

    @Override
    public void invalidateWorker() {}
}
