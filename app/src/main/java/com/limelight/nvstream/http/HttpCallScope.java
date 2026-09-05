package com.limelight.nvstream.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Owns a connection attempt's HTTP calls until their response bodies are closed. */
public final class HttpCallScope {
    private final Set<Call> calls = new HashSet<>();
    private boolean cancelled;

    synchronized void register(Call call) throws InterruptedIOException {
        if (cancelled) {
            call.cancel();
            throw new InterruptedIOException("Connection attempt cancelled");
        }
        calls.add(call);
    }

    synchronized void finished(Call call) {
        calls.remove(call);
    }

    Response execute(Call call) throws IOException {
        register(call);
        try {
            Response response = NvHTTP.executeCall(call);
            ResponseBody body = response.body();
            if (body == null) {
                finished(call);
                return response;
            }
            // execute() returns at headers. Keep cancellation attached while an established
            // socket is stalled in XML/body reading too, until the caller closes the response.
            BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        finished(call);
                    }
                }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() { return body.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        } catch (IOException | RuntimeException error) {
            finished(call);
            throw error;
        }
    }

    /** Terminal for this attempt, including calls created concurrently with stop. */
    public void cancel() {
        Call[] pending;
        synchronized (this) {
            cancelled = true;
            pending = calls.toArray(new Call[0]);
        }
        for (Call call : pending) {
            call.cancel();
        }
    }
}
