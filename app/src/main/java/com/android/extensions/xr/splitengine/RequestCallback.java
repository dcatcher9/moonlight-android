package com.android.extensions.xr.splitengine;

/**
 * Stub interface to prevent R8 from leaving onResult() abstract during lambda desugaring.
 * The actual interface is provided by the Android XR system image at runtime.
 */
public interface RequestCallback {
    void onResult(byte[] result);
}
