import java.net.*;
import java.io.*;

public class Server {
    private static final int PORT = 6001;  // Listening port
    private int peerID;

    public Server(int peerID) {
        this.peerID = peerID;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Peer " + peerID + " is listening on port " + PORT + "...");

            while (true) {
                Socket connection = serverSocket.accept();
                System.out.println("New connection from " + connection.getInetAddress());

                new Handler(connection, peerID).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class Handler extends Thread {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private int peerID;

        public Handler(Socket socket, int peerID) {
            this.socket = socket;
            this.peerID = peerID;
        }

        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                // Receive handshake
                if (receiveHandshake()) {
                    System.out.println("Handshake successful!");
                    sendHandshake();
                } else {
                    System.out.println("Invalid handshake received.");
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                closeConnections();
            }
        }

        private boolean receiveHandshake() throws IOException {
            byte[] receivedHandshake = new byte[32];
            in.readFully(receivedHandshake);

            // Extract header and peer ID
            String receivedHeader = new String(receivedHandshake, 0, 18);
            int receivedPeerID = byteArrayToInt(receivedHandshake, 28);

            // Validate handshake
            return receivedHeader.equals("P2PFILESHARINGPROJ");
        }

        private void sendHandshake() throws IOException {
            byte[] handshakeMessage = new byte[32];

            // Fill in handshake header (18 bytes)
            System.arraycopy("P2PFILESHARINGPROJ".getBytes(), 0, handshakeMessage, 0, 18);

            // Fill in zero bits (10 bytes) - already zero-initialized

            // Fill in Peer ID (last 4 bytes)
            byte[] peerIDBytes = intToByteArray(peerID);
            System.arraycopy(peerIDBytes, 0, handshakeMessage, 28, 4);

            // Send handshake
            out.write(handshakeMessage);
            out.flush();
            System.out.println("Handshake sent to peer.");
        }

        private byte[] intToByteArray(int value) {
            return new byte[]{
                    (byte) (value >> 24),
                    (byte) (value >> 16),
                    (byte) (value >> 8),
                    (byte) value
            };
        }

        private int byteArrayToInt(byte[] bytes, int startIndex) {
            return ((bytes[startIndex] & 0xFF) << 24) |
                   ((bytes[startIndex + 1] & 0xFF) << 16) |
                   ((bytes[startIndex + 2] & 0xFF) << 8) |
                   (bytes[startIndex + 3] & 0xFF);
        }

        private void closeConnections() {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        int peerID = 1001; // Example Peer ID
        new Server(peerID).start();
    }
}
