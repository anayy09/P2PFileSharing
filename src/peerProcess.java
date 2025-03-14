import java.io.*;
import java.net.*;
import java.util.*;

public class peerProcess {
    private int peerID;
    private String hostName;
    private int listeningPort;
    private boolean hasFile; // Commented out for now
    private List<PeerInfo> peerList = new ArrayList<>();
    private Map<Integer, Socket> peerConnections = new HashMap<>(); // Stores active peer connections
    private byte[] bitfield; // Bitfield added

    // New Config Variables from Common.cfg
    private int numberOfPreferredNeighbors;
    private int unchokingInterval;
    private int optimisticUnchokingInterval;
    private String fileName;
    private int fileSize;
    private int pieceSize;
    private int numPieces;

    public peerProcess(int peerID) {
        this.peerID = peerID;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java peerProcess <peerID>");
            return;
        }

        int peerID = Integer.parseInt(args[0]);
        peerProcess peer = new peerProcess(peerID);
        peer.initialize();
        peer.start();
    }

    private void initialize() {
        parseCommonConfig(); // New: Parse Common.cfg
        parsePeerInfo();
    }

    private void parseCommonConfig() {
        try (BufferedReader br = new BufferedReader(new FileReader("Common.cfg"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length != 2)
                    continue;

                switch (parts[0]) {
                    case "NumberOfPreferredNeighbors":
                        numberOfPreferredNeighbors = Integer.parseInt(parts[1]);
                        break;
                    case "UnchokingInterval":
                        unchokingInterval = Integer.parseInt(parts[1]);
                        break;
                    case "OptimisticUnchokingInterval":
                        optimisticUnchokingInterval = Integer.parseInt(parts[1]);
                        break;
                    case "FileName":
                        fileName = parts[1];
                        break;
                    case "FileSize":
                        fileSize = Integer.parseInt(parts[1]);
                        break;
                    case "PieceSize":
                        pieceSize = Integer.parseInt(parts[1]);
                        break;
                }
            }

            numPieces = (int) Math.ceil((double) fileSize / pieceSize);
            log("Common.cfg parsed successfully.");

        } catch (IOException e) {
            System.err.println("Error reading Common.cfg");
            e.printStackTrace();
        }
    }

    private void parsePeerInfo() {
        try (BufferedReader br = new BufferedReader(new FileReader("PeerInfo.cfg"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                // Split line into parts
                String[] parts = line.split("\\s+");

                // Ensure correct number of parts
                if (parts.length != 4) {
                    System.err.println("Error: Invalid format in PeerInfo.cfg: " + line);
                    continue;
                }

                // Parse Peer Info
                int id = Integer.parseInt(parts[0]);
                String host = parts[1];
                int port = Integer.parseInt(parts[2]);
                boolean hasFile = parts[3].equals("1"); // Commented out for now

                peerList.add(new PeerInfo(id, host, port, hasFile));

                // Store details for this peer
                if (id == peerID) {
                    this.hostName = host;
                    this.listeningPort = port;
                    this.hasFile = hasFile;
                    initializeBitfield();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading PeerInfo.cfg");
            e.printStackTrace();
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid number format in PeerInfo.cfg");
            e.printStackTrace();
        }
    }

    private void initializeBitfield() {
        bitfield = new byte[(numPieces + 7) / 8];

        if (hasFile) {
            Arrays.fill(bitfield, (byte) 0xFF); // Mark all pieces as available
        }
    }

    private void start() {
        // Start listening for incoming connections
        new Thread(() -> startServer()).start();

        // Connect to peers that started before this one
        connectToPeers();

        // Start interactive message sender
        startMessageSender();
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(listeningPort)) {
            log("Peer " + peerID + " is listening on port " + listeningPort);

            while (true) {
                Socket socket = serverSocket.accept();
                log("Connection received from " + socket.getInetAddress());
                new PeerHandler(socket, -1).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void log(String message) {
        String logMessage = "[" + new Date() + "] " + message;
        System.out.println(logMessage);

        // Create log directory if it doesn't exist
        File logDir = new File("log");
        if (!logDir.exists()) {
            logDir.mkdir();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter("log/log_peer_" + peerID + ".log", true))) {
            writer.write(logMessage + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void connectToPeers() {
        for (PeerInfo peer : peerList) {
            if (peer.id == peerID)
                break; // Stop at current peer
            try {
                Socket socket = new Socket(peer.hostName, peer.port);
                peerConnections.put(peer.id, socket); // Store connection
                log("Connected to Peer " + peer.id);
                new PeerHandler(socket, peer.id).start();
            } catch (IOException e) {
                System.out.println("Could not connect to Peer " + peer.id);
            }
        }
    }

    private void startMessageSender() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine();

            // Parse input in the format "<peerID>: <message>"
            int colonIndex = input.indexOf(':');
            if (colonIndex == -1) {
                System.out.println("Usage: <peerID>: <message>");
                continue;
            }

            // Extract peer ID and message
            String peerIDStr = input.substring(0, colonIndex).trim();
            String message = input.substring(colonIndex + 1).trim();

            // Validate peer ID
            int targetPeerID;
            try {
                targetPeerID = Integer.parseInt(peerIDStr);
            } catch (NumberFormatException e) {
                System.out.println("Invalid peer ID.");
                continue;
            }

            if (message.isEmpty()) {
                System.out.println("Message cannot be empty.");
                continue;
            }

            if (!peerConnections.containsKey(targetPeerID)) {
                System.out.println("Error: No connection to Peer " + targetPeerID);
                continue;
            }

            sendMessage(targetPeerID, message);
        }
    }

    private void sendMessage(int targetPeerID, String message) {
        try {
            Socket socket = peerConnections.get(targetPeerID);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF("From " + peerID + ": " + message);
            out.flush();
            log("Sent message to Peer " + targetPeerID + ": " + message);
        } catch (IOException e) {
            System.out.println("Error sending message to Peer " + targetPeerID);
        }
    }

    private class PeerHandler extends Thread {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private int connectedPeerID;

        public PeerHandler(Socket socket, int connectedPeerID) {
            this.socket = socket;
            this.connectedPeerID = connectedPeerID;
        }

        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                // Perform Handshake
                sendHandshake();
                if (receiveHandshake()) {
                    log("Handshake successful with Peer " + connectedPeerID);
                    sendBitfield();
                } else {
                    log("Handshake failed");
                }

                // Message reception loop
                while (true) {
                    try {
                        // Try to receive regular messages first
                        String message = in.readUTF();
                        log("Received: " + message);
                        continue;
                    } catch (UTFDataFormatException e) {
                        // Not a UTF message, might be a bitfield or other protocol message
                    }

                    // Handle protocol messages like bitfield
                    receiveBitfield();
                }
            } catch (IOException e) {
                log("Connection closed with Peer " + connectedPeerID);
            }
        }

        private void sendHandshake() throws IOException {
            byte[] handshake = new byte[32];
            System.arraycopy("P2PFILESHARINGPROJ".getBytes(), 0, handshake, 0, 18);
            byte[] peerIDBytes = intToByteArray(peerID);
            System.arraycopy(peerIDBytes, 0, handshake, 28, 4);
            out.write(handshake);
            out.flush();
            log("Sent handshake to peer.");
        }

        private boolean receiveHandshake() throws IOException {
            byte[] handshake = new byte[32];
            in.readFully(handshake);
            String header = new String(handshake, 0, 18);

            // Extract the peer ID from the handshake
            int remotePeerID = byteArrayToInt(handshake, 28);

            // Update connectedPeerID if it was unknown (-1)
            if (connectedPeerID == -1) {
                connectedPeerID = remotePeerID;
                // Register this connection in the main peer's connection map
                peerProcess.this.peerConnections.put(remotePeerID, socket);
            }
            return header.equals("P2PFILESHARINGPROJ");
        }

        private void sendBitfield() throws IOException {
            if (bitfield == null || bitfield.length == 0)
                return;

            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream dataOut = new DataOutputStream(byteStream);

            dataOut.writeInt(bitfield.length + 1);
            dataOut.writeByte(5); // Bitfield message type
            dataOut.write(bitfield);

            out.write(byteStream.toByteArray());
            out.flush();
            log("Sent Bitfield message.");
        }

        private void receiveBitfield() throws IOException {
            int length = in.readInt();
            byte type = in.readByte();

            if (type == 5) { // Bitfield message type
                byte[] receivedBitfield = new byte[length - 1];
                in.readFully(receivedBitfield);
                log("Received Bitfield from Peer " + connectedPeerID);
            }
        }

        private void log(String message) {
            String logMessage = "[" + new Date() + "] " + message;
            System.out.println(logMessage);

            // Create log directory if it doesn't exist
            File logDir = new File("log");
            if (!logDir.exists()) {
                logDir.mkdir();
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter("log/log_peer_" + peerID + ".log", true))) {
                writer.write(logMessage + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private byte[] intToByteArray(int value) {
            return new byte[] {
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
    }

    class PeerInfo {
        int id;
        String hostName;
        int port;
        boolean hasFile;

        public PeerInfo(int id, String hostName, int port, boolean hasFile) {
            this.id = id;
            this.hostName = hostName;
            this.port = port;
            this.hasFile = hasFile;
        }
    }
}
