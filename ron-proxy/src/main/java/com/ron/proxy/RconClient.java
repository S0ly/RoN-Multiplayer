package com.ron.proxy;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Minecraft RCON client.
 * Protocol: https://wiki.vg/RCON
 */
public class RconClient implements AutoCloseable {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int requestId = 1;

    public RconClient(String host, int port, String password) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 3000);
        this.socket.setSoTimeout(5000);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();

        // Authenticate
        sendPacket(TYPE_AUTH, password);
        int[] response = readPacket();
        if (response[0] == -1) {
            close();
            throw new IOException("RCON authentication failed");
        }
    }

    public String sendCommand(String command) throws IOException {
        int id = sendPacket(TYPE_COMMAND, command);
        int[] response = readPacket();
        if (response[0] != id) {
            throw new IOException("RCON response ID mismatch: expected " + id + ", got " + response[0]);
        }
        return new String(readPayload, StandardCharsets.UTF_8).trim();
    }

    // Temp storage for payload from last readPacket
    private byte[] readPayload = new byte[0];

    private int sendPacket(int type, String payload) throws IOException {
        int id = requestId++;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + payloadBytes.length + 2; // id + type + payload + padding

        ByteBuffer buf = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payloadBytes);
        buf.put((byte) 0);
        buf.put((byte) 0);

        out.write(buf.array());
        out.flush();
        return id;
    }

    private int[] readPacket() throws IOException {
        byte[] header = in.readNBytes(4);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();

        byte[] data = in.readNBytes(length);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int id = buf.getInt();
        int type = buf.getInt();

        int payloadLength = length - 4 - 4 - 2;
        readPayload = new byte[Math.max(0, payloadLength)];
        if (payloadLength > 0) {
            buf.get(readPayload);
        }

        return new int[]{id, type};
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
