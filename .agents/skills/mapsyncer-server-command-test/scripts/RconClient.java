import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RCON client for the MapSyncer server test matrix.
 *
 * Usage:
 *   java RconClient <host> <port> <password> <command>
 *
 * The command is sent without a leading slash. The client logs in for each
 * invocation, prints the response, and treats EOF after "stop" as success.
 */
public final class RconClient {
    private static final int LOGIN = 3;
    private static final int COMMAND = 2;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: RconClient <host> <port> <password> <command>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String password = args[2];
        String command = args[3];

        if (command.startsWith("/")) {
            System.err.println("RCON command must not start with '/': " + command);
            System.exit(2);
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            send(out, 1, LOGIN, password);
            Packet login = read(in);
            if (login.id != 1) {
                throw new IOException("RCON login failed, response id=" + login.id);
            }

            send(out, 2, COMMAND, command);
            socket.setSoTimeout(500);
            StringBuilder result = new StringBuilder();
            while (true) {
                try {
                    Packet packet = read(in);
                    if (!packet.body.isEmpty()) {
                        if (result.length() > 0) {
                            result.append('\n');
                        }
                        result.append(packet.body);
                    }
                } catch (SocketTimeoutException timeout) {
                    break;
                } catch (EOFException eof) {
                    if (command.trim().equalsIgnoreCase("stop")) {
                        break;
                    }
                    throw eof;
                }
            }
            if (result.length() > 0) {
                System.out.println(stripColors(result.toString()));
            }
        }
    }

    private static void send(DataOutputStream out, int id, int type, String body) throws IOException {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + text.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4 + 4 + text.length + 2);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(text);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private static Packet read(DataInputStream in) throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > 4 * 1024 * 1024) {
            throw new IOException("invalid RCON packet length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        int bodyLength = Math.max(0, length - 10);
        String body = new String(data, 8, bodyLength, StandardCharsets.UTF_8).replace("\u0000", "");
        return new Packet(id, type, body);
    }

    private static String stripColors(String input) {
        return input.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "");
    }

    private record Packet(int id, int type, String body) {
    }
}
