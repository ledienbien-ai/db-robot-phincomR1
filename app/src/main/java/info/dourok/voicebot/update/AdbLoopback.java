package info.dourok.voicebot.update;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Runs one shell command through this device's own adb daemon, over TCP on localhost.
 *
 * An ordinary app cannot install a package silently, and the R1 has no screen on which to confirm
 * an install dialog. Its adbd, however, listens on the network with authorisation switched off
 * (that is how the app gets installed in the first place), and a shell opened there may run
 * {@code pm install}. So the updater talks to it the way a PC would: this is the client side of the
 * ADB wire protocol, cut down to "connect, open one shell stream, collect its output".
 *
 * Wire format: every message is six little-endian 32-bit words -- command, arg0, arg1, payload
 * length, payload checksum (the plain sum of its bytes), and the command with all bits flipped --
 * followed by the payload. Android 5.1's adbd verifies the checksum, so it is always sent.
 *
 * Plain Java with no Android dependency, so it can be exercised off the device.
 */
public final class AdbLoopback {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    /** Refuse absurd lengths rather than allocate them: a corrupt header is not worth an OOM. */
    private static final int SANE_PAYLOAD = 1 << 20;
    private static final byte[] EMPTY = new byte[0];
    /** How long to go on listening once the shell is running; see {@link #shell}. */
    private static final int LAUNCH_GRACE_MS = 1500;

    /**
     * The daemon took the connection but never answered it. On this device that has one cause:
     * adbd files every network client under the same name, "host", and refuses a second one while
     * the first is still attached -- without closing the socket or saying a word. So if a computer
     * on the network still has its {@code adb connect} open, the speaker cannot reach its own
     * daemon. Measured on the R1, 10/10/2026: 15 s of silence with a PC connected, an immediate
     * answer the moment the PC ran {@code adb kill-server}.
     */
    public static final class BusyException extends IOException {
        private static final long serialVersionUID = 1L;

        BusyException() {
            super("adbd is serving another host");
        }
    }

    private AdbLoopback() {
    }

    /**
     * Start {@code command} in a device shell.
     *
     * Returns as soon as the daemon has accepted the command, plus a short moment to collect
     * whatever it prints straight away -- not when the shell exits. The command this is used for
     * detaches its work and prints nothing, and adbd does not report the stream closed until every
     * process holding the shell's terminal is gone, which for a detached job can be minutes.
     * Waiting for that close would turn every successful launch into a timeout.
     *
     * @param timeoutMs longest silence tolerated during the handshake.
     * @return what the shell printed in its first moment (usually nothing).
     * @throws BusyException if the daemon accepts the connection but does not answer.
     * @throws IOException   if the daemon is unreachable, wants authorisation, or refuses the stream.
     */
    public static String shell(String host, int port, String command, int timeoutMs)
            throws IOException {
        byte[] service = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
        if (service.length > MAX_PAYLOAD) {
            throw new IOException("command too long for one adb packet");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            send(out, A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message reply;
            try {
                reply = read(in);
            } catch (java.net.SocketTimeoutException e) {
                throw new BusyException();
            }
            if (reply.command == A_AUTH) {
                throw new IOException("adbd on this device asks for authorisation");
            }
            if (reply.command != A_CNXN) {
                throw new IOException("unexpected adb reply " + Integer.toHexString(reply.command));
            }

            final int localId = 1;
            send(out, A_OPEN, localId, 0, service);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean opened = false;
            while (true) {
                Message m;
                try {
                    m = read(in);
                } catch (java.net.SocketTimeoutException e) {
                    if (opened) {
                        return new String(output.toByteArray(), StandardCharsets.UTF_8);
                    }
                    throw new IOException("adbd did not open a shell");
                }
                if (m.arg1 != localId) {
                    continue;   // not about our stream (a late CNXN, or noise) -- nothing to answer
                }
                if (m.command == A_OKAY) {
                    if (!opened) {
                        // The shell is running. Anything it says from here on is a bonus.
                        opened = true;
                        socket.setSoTimeout(LAUNCH_GRACE_MS);
                    }
                } else if (m.command == A_WRTE) {
                    opened = true;
                    output.write(m.payload, 0, m.payload.length);
                    send(out, A_OKAY, localId, m.arg0, EMPTY);   // every WRTE must be acknowledged
                } else if (m.command == A_CLSE) {
                    if (!opened) {
                        throw new IOException("adbd refused to open a shell");
                    }
                    send(out, A_CLSE, localId, m.arg0, EMPTY);
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do about a socket that will not close
            }
        }
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        int sum = 0;
        for (byte b : payload) {
            sum += b & 0xff;
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1)
                .putInt(payload.length).putInt(sum).putInt(~command);
        // One write per message: adbd reads header and payload separately, but a single buffer
        // cannot be torn apart by another writer.
        byte[] packet = new byte[24 + payload.length];
        System.arraycopy(header.array(), 0, packet, 0, 24);
        System.arraycopy(payload, 0, packet, 24, payload.length);
        out.write(packet);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        header.getInt();   // checksum: ours to send, not worth refusing a reply over
        int magic = header.getInt();
        if (magic != ~m.command || length < 0 || length > SANE_PAYLOAD) {
            throw new IOException("corrupt adb packet");
        }
        m.payload = new byte[length];
        in.readFully(m.payload);
        return m;
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] payload;
    }
}
