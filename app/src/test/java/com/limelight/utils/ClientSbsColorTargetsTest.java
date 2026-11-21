package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.opengl.GLES20;
import android.opengl.GLES30;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientSbsColorTargetsTest {
    private final AtomicInteger nextName = new AtomicInteger(10);
    private final List<Integer> allocatedFormats = new ArrayList<>();

    @Test
    public void hdrWindowFallsBackFromTenBitToHalfFloatInOrder() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class);
             MockedStatic<GLES30> gl30 = mockStatic(GLES30.class)) {
            stubNames(gl, gl30, "GL_EXT_color_buffer_half_float");
            // Both RGB10_A2 slots are incomplete; both RGBA16F slots complete.
            gl.when(() -> GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)).thenReturn(
                    0, 0, GLES20.GL_FRAMEBUFFER_COMPLETE, GLES20.GL_FRAMEBUFFER_COMPLETE);

            ClientSbsColorTargets targets = new ClientSbsColorTargets();
            assertTrue(targets.allocate(3840, 2160, true));

            assertEquals(ClientSbsColorTargets.Format.RGBA16F, targets.format());
            assertEquals(Arrays.asList(GLES30.GL_RGB10_A2, GLES30.GL_RGB10_A2,
                    GLES30.GL_RGBA16F, GLES30.GL_RGBA16F), allocatedFormats);
            // The failed RGB10_A2 attempt was deleted before the next format was tried.
            gl.verify(() -> GLES20.glDeleteFramebuffers(eq(2), any(int[].class), eq(0)));
            gl.verify(() -> GLES20.glDeleteTextures(eq(2), any(int[].class), eq(0)));
            assertTrue(targets.matches(3840, 2160));
            assertFalse(targets.matches(1920, 1080));
            assertEquals(3840, targets.width());
            assertEquals(2160, targets.height());
            assertTrue(targets.texture(1) != 0 && targets.framebuffer(1) != 0);
        }
    }

    @Test
    public void sdrWindowAllocatesOnlyRgba8() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class);
             MockedStatic<GLES30> gl30 = mockStatic(GLES30.class)) {
            stubNames(gl, gl30, "GL_EXT_color_buffer_float");
            gl.when(() -> GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
                    .thenReturn(GLES20.GL_FRAMEBUFFER_COMPLETE);

            ClientSbsColorTargets targets = new ClientSbsColorTargets();
            assertTrue(targets.allocate(0, 0, false));

            assertEquals(ClientSbsColorTargets.Format.RGBA8, targets.format());
            assertEquals(Arrays.asList(GLES30.GL_RGBA8, GLES30.GL_RGBA8), allocatedFormats);
            assertTrue("Allocation is at least 1x1", targets.matches(1, 1));
            gl.verify(() -> GLES20.glDeleteTextures(anyInt(), any(int[].class), anyInt()),
                    never());
        }
    }

    @Test
    public void exhaustedFormatsReportFailureAtRgba8AndLeaveNoNames() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class);
             MockedStatic<GLES30> gl30 = mockStatic(GLES30.class)) {
            stubNames(gl, gl30, null);
            gl.when(() -> GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)).thenReturn(0);

            ClientSbsColorTargets targets = new ClientSbsColorTargets();
            assertFalse(targets.allocate(1920, 1080, true));

            // Without a half-float extension the HDR window tries RGB10_A2 and then RGBA8.
            assertEquals(Arrays.asList(GLES30.GL_RGB10_A2, GLES30.GL_RGB10_A2,
                    GLES30.GL_RGBA8, GLES30.GL_RGBA8), allocatedFormats);
            assertEquals(ClientSbsColorTargets.Format.RGBA8, targets.format());
            assertFalse(targets.matches(1920, 1080));
            gl.verify(() -> GLES20.glDeleteTextures(eq(2), any(int[].class), eq(0)), times(2));
        }
    }

    @Test
    public void contextLossForgetsNamesWithoutGlCalls() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class);
             MockedStatic<GLES30> gl30 = mockStatic(GLES30.class)) {
            stubNames(gl, gl30, "GL_EXT_color_buffer_half_float");
            gl.when(() -> GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
                    .thenReturn(GLES20.GL_FRAMEBUFFER_COMPLETE);
            ClientSbsColorTargets targets = new ClientSbsColorTargets();
            assertTrue(targets.allocate(1920, 1080, true));
            assertEquals(ClientSbsColorTargets.Format.RGB10_A2, targets.format());

            gl.clearInvocations();
            targets.forgetAfterContextLoss();
            gl.verifyNoInteractions();
            assertEquals(0, targets.texture(0));
            assertEquals(ClientSbsColorTargets.Format.RGBA8, targets.format());
            assertFalse(targets.matches(1920, 1080));
        }
    }

    private void stubNames(MockedStatic<GLES20> gl, MockedStatic<GLES30> gl30,
                           String extensions) {
        gl.when(() -> GLES20.glGetString(GLES20.GL_EXTENSIONS)).thenReturn(extensions);
        gl.when(() -> GLES20.glGenFramebuffers(anyInt(), any(int[].class), anyInt()))
                .thenAnswer(call -> fillNames(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2)));
        gl.when(() -> GLES20.glGenTextures(anyInt(), any(int[].class), anyInt()))
                .thenAnswer(call -> fillNames(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2)));
        gl30.when(() -> GLES30.glTexImage2D(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                    allocatedFormats.add(call.getArgument(2));
                    return null;
                });
    }

    private Object fillNames(int count, int[] names, int offset) {
        for (int index = 0; index < count; index++) {
            names[offset + index] = nextName.getAndIncrement();
        }
        return null;
    }
}
