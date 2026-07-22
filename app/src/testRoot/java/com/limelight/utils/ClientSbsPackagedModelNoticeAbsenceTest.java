package com.limelight.utils;

import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;

@Config(sdk = 33)
@RunWith(RobolectricTestRunner.class)
public class ClientSbsPackagedModelNoticeAbsenceTest {
    @Test
    public void rootFlavorDoesNotPackageClientSbsModelNotices() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String[] noticeAssets = {
                "third_party/client_sbs_models/NOTICE.txt",
                "third_party/client_sbs_models/LICENSE-APACHE-2.0.txt",
                "third_party/client_sbs_models/LICENSE-MIDAS-MIT.txt",
        };

        for (String assetName : noticeAssets) {
            try (InputStream ignored = context.getAssets().open(assetName)) {
                fail("Root flavor unexpectedly packages " + assetName);
            } catch (IOException expected) {
                // The legal notices follow the same non-root-only source-set boundary as models.
            }
        }
    }
}
