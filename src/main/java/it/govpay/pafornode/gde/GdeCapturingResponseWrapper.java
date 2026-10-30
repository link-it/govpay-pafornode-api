package it.govpay.pafornode.gde;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Wrapper "tee": ogni byte scritto dall'endpoint/servizio arriva SEMPRE al client reale
 * (passthrough immediato, nessun buffering completo in memoria né un flush esplicito da
 * ricordare a fine richiesta), e in parallelo viene accumulato un buffer con soglia massima
 * per l'eventuale payload GDE. Oltre la soglia si smette di accumulare (payload marcato
 * troncato) ma il passthrough continua invariato.
 * <p>
 * Nota: cattura solo ciò che passa da {@link #getOutputStream()}. Spring-WS scrive il
 * messaggio SOAP sullo stream (tramite {@code HttpServletConnection}, non via {@code
 * getWriter()}), quindi questa condizione è sempre soddisfatta per le risposte di questo
 * servizio.
 */
public class GdeCapturingResponseWrapper extends HttpServletResponseWrapper {

    private final int maxCaptureBytes;
    private TeeServletOutputStream teeStream;

    public GdeCapturingResponseWrapper(HttpServletResponse response, int maxCaptureBytes) {
        super(response);
        this.maxCaptureBytes = maxCaptureBytes;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (teeStream == null) {
            teeStream = new TeeServletOutputStream(getResponse().getOutputStream(), maxCaptureBytes);
        }
        return teeStream;
    }

    public byte[] getCapturedBytes() {
        return teeStream != null ? teeStream.getCapturedBytes() : new byte[0];
    }

    public boolean isTruncated() {
        return teeStream != null && teeStream.isTruncated();
    }

    private static final class TeeServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream target;
        private final ByteArrayOutputStream capture;
        private final int maxCaptureBytes;
        private boolean truncated = false;

        TeeServletOutputStream(ServletOutputStream target, int maxCaptureBytes) {
            this.target = target;
            this.maxCaptureBytes = maxCaptureBytes;
            this.capture = new ByteArrayOutputStream(Math.clamp(maxCaptureBytes, 0, 8192));
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            appendToCapture(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            appendToCapture(b, off, len);
        }

        private void appendToCapture(byte[] b, int off, int len) {
            if (truncated) {
                return;
            }
            int remaining = maxCaptureBytes - capture.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(remaining, len);
            capture.write(b, off, toWrite);
            if (toWrite < len) {
                truncated = true;
            }
        }

        byte[] getCapturedBytes() {
            return capture.toByteArray();
        }

        boolean isTruncated() {
            return truncated;
        }

        @Override
        public boolean isReady() {
            return target.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            target.setWriteListener(writeListener);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }
}
