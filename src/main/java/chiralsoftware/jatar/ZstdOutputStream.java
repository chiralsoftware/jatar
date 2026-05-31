package chiralsoftware.jatar;

import dev.hallock.zstd.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;

import static dev.hallock.zstd.ZstdCompressionParameter.COMPRESSION_LEVEL;
import static dev.hallock.zstd.ZstdCompressionParameter.NB_WORKERS;
import static dev.hallock.zstd.ZstdEndDirective.CONTINUE;
import static dev.hallock.zstd.ZstdEndDirective.END;
import static dev.hallock.zstd.bindings.ZSTD_h.ZSTD_CStreamInSize;
import static dev.hallock.zstd.bindings.ZSTD_h.ZSTD_CStreamOutSize;
import static java.lang.Math.min;
import static java.lang.System.arraycopy;
import static java.lang.foreign.Arena.ofConfined;
import static java.lang.foreign.MemorySegment.copy;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

final class ZstdOutputStream extends OutputStream {

    private boolean closed = false;

    private final OutputStream out;
    private final Arena arena = ofConfined();
    private final ZstdCompressionContext context = new ZstdCompressionContext();
    private final MemorySegment inSegment = arena.allocate(ZSTD_CStreamInSize());
    private final MemorySegment outSegment = arena.allocate(ZSTD_CStreamOutSize());
    private final ZstdInputBuffer inputBuffer = new ZstdInputBuffer(arena, inSegment);
    private final ZstdOutputBuffer outputBuffer = new ZstdOutputBuffer(arena, outSegment);
    private final byte[] buffer = new byte[(int) inSegment.byteSize()];
    private final byte[] outBuffer = new byte[(int) outSegment.byteSize()];

    private int bufferPosition = 0;

    ZstdOutputStream(OutputStream out, Map<ZstdCompressionParameter, Integer> parameters) {
        this.out = out;

        context.parameter(COMPRESSION_LEVEL, parameters.getOrDefault(COMPRESSION_LEVEL, 18));
        context.parameter(NB_WORKERS, parameters.getOrDefault(NB_WORKERS, 4));
    }

    @Override
    public void write(int i) throws IOException {
        if(closed) throw new IOException("Stream closed");
        buffer[bufferPosition++] = (byte) i;
        if(bufferPosition == buffer.length) {
            writeChunk();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if(closed) throw new IOException("Stream closed");
        while (len > 0) {
            final int space = buffer.length - bufferPosition;
            final int toCopy = min(space, len);

            arraycopy(b, off, buffer, bufferPosition, toCopy);
            bufferPosition += toCopy;
            off += toCopy;
            len -= toCopy;

            if (bufferPosition == buffer.length) writeChunk();
        }
    }

    private void writeChunk() throws IOException {
        if(closed) throw new IOException("Stream closed");
        if(bufferPosition == 0) return;
        copy(buffer, 0, inSegment, JAVA_BYTE, 0, bufferPosition);
        inputBuffer.size(bufferPosition);
        inputBuffer.position(0);
        while(inputBuffer.position() < inputBuffer.size()) {
            outputBuffer.position(0);
            context.compressStream(outputBuffer, inputBuffer, CONTINUE);
            final int produced = (int) outputBuffer.position();
            if(produced > 0) {
                copy(outSegment, JAVA_BYTE, 0, outBuffer, 0, produced);
                out.write(outBuffer, 0, produced);
            }
        }
        bufferPosition = 0;
    }

    @Override
    public void close() throws IOException {
        if(closed) throw new IOException("Stream closed");
        writeChunk(); // flush remaining input
        inputBuffer.size(0);
        inputBuffer.position(0);
        boolean done = false;
        while (!done) {
            outputBuffer.position(0);

            var result = context.compressStream(outputBuffer, inputBuffer, END);

            int produced = (int) outputBuffer.position();
            if (produced > 0) {
                copy(outSegment, JAVA_BYTE, 0, outBuffer, 0, produced);
                out.write(outBuffer, 0, produced);
            }

            done = result instanceof ZstdResult.Ok;
        }

        out.flush();
        out.close();
        arena.close();
        closed = true;
    }
}
