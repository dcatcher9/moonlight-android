package android.extensions.xr.splitengine;

/**
 * Stub interface to prevent R8 from leaving onMessageGroupComplete() abstract during lambda desugaring.
 * The actual interface is provided by the Android XR system image at runtime.
 */
public interface MessageGroupCallback {
    void onMessageGroupComplete(int groupId);
}
