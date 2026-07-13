package com.bytedance.zgx.solin.releasesmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemClock;
import com.bytedance.zgx.solin.memory.EmbeddingRuntime;
import com.bytedance.zgx.solin.runtime.TfliteTextEmbeddingRuntimeFactory;
import java.io.File;
import java.util.Locale;

public final class ReleaseSmokeInstrumentation extends Instrumentation {
    public static final String LAUNCH_TEST_CLASS =
            "com.bytedance.zgx.solin.ReleaseSignedSmokeDeviceTest";
    public static final String EMBEDDING_TEST_CLASS =
            "com.bytedance.zgx.solin.ReleaseEmbeddingSmokeDeviceTest";
    private static final long LAUNCH_TIMEOUT_MILLIS = 45_000L;
    private Bundle arguments;
    private String requestedTestClass;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? Bundle.EMPTY : new Bundle(arguments);
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        long startedAt = SystemClock.elapsedRealtime();
        requestedTestClass = arguments.getString("class");
        sendStatus(1, testStatus("\n" + requestedTestClass + ":"));
        try {
            verifyRequestedTestClass();
            if (EMBEDDING_TEST_CLASS.equals(requestedTestClass)) {
                runEmbeddingProbe();
            } else {
                verifyMainShellLaunchContract();
            }
            sendStatus(0, testStatus("."));
            Bundle result = new Bundle();
            result.putString(
                    REPORT_KEY_STREAMRESULT,
                    "\nTime: " + elapsedSeconds(startedAt) + "\n\nOK (1 test)\n"
            );
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Bundle result = new Bundle();
            result.putString("shortMsg", error.toString());
            result.putString(
                    REPORT_KEY_STREAMRESULT,
                    "\nFAILURES!!!\nTests run: 1,  Failures: 1\n" + error + "\n"
            );
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyRequestedTestClass() {
        if (
                !LAUNCH_TEST_CLASS.equals(requestedTestClass)
                        && !EMBEDDING_TEST_CLASS.equals(requestedTestClass)
        ) {
            throw new IllegalArgumentException(
                    "Unexpected release smoke class: " + requestedTestClass
            );
        }
    }

    private void verifyMainShellLaunchContract() {
        Context targetContext = getTargetContext();
        String packageName = targetContext.getPackageName();
        Intent launchIntent = targetContext.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            throw new AssertionError("No launcher activity for " + packageName);
        }
        ResolveInfo resolvedActivity = targetContext.getPackageManager().resolveActivity(
                launchIntent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (
                resolvedActivity == null
                        || resolvedActivity.activityInfo == null
                        || !packageName.equals(resolvedActivity.activityInfo.packageName)
        ) {
            throw new AssertionError("Launcher does not resolve to " + packageName);
        }
    }

    private void runEmbeddingProbe() {
        Context targetContext = getTargetContext();
        File downloadDir = targetContext.getExternalFilesDir("Download");
        if (downloadDir == null) {
            throw new AssertionError("External model directory is unavailable");
        }
        File modelFile = new File(
                downloadDir,
                "embeddinggemma-300M_seq256_mixed-precision.tflite"
        );
        File tokenizerFile = new File(downloadDir, "sentencepiece.model");
        if (!modelFile.isFile() || !tokenizerFile.isFile()) {
            throw new AssertionError("Verified embedding model files are missing");
        }

        EmbeddingRuntime runtime =
                TfliteTextEmbeddingRuntimeFactory.INSTANCE.create(
                        targetContext,
                        modelFile.getAbsolutePath()
                );
        if (runtime == null) {
            throw new AssertionError("Embedding runtime factory returned null");
        }
        try {
            float[] vector = runtime.embed("Solin release semantic memory probe");
            if (vector.length != TfliteTextEmbeddingRuntimeFactory.EMBEDDING_DIMENSION) {
                throw new AssertionError("Unexpected embedding dimension: " + vector.length);
            }
            double squaredNorm = 0.0;
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new AssertionError("Embedding vector contains a non-finite value");
                }
                squaredNorm += value * value;
            }
            double norm = Math.sqrt(squaredNorm);
            if (norm < 0.95 || norm > 1.05) {
                throw new AssertionError("Embedding vector norm is invalid: " + norm);
            }
        } finally {
            runtime.close();
        }
    }

    private Bundle testStatus(String stream) {
        Bundle status = new Bundle();
        status.putString("id", "ReleaseSmokeInstrumentation");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        status.putString("class", requestedTestClass);
        status.putString(
                "test",
                EMBEDDING_TEST_CLASS.equals(requestedTestClass)
                        ? "verifiedEmbeddingModelProducesSemanticVector"
                        : "installedAppLaunchesMainShell"
        );
        status.putString(REPORT_KEY_STREAMRESULT, stream);
        return status;
    }

    private String elapsedSeconds(long startedAt) {
        return String.format(
                Locale.US,
                "%.3f",
                (SystemClock.elapsedRealtime() - startedAt) / 1_000.0
        );
    }
}
