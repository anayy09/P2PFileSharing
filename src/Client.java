import java.net.*;
import java.io.*;

public class Client {
    private Socket requestSocket;
    private DataOutputStream out;
    private DataInputStream in;
    private int peerID;

    public Client(int peerID) {
        this.peerID = peerID;
    }

    public void run(String serverAddress, int serverPort) {
        try {
            // Connect to the peer (server)
            requestSocket = new Socket(serverAddress, serverPort);
            System.out.println("Connected to Peer at " + serverAddress + ":" + serverPort);

            // Initialize streams
            out = new DataOutputStream(requestSocket.getOutputStream());
            in = new DataInputStream(requestSocket.getInputStream());

            // Send handshake message
            sendHandshake();

            // Receive and validate handshake response
            if (receiveHandshake()) {
                System.out.println("Handshake successful with Peer " + serverAddress);
            } else {
                System.out.println("Handshake failed.");
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            closeConnections();
        }
    }

    private void sendHandshake() throws IOException {
        byte[] handshakeMessage = new byte[32];

        // Fill in handshake header (18 bytes)
        System.arraycopy("P2PFILESHARINGPROJ".getBytes(), 0, handshakeMessage, 0, 18);

        // Fill in zero bits (10 bytes) - already zero-initialized in Java

        // Fill in Peer ID (last 4 bytes)
        byte[] peerIDBytes = intToByteArray(peerID);
        System.arraycopy(peerIDBytes, 0, handshakeMessage, 28, 4);

        // Send the handshake message
        out.write(handshakeMessage);
        out.flush();
        System.out.println("Handshake message sent.");
    }

    private boolean receiveHandshake() throws IOException {
        byte[] receivedHandshake = new byte[32];
        in.readFully(receivedHandshake);

        // Extract received header and peer ID
        String receivedHeader = new String(receivedHandshake, 0, 18);
        int receivedPeerID = byteArrayToInt(receivedHandshake, 28);

        // Validate the handshake
        return receivedHeader.equals("P2PFILESHARINGPROJ");
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
            if (requestSocket != null) requestSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        int peerID = 1002; // Example peer ID
        Client client = new Client(peerID);
        client.run("localhost", 6001);
    }
}
