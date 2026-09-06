package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.xr.scenecore.SurfaceEntity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the video-layer preference request without treating SDK readback as presentation proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public class XrVideoBlendingTest {
    @Test
    public void acceptedRequestWritesOpaqueBeforeReadingTheCachedPreference() {
        SurfaceEntity target = mock(SurfaceEntity.class);
        when(target.getMediaBlendingMode()).thenReturn(SurfaceEntity.MediaBlendingMode.OPAQUE);

        assertTrue(XrStreamPresenter.requestOpaqueVideoBlending(target));

        InOrder order = inOrder(target);
        order.verify(target).setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
        order.verify(target).getMediaBlendingMode();
        verify(target, never()).setAlpha(anyFloat());
    }

    @Test
    public void unsupportedSdkSetterOrReadbackDoesNotAbortStreamingSetup() {
        for (Throwable failure : new Throwable[] {
                new IllegalStateException("runtime rejected blending mode"),
                new UnsatisfiedLinkError("runtime entry point unavailable")
        }) {
            SurfaceEntity setterFailure = mock(SurfaceEntity.class);
            doThrow(failure).when(setterFailure)
                    .setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
            assertFalse(XrStreamPresenter.requestOpaqueVideoBlending(setterFailure));
            verify(setterFailure, never()).getMediaBlendingMode();
            verify(setterFailure, never()).setAlpha(anyFloat());

            SurfaceEntity readbackFailure = mock(SurfaceEntity.class);
            when(readbackFailure.getMediaBlendingMode()).thenThrow(failure);
            assertFalse(XrStreamPresenter.requestOpaqueVideoBlending(readbackFailure));
            verify(readbackFailure).setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
            verify(readbackFailure, never()).setAlpha(anyFloat());
        }
    }

    @Test
    public void staleOrMissingReadbackIsReportedWithoutForcingEntityAlpha() {
        for (SurfaceEntity.MediaBlendingMode cachedMode : new SurfaceEntity.MediaBlendingMode[] {
                SurfaceEntity.MediaBlendingMode.TRANSPARENT, null
        }) {
            SurfaceEntity target = mock(SurfaceEntity.class);
            when(target.getMediaBlendingMode()).thenReturn(cachedMode);

            assertFalse(XrStreamPresenter.requestOpaqueVideoBlending(target));

            verify(target).setMediaBlendingMode(SurfaceEntity.MediaBlendingMode.OPAQUE);
            verify(target).getMediaBlendingMode();
            verify(target, never()).setAlpha(anyFloat());
        }
    }
}
