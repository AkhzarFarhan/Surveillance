package com.arthenica.smartexception.java;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Compatibility shim for com.arthenica:smart-exception-java required at runtime by
 * dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7 (whose Maven POM omits this transitive dependency).
 *
 * FFmpegKitConfig.<clinit>, NativeLoader, AbstractSession, and Async*ExecuteTask invoke:
 * - Exceptions.registerRootPackage(String)
 * - Exceptions.getStackTraceString(Throwable)
 */
public final class Exceptions {

    private static final Set<String> ROOT_PACKAGES = Collections.synchronizedSet(new HashSet<>());

    private Exceptions() {
        // Utility class
    }

    public static void registerRootPackage(final String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            ROOT_PACKAGES.add(packageName);
        }
    }

    public static void clearRootPackages() {
        ROOT_PACKAGES.clear();
    }

    public static String getStackTraceString(final Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        try {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            throwable.printStackTrace(pw);
            return sw.toString();
        } catch (final Throwable ignored) {
            return throwable.toString();
        }
    }
}
