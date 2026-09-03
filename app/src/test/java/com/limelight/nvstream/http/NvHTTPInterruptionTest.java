package com.limelight.nvstream.http;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.io.InterruptedIOException;

import okhttp3.Call;

public class NvHTTPInterruptionTest {
    @Test
    public void sneakyInterruptedExceptionBecomesInterruptedIOException() throws Exception {
        InterruptedException cause = new InterruptedException("cancelled connect");
        Call call = mock(Call.class);
        when(call.execute()).thenAnswer(invocation -> {
            throw cause;
        });

        try {
            NvHTTP.executeCall(call);
            fail("Interrupted request must fail");
        } catch (InterruptedIOException expected) {
            assertSame(cause, expected.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Avoid leaking the deliberately restored interrupt status into the test worker.
            Thread.interrupted();
        }
    }
}
