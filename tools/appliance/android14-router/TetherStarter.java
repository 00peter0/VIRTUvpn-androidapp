/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

import android.content.Context;
import android.net.TetheringManager;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TetherStarter {
    private static final int TIMEOUT_SECONDS = 20;

    private TetherStarter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !"start".equals(args[0])) {
            System.out.println("USAGE: TetherStarter start");
            System.exit(2);
        }

        final Context context = systemContext();
        final TetheringManager manager = context.getSystemService(TetheringManager.class);
        if (manager == null) {
            System.out.println("TETHERING_MANAGER_UNAVAILABLE");
            System.exit(3);
        }

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(Integer.MIN_VALUE);
        final Executor direct = new Executor() {
            @Override
            public void execute(final Runnable command) {
                command.run();
            }
        };
        final TetheringManager.TetheringRequest request =
            new TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build();

        manager.startTethering(request, direct, new TetheringManager.StartTetheringCallback() {
            @Override
            public void onTetheringStarted() {
                result.set(0);
                done.countDown();
            }

            @Override
            public void onTetheringFailed(final int error) {
                result.set(error);
                done.countDown();
            }
        });

        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            System.out.println("TETHERING_TIMEOUT");
            System.exit(4);
        }

        final int code = result.get();
        if (code == 0) {
            System.out.println("TETHERING_STARTED");
        } else {
            System.out.println("TETHERING_FAILED:" + code);
            System.exit(5);
        }
    }

    private static Context systemContext() throws Exception {
        final Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        final Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        final Object activityThread = systemMain.invoke(null);
        final Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }
}
