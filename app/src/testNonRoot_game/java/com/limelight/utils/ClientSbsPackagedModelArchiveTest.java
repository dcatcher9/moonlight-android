package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class ClientSbsPackagedModelArchiveTest {
    private static final String MODEL_NOTICE_DIRECTORY = "third_party/client_sbs_models/";

    @Test
    public void packagedFamilyArchivesMatchEveryProductionRuntimeContract() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ClientSbsModelManifest[] manifests = {
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9,
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_21_9,
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_32_9,
                ClientSbsModelManifest.MIDAS_V2_STATIC_16_9,
                ClientSbsModelManifest.MIDAS_V2_STATIC_21_9,
                ClientSbsModelManifest.MIDAS_V2_STATIC_32_9,
        };
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void write(byte[] values, int offset, int length) {
            }
        };

        for (ClientSbsModelManifest manifest : manifests) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream archive = context.getAssets().open(
                    manifest.getModelArchiveAssetName(), AssetManager.ACCESS_STREAMING)) {
                ClientSbsGpuInferenceEngine.extractArchivedModel(
                        archive, manifest.getAssetName(), sink, digest);
            }
            assertEquals(manifest.getAssetSha256(), toHex(digest.digest()));
        }
    }

    @Test
    public void packagedModelNoticesAreCompleteAndUnchanged() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String[][] expectedAssets = {
                {"NOTICE.txt", "b944624a6829f97a8461cd9d657b7909a6c1f3cf4a52a50ac22a3aa84bdb2cbd"},
                {"LICENSE-APACHE-2.0.txt", "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"},
                {"LICENSE-MIDAS-MIT.txt", "99ec0b9f9bcc9234b649787b8f03b07dbece95764b7879e1e72fb76cb0f96876"},
        };

        for (String[] expected : expectedAssets) {
            byte[] contents = readAsset(context, MODEL_NOTICE_DIRECTORY + expected[0]);
            byte[] canonicalContents = new String(contents, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
            assertEquals(
                    expected[0],
                    expected[1],
                    toHex(MessageDigest.getInstance("SHA-256").digest(canonicalContents)));
        }

        String notice = new String(
                readAsset(context, MODEL_NOTICE_DIRECTORY + "NOTICE.txt"),
                StandardCharsets.UTF_8);
        assertTrue(notice.contains("Depth Anything V2 Small"));
        assertTrue(notice.contains("MiDaS v2.1 Small"));
        assertTrue(notice.contains("LiteRT 2.2.0 native runtime and accelerator"));
        assertTrue(notice.contains("modified TFLite conversions"));
        assertTrue(notice.contains("does not claim a complete"));
        assertTrue(notice.contains("LICENSE-APACHE-2.0.txt"));
        assertTrue(notice.contains("LICENSE-MIDAS-MIT.txt"));
    }

    private static byte[] readAsset(Context context, String assetName) throws Exception {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xFF));
        }
        return result.toString();
    }
}
