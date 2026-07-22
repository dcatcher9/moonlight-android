package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class PreferenceConfigurationClientSbsModelMigrationTest {
    private static final String MODEL_KEY = "list_client_sbs_depth_model";
    private static final String LEGACY_MODEL = "depth-anything-v2-small-static-buckets";

    private Context context;
    private ProfilesManager profiles;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear().commit());
        deleteRecursively(new File(context.getFilesDir(), "profiles"));

        java.lang.reflect.Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        profiles = ProfilesManager.getInstance();
        assertTrue(profiles.load(context));
    }

    @Test
    public void legacyModelInActiveProfileIsMigratedInThatProfile() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(MODEL_KEY, LEGACY_MODEL);
        SettingsProfile profile = new SettingsProfile(
                UUID.randomUUID(), "XR", 1L, 1L, options);
        profiles.add(profile);
        profiles.setActive(profile.getUuid());

        PreferenceConfiguration effective = PreferenceConfiguration.readPreferences(context);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                effective.clientSbsDepthModelId);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                profiles.getActive().getOptions().get(MODEL_KEY));

        // Reload the serialized profile to prove the migration was not only an in-memory overlay.
        assertTrue(profiles.load(context));
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                profiles.getActive().getOptions().get(MODEL_KEY));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
