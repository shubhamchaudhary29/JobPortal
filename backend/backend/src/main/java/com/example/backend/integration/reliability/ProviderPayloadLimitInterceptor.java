package com.example.backend.integration.reliability;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

final class ProviderPayloadLimitInterceptor implements ClientHttpRequestInterceptor {
    private final int maxBytes;

    ProviderPayloadLimitInterceptor(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        long length = response.getHeaders().getContentLength();
        if (length > maxBytes) {
            response.close();
            throw new PayloadLimitIOException();
        }
        return new LimitedResponse(response, maxBytes);
    }

    static boolean causedByLimit(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PayloadLimitIOException) return true;
        }
        return false;
    }

    private static final class LimitedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final int maxBytes;
        private LimitedResponse(ClientHttpResponse delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }
        @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getBody() throws IOException {
            return new LimitedInputStream(delegate.getBody(), maxBytes);
        }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long read;
        private LimitedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) checked(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) checked(count);
            return count;
        }
        private void checked(int count) throws PayloadLimitIOException {
            read += count;
            if (read > maxBytes) throw new PayloadLimitIOException();
        }
    }

    private static final class PayloadLimitIOException extends IOException {
        private PayloadLimitIOException() { super("provider response exceeded configured byte limit"); }
    }
}
