package com.spd.mod;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.watabou.utils.Bundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModUpdates {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/edward9s/Shattered-Master-Mode/releases";
    private static final long CHECK_DELAY = 1000L * 60L * 60L;
    private static final Pattern MOD_VERSION = Pattern.compile("-m([0-9]+(?:\\.[0-9]+)*)$");

    private static long lastCheck = 0L;
    private static boolean checking = false;
    private static String latestVersion;

    private ModUpdates() {
    }

    public static synchronized void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (checking || (lastCheck != 0L && now - lastCheck < CHECK_DELAY)) {
            return;
        }
        checking = true;

        try {
            Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
            request.setUrl(RELEASES_URL);
            request.setHeader("Accept", "application/vnd.github.v3+json");

            Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
                @Override
                public void handleHttpResponse(Net.HttpResponse response) {
                    try {
                        String bestVersion = null;

                        for (Bundle release : Bundle.read(response.getResultAsStream()).getBundleArray()) {
                            if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
                                continue;
                            }

                            String tag = release.getString("tag_name");
                            Matcher matcher = MOD_VERSION.matcher(tag == null ? "" : tag);
                            if (!matcher.find()) {
                                continue;
                            }

                            String version = matcher.group(1);
                            if (bestVersion == null || compareVersions(version, bestVersion) > 0) {
                                bestVersion = version;
                            }
                        }

                        synchronized (ModUpdates.class) {
                            if (bestVersion != null && compareVersions(bestVersion, ModGame.version()) > 0) {
                                latestVersion = bestVersion;
                            } else {
                                latestVersion = null;
                            }
                            lastCheck = System.currentTimeMillis();
                            checking = false;
                        }
                    } catch (Exception e) {
                        finishFailedCheck();
                    }
                }

                @Override
                public void failed(Throwable t) {
                    finishFailedCheck();
                }

                @Override
                public void cancelled() {
                    finishFailedCheck();
                }
            });
        } catch (Throwable t) {
            finishFailedCheck();
        }
    }

    private static synchronized void finishFailedCheck() {
        checking = false;
        // Leave lastCheck unset so a later menu opening can retry.
    }

    public static synchronized boolean updateAvailable() {
        return latestVersion != null;
    }

    public static synchronized String latestVersion() {
        return latestVersion;
    }

    static int compareVersions(String a, String b) {
        String[] left = a == null ? new String[0] : a.split("\\.");
        String[] right = b == null ? new String[0] : b.split("\\.");
        int count = Math.max(left.length, right.length);

        for (int i = 0; i < count; i++) {
            int l = i < left.length ? parsePart(left[i]) : 0;
            int r = i < right.length ? parsePart(right[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (Exception e) {
            return 0;
        }
    }
}
