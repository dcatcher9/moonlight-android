package com.limelight.binding.input.capture;

import android.app.Activity;

import com.limelight.LimeLog;
import com.limelight.R;

public class InputCaptureManager {
    public static InputCaptureProvider getInputCaptureProvider(Activity activity) {
        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using Android O+ native mouse capture");
            return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.streamContainer));
        }
        else if (ShieldCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using NVIDIA mouse capture extension");
            return new ShieldCaptureProvider(activity);
        }
        else {
            // Android N's native capture can't capture over system UI elements
            // so we want to only use it if there's no other option. minSdk 24 always has it.
            LimeLog.info("Using Android N+ pointer hiding");
            return new AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.streamContainer));
        }
    }
}
