package com.android.extensions.xr.function;

/**
 * Stub interface to prevent R8 from leaving accept() abstract during lambda desugaring.
 * The actual interface is provided by the Android XR system image at runtime.
 */
public interface Consumer<T> {
    void accept(T t);
}
