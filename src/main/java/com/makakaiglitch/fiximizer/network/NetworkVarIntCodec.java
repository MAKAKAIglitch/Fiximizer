package com.makakaiglitch.fiximizer.network;

import java.nio.ByteBuffer;

/**
 * Batch VarInt codec used by Fiximizer's backend capability benchmark.
 *
 * The wire representation matches Minecraft's unsigned-shift VarInt encoding,
 * including five-byte representations of negative integers.
 */
public final class NetworkVarIntCodec {
    private NetworkVarIntCodec() {
    }

    public static int encode(ByteBuffer values, int valueCount, ByteBuffer output) {
        if (valueCount < 0 || values.remaining() < valueCount * Integer.BYTES) {
            throw new IllegalArgumentException("Invalid VarInt input count");
        }

        int start = output.position();
        for (int i = 0; i < valueCount; i++) {
            int value = values.getInt();
            while ((value & ~0x7F) != 0) {
                output.put((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            output.put((byte) value);
        }
        return output.position() - start;
    }

    public static int decode(ByteBuffer input, int encodedLength, ByteBuffer output) {
        if (encodedLength < 0 || input.remaining() < encodedLength) {
            throw new IllegalArgumentException("Invalid encoded VarInt length");
        }

        int end = input.position() + encodedLength;
        int decoded = 0;
        while (input.position() < end) {
            int value = 0;
            int bytesRead = 0;
            boolean complete = false;

            while (input.position() < end && bytesRead < 5) {
                int current = input.get() & 0xFF;
                if (bytesRead == 4 && (current & 0xF0) != 0) {
                    throw new IllegalArgumentException("VarInt exceeds 32 bits");
                }

                value |= (current & 0x7F) << (bytesRead * 7);
                bytesRead++;
                if ((current & 0x80) == 0) {
                    complete = true;
                    break;
                }
            }

            if (!complete) {
                throw new IllegalArgumentException("Truncated or oversized VarInt");
            }

            output.putInt(value);
            decoded++;
        }
        return decoded;
    }
}
