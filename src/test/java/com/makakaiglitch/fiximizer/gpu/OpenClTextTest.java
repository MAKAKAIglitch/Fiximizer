package com.makakaiglitch.fiximizer.gpu;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenClTextTest {
    @Test
    void decodesOnlyTheNullTerminatedStringAndIgnoresNativeBufferTail() {
        byte[] nativeBuffer = new byte[512];
        byte[] deviceName = "AMD Radeon RX 6600".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(deviceName, 0, nativeBuffer, 0, deviceName.length);
        nativeBuffer[deviceName.length] = 0;

        // Simulate uninitialized bytes after the C string terminator.
        for (int index = deviceName.length + 1; index < nativeBuffer.length; index++) {
            nativeBuffer[index] = (byte) 0x7F;
        }

        assertEquals(
                "AMD Radeon RX 6600",
                OpenClText.decode(ByteBuffer.wrap(nativeBuffer), nativeBuffer.length)
        );
    }

    @Test
    void decodesUtf8DeviceNames() {
        byte[] nativeBuffer = "Графический процессор".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "Графический процессор",
                OpenClText.decode(ByteBuffer.wrap(nativeBuffer), nativeBuffer.length)
        );
    }

    @Test
    void rejectsInvalidReportedLengths() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        assertEquals("", OpenClText.decode(buffer, 0));
        assertEquals("", OpenClText.decode(buffer, 9));
    }

    @Test
    void stripsControlCharactersFromDecodedText() {
        byte[] nativeBuffer = ("OpenCL" + (char) 1 + " GPU\nDriver").getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "OpenCL GPU Driver",
                OpenClText.decode(ByteBuffer.wrap(nativeBuffer), nativeBuffer.length)
        );
    }
}
