package com.limelight.ui;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.xr.scenecore.SurfaceEntity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the video-layer preference request without treating SDK readback as presentation proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public class XrVideoBlendingTest {
    @Test
    public void cachedPreferenceIsNeverInterpretedAsNativeAcceptance() {
        for (SurfaceEntity.MediaBlendingMode cachedMode : new SurfaceEntity.MediaBlendingMode[] {
                SurfaceEntity.MediaBlendingMode.OPAQUE,
                SurfaceEntity.MediaBlendingMode.TRANSPARENT,
                null
        }) {
            SurfaceEntity target = mock(SurfaceEntity.class);
            when(target.getMediaBlendingMode()).thenReturn(cachedMode);

            // API level 3 can return normally and cache OPAQUE while rejecting native blending.
            XrStreamPresenter.requestOpaqueVideoBlending(target);

            verify(target).setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
            verify(target, never()).getMediaBlendingMode();
            verify(target, never()).setAlpha(anyFloat());
        }
    }

    @Test
    public void unsupportedSdkSetterDoesNotAbortStreamingSetupOrExposeTransitionFrame() {
        for (Throwable failure : new Throwable[] {
                new IllegalStateException("runtime rejected blending mode"),
                new UnsatisfiedLinkError("runtime entry point unavailable")
        }) {
            SurfaceEntity setterFailure = mock(SurfaceEntity.class);
            doThrow(failure).when(setterFailure)
                    .setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
            XrStreamPresenter.requestOpaqueVideoBlending(setterFailure);
            verify(setterFailure, never()).getMediaBlendingMode();
            verify(setterFailure, never()).setAlpha(anyFloat());
        }
    }
}
