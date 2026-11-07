package com.prajwal.cardashboard;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** CarLinkInstaller's checks: which release files are taken, checksum files, and the signing-key pin. */
public class CarLinkInstallerTest {
    private static final String RELEASE = "https://github.com/F-e-n-y-x/CarLink/releases/download/v1.0.13/";
    private static final String DIGEST = "ae61614a44ffcb7b4d2154ab50f57f6dc0cdc477ea5419bece534b59def4334b";

    private static CarLinkInstaller.Asset asset(String name, String url) {
        return new CarLinkInstaller.Asset(name, url, 16585464);
    }

    @Test public void picksTheApkWithItsChecksum() {
        CarLinkInstaller.Release release = CarLinkInstaller.pickRelease("v1.0.13", Arrays.asList(
                asset("CarLink-1.0.13.apk.sha256", RELEASE + "CarLink-1.0.13.apk.sha256"),
                asset("CarLink-1.0.13.apk", RELEASE + "CarLink-1.0.13.apk")));
        assertNotNull(release);
        assertEquals("1.0.13", release.version);
        assertEquals("CarLink-1.0.13.apk", release.apk.name);
        assertEquals("CarLink-1.0.13.apk.sha256", release.checksum.name);
    }
    @Test public void noChecksumNoRelease() {
        assertNull(CarLinkInstaller.pickRelease("v1.0.13", Collections.singletonList(
                asset("CarLink-1.0.13.apk", RELEASE + "CarLink-1.0.13.apk"))));
    }
    @Test public void filesFromElsewhereAreRefused() {
        List<CarLinkInstaller.Asset> assets = Arrays.asList(
                asset("CarLink-1.0.13.apk", "https://example.com/CarLink-1.0.13.apk"),
                asset("CarLink-1.0.13.apk.sha256", RELEASE + "CarLink-1.0.13.apk.sha256"));
        assertNull(CarLinkInstaller.pickRelease("v1.0.13", assets));
    }
    @Test public void trustedDownloadsAreCarLinksOwnReleases() {
        assertTrue(CarLinkInstaller.isTrustedDownload(RELEASE + "CarLink-1.0.13.apk"));
        assertFalse(CarLinkInstaller.isTrustedDownload("https://github.com/someone/CarLink/releases/download/v1/x.apk"));
        assertFalse(CarLinkInstaller.isTrustedDownload("http://github.com/F-e-n-y-x/CarLink/releases/download/v1/x.apk"));
        assertFalse(CarLinkInstaller.isTrustedDownload(RELEASE + "../../../other/x.apk"));
        assertFalse(CarLinkInstaller.isTrustedDownload(null));
    }
    @Test public void readsSha256sumLines() {
        assertEquals(DIGEST, CarLinkInstaller.parseChecksum(DIGEST + "  CarLink-1.0.13.apk\n"));
        assertEquals(DIGEST, CarLinkInstaller.parseChecksum(DIGEST.toUpperCase()));
        assertNull(CarLinkInstaller.parseChecksum("not a checksum"));
        assertNull(CarLinkInstaller.parseChecksum(""));
        assertNull(CarLinkInstaller.parseChecksum(null));
    }
    @Test public void otherSignersAreRefused() {
        assertFalse(CarLinkInstaller.isCarLinkSigner(Collections.singletonList(new byte[] {1, 2, 3})));
        assertFalse(CarLinkInstaller.isCarLinkSigner(Arrays.asList(new byte[] {1}, new byte[] {2})));
        assertFalse(CarLinkInstaller.isCarLinkSigner(Collections.emptyList()));
        assertFalse(CarLinkInstaller.isCarLinkSigner(null));
    }
    @Test public void hexIsLowerCaseTwoDigitsPerByte() {
        assertEquals("00ff10", CarLinkInstaller.formatHex(new byte[] {0, (byte) 0xff, 0x10}));
    }
}
