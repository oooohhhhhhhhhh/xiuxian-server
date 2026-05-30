package com.mtxgdn.minecraft;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class VarInt {

    private VarInt() {
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
        return value;
    }

    public static byte[] writeVarInt(int value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true) {
            if ((value & ~0x7F) == 0) {
                baos.write(value);
                break;
            }
            baos.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        return baos.toByteArray();
    }

    public static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes("UTF-8");
        out.write(writeVarInt(bytes.length));
        out.write(bytes);
    }

    public static void writePacket(int packetId, byte[] data, DataOutputStream out) throws IOException {
        byte[] idBytes = writeVarInt(packetId);
        int totalLength = idBytes.length + (data != null ? data.length : 0);
        out.write(writeVarInt(totalLength));
        out.write(idBytes);
        if (data != null) {
            out.write(data);
        }
        out.flush();
    }
}
