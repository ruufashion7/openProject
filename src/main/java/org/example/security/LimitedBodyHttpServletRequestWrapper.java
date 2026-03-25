package org.example.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps how many bytes can be read from the body when {@code Content-Length} is unknown (chunked / -1).
 */
public class LimitedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final long maxBodyBytes;
    private ServletInputStream stream;

    public LimitedBodyHttpServletRequestWrapper(HttpServletRequest request, long maxBodyBytes) {
        super(request);
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new LimitedServletInputStream(super.getInputStream(), maxBodyBytes);
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset cs = StandardCharsets.UTF_8;
        String enc = getCharacterEncoding();
        if (enc != null && !enc.isBlank()) {
            try {
                cs = Charset.forName(enc);
            } catch (Exception ignored) {
                // UTF-8 default
            }
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long totalRead;
        private IOException limitException;

        LimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int read() throws IOException {
            if (limitException != null) {
                throw limitException;
            }
            if (totalRead >= maxBytes) {
                limitException = new IOException("Request body exceeds configured limit");
                throw limitException;
            }
            int b = delegate.read();
            if (b >= 0) {
                totalRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (limitException != null) {
                throw limitException;
            }
            if (totalRead >= maxBytes) {
                limitException = new IOException("Request body exceeds configured limit");
                throw limitException;
            }
            long remaining = maxBytes - totalRead;
            int toRead = (int) Math.min(len, remaining);
            if (toRead == 0) {
                limitException = new IOException("Request body exceeds configured limit");
                throw limitException;
            }
            int n = delegate.read(b, off, toRead);
            if (n > 0) {
                totalRead += n;
            }
            return n;
        }
    }
}
