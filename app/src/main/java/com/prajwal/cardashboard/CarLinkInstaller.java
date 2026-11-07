package com.prajwal.cardashboard;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Installs the latest CarLink for AutoPlay without a browser: finds CarLink's latest GitHub release, downloads its
 * APK, checks it, and hands it to Android's installer, which asks the user to confirm (no app can install silently).
 *
 * The checks, any failing one deletes the download and stops (nothing is installed):
 * - the APK's SHA-256 equals the release's own .sha256 file;
 * - the APK is CarLink's package, signed with CarLink's release key (the certificate pinned below, as CarLink's
 *   README publishes it). This one is what makes a swapped download fail: the checksum comes from the same release.
 * Runs off the main thread (AutoPlay's install dialog); Android's answer arrives in CarLinkInstallReceiver.
 */
final class CarLinkInstaller {
    private static final String TAG = "CarDash.CarLinkInstall";
    static final String LATEST_RELEASE_API = "https://api.github.com/repos/F-e-n-y-x/CarLink/releases/latest";
    /** Release files are only taken from CarLink's own releases (GitHub then redirects to its file host). */
    static final String DOWNLOAD_PREFIX = "https://github.com/F-e-n-y-x/CarLink/releases/download/";
    /** CarLink's release signing certificate, SHA-256, from CarLink's README (Install). */
    static final String CARLINK_CERTIFICATE_SHA256 =
            "037468ca5beb70ea5f68543329b03368247dfb393d3f626a6b472d10e5657d21";
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static final int TIMEOUT_SECONDS = 30;
    private static final String USER_AGENT = "CarDashboard (com.prajwal.cardashboard)";
    private static OkHttpClient client;

    /** A release file: name, download address, size in bytes. */
    static final class Asset {
        final String name;
        final String url;
        final long size;

        Asset(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    /** The release to install: its version, the APK, and the APK's .sha256 file. */
    static final class Release {
        final String version;
        final Asset apk;
        final Asset checksum;

        Release(String version, Asset apk, Asset checksum) {
            this.version = version;
            this.apk = apk;
            this.checksum = checksum;
        }
    }

    interface Progress {
        /** Bytes downloaded so far of {@code total}; from the download thread. */
        void onDownloaded(long done, long total);
    }

    private CarLinkInstaller() {
    }

    /** CarLink's latest release from GitHub's API. Network: call off the main thread. */
    static Release findLatestRelease() throws IOException {
        String answer = fetchText(LATEST_RELEASE_API, 1024 * 1024);
        try {
            JSONObject release = new JSONObject(answer);
            JSONArray files = release.getJSONArray("assets");
            List<Asset> assets = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                assets.add(new Asset(file.getString("name"), file.getString("browser_download_url"),
                        file.optLong("size", -1)));
            }
            Release picked = pickRelease(release.optString("tag_name"), assets);
            if (picked == null) throw new IOException("CarLink's latest release has no APK with a checksum");
            return picked;
        } catch (JSONException e) {
            throw new IOException("GitHub's answer about CarLink was unreadable", e);
        }
    }

    /**
     * The APK of a release and its checksum file (the APK's name + ".sha256"), both from CarLink's own release
     * downloads; null when either is missing or comes from elsewhere. The version is the tag without its "v".
     */
    static Release pickRelease(String tag, List<Asset> assets) {
        for (Asset apk : assets) {
            if (!apk.name.endsWith(".apk") || !isTrustedDownload(apk.url)) continue;
            if (apk.size > MAX_APK_BYTES) continue;
            for (Asset checksum : assets) {
                if (checksum.name.equals(apk.name + ".sha256") && isTrustedDownload(checksum.url)) {
                    String version = tag != null && tag.startsWith("v") ? tag.substring(1) : tag;
                    return new Release(version, apk, checksum);
                }
            }
        }
        return null;
    }

    static boolean isTrustedDownload(String url) {
        return url != null && url.startsWith(DOWNLOAD_PREFIX) && !url.contains("..");
    }

    /** The SHA-256 in a sha256sum line ("<64 hex>  <file>"), lower case; null when there is none. */
    static String parseChecksum(String sha256File) {
        if (sha256File == null) return null;
        String[] words = sha256File.trim().split("\\s+");
        if (words.length == 0) return null;
        String digest = words[0].toLowerCase(Locale.ROOT);
        return digest.matches("[0-9a-f]{64}") ? digest : null;
    }

    /** True only when the APK has exactly one signer and it is CarLink's release certificate. */
    static boolean isCarLinkSigner(List<byte[]> signerCertificates) {
        if (signerCertificates == null || signerCertificates.size() != 1) return false;
        return CARLINK_CERTIFICATE_SHA256.equals(formatHex(sha256(signerCertificates.get(0))));
    }

    /**
     * Downloads the release's APK into the app's cache and checks it (see the class comment). Network: call off the
     * main thread. The checked file is returned; on any failure the download is deleted and the reason thrown.
     */
    static File downloadVerified(Context context, Release release, Progress progress) throws IOException {
        String expected = parseChecksum(fetchText(release.checksum.url, 4096));
        if (expected == null) throw new IOException("CarLink's checksum file is unreadable");
        File apk = new File(context.getCacheDir(), "carlink.apk");
        boolean verified = false;
        try {
            String actual = download(release.apk.url, apk, progress);
            if (!expected.equals(actual)) throw new IOException("The download doesn't match CarLink's checksum");
            checkPackage(context, apk);
            verified = true;
            return apk;
        } finally {
            if (!verified && apk.exists() && !apk.delete()) Log.w(TAG, "could not delete " + apk);
        }
    }

    /**
     * Hands the checked APK to Android's installer, which asks the user (and, the first time, to allow installs from
     * this app). The answer comes to CarLinkInstallReceiver. The APK file is deleted once handed over.
     */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(AutoPlay.CARLINK_PACKAGE);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("carlink.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            Intent answer = new Intent(context, CarLinkInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);   // the installer adds its answer
            session.commit(PendingIntent.getBroadcast(context, sessionId, answer, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        } finally {
            if (!apk.delete()) Log.w(TAG, "could not delete " + apk);
        }
    }

    /** The downloaded file is CarLink, signed by CarLink's release key, or an IOException says why not. */
    private static void checkPackage(Context context, File apk) throws IOException {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = Build.VERSION.SDK_INT >= 28
                ? pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES)
                : null;
        List<byte[]> signers = archive != null ? listSigners(archive) : null;
        if (signers == null) {
            archive = pm.getPackageArchiveInfo(apk.getPath(), readSignaturesFlag());
            signers = archive != null ? listOldSignatures(archive) : null;
        }
        if (archive == null) throw new IOException("The download isn't an app Android can read");
        if (!AutoPlay.CARLINK_PACKAGE.equals(archive.packageName)) {
            throw new IOException("The download is " + archive.packageName + ", not CarLink");
        }
        if (!isCarLinkSigner(signers)) throw new IOException("The download isn't signed with CarLink's release key");
    }

    private static List<byte[]> listSigners(PackageInfo archive) {
        if (Build.VERSION.SDK_INT < 28) return null;
        SigningInfo signing = archive.signingInfo;
        if (signing == null) return null;
        Signature[] signatures = signing.hasMultipleSigners()
                ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
        return toBytes(signatures);
    }

    @SuppressWarnings("deprecation")
    private static int readSignaturesFlag() {
        return PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static List<byte[]> listOldSignatures(PackageInfo archive) {
        return toBytes(archive.signatures);
    }

    private static List<byte[]> toBytes(Signature[] signatures) {
        if (signatures == null) return null;
        List<byte[]> certificates = new ArrayList<>();
        for (Signature signature : signatures) certificates.add(signature.toByteArray());
        return certificates;
    }

    /** Downloads {@code url} into {@code target} (at most MAX_APK_BYTES), returning the file's SHA-256 in hex. */
    private static String download(String url, File target, Progress progress) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = lookupClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Download failed (HTTP " + response.code() + ")");
            }
            long total = body.contentLength();
            if (total > MAX_APK_BYTES) throw new IOException("The download is too large");
            MessageDigest digest = createSha256();
            long done = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(target)) {
                for (int read; (read = in.read(buffer)) != -1; ) {
                    done += read;
                    if (done > MAX_APK_BYTES) throw new IOException("The download is too large");
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    progress.onDownloaded(done, total);
                }
            }
            return formatHex(digest.digest());
        }
    }

    private static String fetchText(String url, int maxBytes) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json").build();
        try (Response response = lookupClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("GitHub answered HTTP " + response.code());
            }
            if (body.contentLength() > maxBytes) throw new IOException("GitHub's answer is too large");
            return body.string();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
    }

    private static byte[] sha256(byte[] bytes) {
        return createSha256().digest(bytes);
    }

    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    static String formatHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static synchronized OkHttpClient lookupClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }
}
