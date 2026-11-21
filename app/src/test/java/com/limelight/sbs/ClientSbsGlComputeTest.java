package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import android.opengl.GLES20;
import android.opengl.GLES31;

import org.junit.Test;
import org.mockito.MockedStatic;

public final class ClientSbsGlComputeTest {
    private static final int SHADER = 7;
    private static final int PROGRAM = 9;

    @Test
    public void compiledAndLinkedProgramIsReturned() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            stubCompile(gl, true);
            gl.when(GLES20::glCreateProgram).thenReturn(PROGRAM);
            stubStatus(gl, true, GLES20.GL_LINK_STATUS);

            assertEquals(PROGRAM, ClientSbsGlCompute.createComputeProgram("pack", "source"));
            gl.verify(() -> GLES20.glDeleteShader(SHADER));
            gl.verify(() -> GLES20.glDeleteProgram(anyInt()), never());
        }
    }

    @Test
    public void failedProgramCreationIsRejectedInsteadOfReturningNameZero() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            stubCompile(gl, true);
            gl.when(GLES20::glCreateProgram).thenReturn(0);

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> ClientSbsGlCompute.createComputeProgram("pack", "source"));
            assertTrue(error.getMessage().startsWith("pack"));
            gl.verify(() -> GLES20.glDeleteShader(SHADER));
            gl.verify(() -> GLES20.glAttachShader(anyInt(), anyInt()), never());
        }
    }

    @Test
    public void compileAndLinkFailuresReleaseTheirObjects() {
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            stubCompile(gl, false);
            assertThrows(IllegalStateException.class,
                    () -> ClientSbsGlCompute.createComputeProgram("pack", "source"));
            gl.verify(() -> GLES20.glDeleteShader(SHADER));
            gl.verify(GLES20::glCreateProgram, never());
        }
        try (MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            stubCompile(gl, true);
            gl.when(GLES20::glCreateProgram).thenReturn(PROGRAM);
            stubStatus(gl, false, GLES20.GL_LINK_STATUS);
            assertThrows(IllegalStateException.class,
                    () -> ClientSbsGlCompute.createComputeProgram("pack", "source"));
            gl.verify(() -> GLES20.glDeleteProgram(PROGRAM));
        }
    }

    private static void stubCompile(MockedStatic<GLES20> gl, boolean compiled) {
        gl.when(() -> GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)).thenReturn(SHADER);
        gl.when(() -> GLES20.glGetShaderiv(eq(SHADER), eq(GLES20.GL_COMPILE_STATUS), any(),
                anyInt())).thenAnswer(call -> {
                    ((int[]) call.getArgument(2))[call.<Integer>getArgument(3)] = compiled ? 1 : 0;
                    return null;
                });
    }

    private static void stubStatus(MockedStatic<GLES20> gl, boolean linked, int name) {
        gl.when(() -> GLES20.glGetProgramiv(eq(PROGRAM), eq(name), any(), anyInt()))
                .thenAnswer(call -> {
                    ((int[]) call.getArgument(2))[call.<Integer>getArgument(3)] = linked ? 1 : 0;
                    return null;
                });
    }
}
