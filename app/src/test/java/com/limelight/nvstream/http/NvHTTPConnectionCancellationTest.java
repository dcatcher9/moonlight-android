package com.limelight.nvstream.http;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
public class NvHTTPConnectionCancellationTest {
    /** An established connection with response headers and an intentionally unfinished body. */
    private static final class StalledHost implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final CountDownLatch headersSent = new CountDownLatch(1);
        final CountDownLatch close = new CountDownLatch(1);
        final Thread worker;

        StalledHost() throws IOException {
            worker = new Thread(() -> {
                try (Socket peer = server.accept()) {
                    // Consume the request headers so cancellation cannot pass merely by failing
                    // before the request reaches an established server connection.
                    int suffix = 0;
                    while (suffix != 0x0d0a0d0a) {
                        int value = peer.getInputStream().read();
                        if (value < 0) return;
                        suffix = (suffix << 8) | value;
                    }
                    peer.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 100\r\nConnection: close\r\n\r\nx")
                            .getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().flush();
                    headersSent.countDown();
                    close.await();
                } catch (Exception ignored) { }
            }, "stalled HTTP host");
            worker.start();
        }

        Request request() {
            return new Request.Builder().url("http://127.0.0.1:" + server.getLocalPort()
                    + "/launch").build();
        }

        @Override public void close() throws Exception {
            close.countDown();
            server.close();
            worker.join(2000L);
        }
    }

    @Test
    public void stopCancelsEstablishedBodyBeforeJoiningAndReleasesPermitAfterCleanup()
            throws Exception {
        HttpCallScope scope = new HttpCallScope();
        NvConnection connection = new NvConnection(RuntimeEnvironment.getApplication(),
                new ComputerDetails.AddressTuple("127.0.0.1", 47989), 47984, "client",
                new StreamConfiguration.Builder().build(),
                mock(LimelightCryptoProvider.class), null);
        Semaphore permit = ReflectionHelpers.getStaticField(NvConnection.class,
                "connectionAllowed");
        assertTrue(permit.tryAcquire(2, TimeUnit.SECONDS));
        ReflectionHelpers.setField(connection, "connectionPermitHeld", true);
        ReflectionHelpers.setField(connection, "startupHttpCalls", scope);

        try (StalledHost host = new StalledHost()) {
            CountDownLatch bodyReading = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            OkHttpClient http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).build();
            Thread start = new Thread(() -> {
                try (Response response = scope.execute(http.newCall(host.request()))) {
                    bodyReading.countDown();
                    response.body().string();
                    outcome.set(new AssertionError("stalled body unexpectedly completed"));
                } catch (IOException expected) {
                    outcome.set(expected);
                }
            }, "connection start test");
            ReflectionHelpers.setField(connection, "connectionThread", start);
            start.start();
            assertTrue(host.headersSent.await(2, TimeUnit.SECONDS));
            assertTrue(bodyReading.await(2, TimeUnit.SECONDS));
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stop = new Thread(() -> {
                try {
                    connection.stop(held -> {
                        assertTrue(held);
                        assertFalse(start.isAlive());
                        assertEquals(0, permit.availablePermits());
                    });
                } catch (Throwable error) { stopFailure.set(error); }
            });
            stop.start();
            stop.join(2000L);
            assertFalse("stop must cancel the socket before its join", stop.isAlive());
            assertNull(stopFailure.get());
            assertTrue(outcome.get() instanceof IOException);
            assertEquals(1, permit.availablePermits());
            assertThrows(IOException.class, () -> scope.execute(http.newCall(host.request())));
            connection.stop(null);
            assertEquals("repeated stop must not over-release", 1, permit.availablePermits());
        } finally {
            scope.cancel();
            connection.stop(null);
        }
    }

    @Test
    public void sessionDeadlineIncludesBodyConsumption() throws Exception {
        try (StalledHost host = new StalledHost()) {
            HttpCallScope scope = new HttpCallScope();
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.SECONDS)
                    .callTimeout(300, TimeUnit.MILLISECONDS).build();
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> {
                try (Response response = scope.execute(client.newCall(host.request()))) {
                    response.body().string();
                }
            });
            assertTrue(host.headersSent.await(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L);
        }
    }

    @Test
    public void sessionClientHasTotalDeadlineWhilePinPairingRemainsUnbounded() throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1", 47989),
                47984, "client", null, mock(LimelightCryptoProvider.class));
        OkHttpClient session = ReflectionHelpers.getField(http, "httpClientSession");
        OkHttpClient pairing = ReflectionHelpers.getField(http,
                "httpClientLongConnectNoReadTimeout");
        assertEquals(NvHTTP.SESSION_CALL_TIMEOUT, session.callTimeoutMillis());
        assertEquals(0, pairing.callTimeoutMillis());
        assertEquals(0, pairing.readTimeoutMillis());
    }
}
