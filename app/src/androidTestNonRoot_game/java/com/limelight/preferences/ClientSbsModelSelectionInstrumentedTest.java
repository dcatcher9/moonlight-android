package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit-only physical-device helper for controlled Client-SBS model A/B runs. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsModelSelectionInstrumentedTest {
    private static final String TAG = "ClientSbsModelSelect";
    private static final String ARGUMENT = "clientSbsModel";

    @Test
    public void selectRequestedModelForNextStream() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String requestedModel = arguments.getString(ARGUMENT);
        // A broad instrumentation run must never change the user's selected model accidentally.
        Assume.assumeTrue("Pass -e " + ARGUMENT + " <model-id> to mutate this one setting",
                requestedModel != null);
        assertTrue("Unsupported Client SBS model: " + requestedModel,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC.equals(requestedModel)
                        || PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2.equals(
                        requestedModel));

        Context targetContext =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(targetContext);
        assertTrue("Unable to commit Client SBS model preference",
                preferences.edit().putString(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        requestedModel).commit());

        PreferenceConfiguration effective =
                PreferenceConfiguration.readPreferences(targetContext);
        assertEquals(requestedModel, effective.clientSbsDepthModelId);
        Log.i(TAG, "selected=" + requestedModel + " storage=global-defaults");
    }
}
