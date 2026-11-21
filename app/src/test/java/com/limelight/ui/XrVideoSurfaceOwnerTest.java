package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.view.Display;
import android.view.Surface;
import android.view.Window;

import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.SurfaceEntity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Every resize adopts SceneCore's published Surface and re-votes the durable rate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrVideoSurfaceOwnerTest {
    private SurfaceEntity entity;
    private int voteHz = 90;
    private final Window window =
            Robolectric.buildActivity(Activity.class).setup().get().getWindow();

    private XrVideoSurfaceOwner owner() {
        return new XrVideoSurfaceOwner(new XrVideoSurfaceOwner.Host() {
            @Override public SurfaceEntity videoEntity() {
                return entity;
            }

            @Override public Window window() {
                return window;
            }

            @Override public Display activeDisplay() {
                return null;
            }

            @Override public boolean preferClientPanelRate() {
                return false;
            }

            @Override public int surfaceFrameRateVoteHz() {
                return voteHz;
            }
        });
    }

    @Test
    public void resizeAdoptsTheReplacementSurfaceAndVotesTheDurableRate() {
        entity = mock(SurfaceEntity.class);
        Surface replacement = mock(Surface.class);
        when(replacement.isValid()).thenReturn(true);
        when(entity.getSurface()).thenReturn(replacement);
        XrVideoSurfaceOwner owner = owner();

        assertTrue(owner.resize(3840, 1080));

        verify(entity).setSurfacePixelDimensions(new IntSize2d(3840, 1080));
        assertSame(replacement, owner.surface());
        verify(replacement).setFrameRate(90f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS);
        owner.clear();
        assertNull(owner.surface());
    }

    @Test
    public void disposedEntityIsNotResized() {
        entity = mock(SurfaceEntity.class);
        when(entity.isDisposed()).thenReturn(true);

        assertFalse(owner().resize(1920, 1080));
        verify(entity, never()).setSurfacePixelDimensions(any());
    }

    @Test
    public void invalidSurfaceIsAdoptedWithoutAVote() {
        entity = mock(SurfaceEntity.class);
        Surface retiring = mock(Surface.class);
        when(entity.getSurface()).thenReturn(retiring);
        XrVideoSurfaceOwner owner = owner();

        assertFalse(owner.resize(1920, 1080));
        assertSame(retiring, owner.surface());
        verify(retiring, never()).setFrameRate(anyFloat(), anyInt(), anyInt());
    }
}
