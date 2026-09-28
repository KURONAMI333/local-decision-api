package com.kuronami.localinferenceapi.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Java21以降の既存通知方式を保ち、Java17では独立したdaemonで配送する。
 *  WorkerClient に加えて SOM router も同じ配送規約を共有する。 */
public final class CompletionThreads {
    private static final Method VIRTUAL = virtualStarter();
    private CompletionThreads() {}

    private static Method virtualStarter() {
        try { return Thread.class.getMethod("startVirtualThread", Runnable.class); }
        catch (NoSuchMethodException java17) { return null; }
    }

    public static void start(Runnable delivery) {
        if (VIRTUAL != null) {
            try { VIRTUAL.invoke(null, delivery); return; }
            catch (IllegalAccessException | InvocationTargetException failure) {
                throw new IllegalStateException("Cannot start completion delivery", failure);
            }
        }
        Thread thread = new Thread(delivery, "localinferenceapi-completion");
        thread.setDaemon(true);
        thread.start();
    }
}
