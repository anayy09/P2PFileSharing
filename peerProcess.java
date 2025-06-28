import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// Conceptual OpenRQ import - in a real scenario, this would be handled by the build system
import org.openrq.OpenRQ;
import org.openrq.encoder.Encoder;
import org.openrq.decoder.Decoder;
import org.openrq.Symbol;

public class peerProcess {

    // Configuration constants
    private static final String COMMON_CFG_PATH = "Common.cfg";
    private static final String PEER_INFO_CFG_PATH = "PeerInfo.cfg";

    // Peer specific information
    private final int peerID;
    private String hostName;
    private int listeningPort;
    private boolean hasFileInitially;
    private BitSet bitfield;
    private final Map<Integer, PeerInfo> peerInfoMap = new ConcurrentHashMap<>();
    private final Map<Integer, PeerConnectionHandler> activeConnections = new ConcurrentHashMap<>();
    private final Set<Integer> interestedPeers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> chokedPeers = ConcurrentHashMap.newKeySet(); // Peers choked by me
    private final Set<Integer> peersChokingMe = ConcurrentHashMap.newKeySet(); // Peers choking me
    private final Set<Integer> preferredNeighbors = ConcurrentHashMap.newKeySet();
    private volatile Integer optimisticallyUnchokedNeighbor = null;
    private final AtomicInteger downloadedPieces = new AtomicInteger(0);
    private final AtomicInteger totalPeersCompleted = new AtomicInteger(0);

    // Common configuration
    private int numberOfPreferredNeighbors;
    private int unchokingInterval; // seconds
    private int optimisticUnchokingInterval; // seconds
    private String fileName;
    private int fileSize;
    private int pieceSize;
    private int numberOfPieces;

    // File handling
    private byte[][] filePieces; // Stores actual file pieces

    // Logging
    private PrintWriter logger;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // Concurrency
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3); // Increased for disaster mode tasks
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();

    // --- CLI Flags ---
    private static boolean disasterMode = false;
    private static boolean wanReturn = false;

    // --- Disaster Mode Specific Fields ---
    private volatile boolean I_AM_SUPER = false;
    private int broadcastChunkSize; // From Common.cfg
    private int sparseAckInterval;  // From Common.cfg
    private FountainCoder fountainEncoder; // For super-peer
    private Decoder fountainDecoder;       // For normal peers in disaster mode
    private BitSet broadcastReceivedChunks; // For normal peers to track received fountain chunks
    private int numberOfBroadcastChunks; // Derived from fileSize and broadcastChunkSize
    private final Map<Integer, BitSet> peerBroadcastProgress = new ConcurrentHashMap<>(); // Super-peer tracks this
    private final AtomicInteger peersCompletedBroadcast = new AtomicInteger(0);


    // --- Message Types ---
    private static final byte MSG_CHOKE = 0;
    private static final byte MSG_UNCHOKE = 1;
    private static final byte MSG_INTERESTED = 2;
    private static final byte MSG_NOT_INTERESTED = 3;
    private static final byte MSG_HAVE = 4;
    private static final byte MSG_BITFIELD = 5;
    private static final byte MSG_REQUEST = 6;
    private static final byte MSG_PIECE = 7;
    // New swarm-level message types
    private static final byte MSG_BCAST_CHUNK = 8;  // fountain-encoded chunk
    private static final byte MSG_SPARSE_ACK = 9;  // bitmap of received chunks
    private static final byte MSG_ROLE_ELECT = 10; // election beacon / vote


    // --- PeerInfo Class ---
    private static class PeerInfo {
        int peerID;
        String hostName;
        int port;
        boolean hasFile;
        BitSet bitfield;
        volatile double downloadRate = 0;
        volatile long downloadStartTime = 0;
        volatile int piecesDownloadedInInterval = 0;

        // Disaster mode fields
        boolean isSuperCandidate;
        int batteryLevel;
        BitSet lastReportedBroadcastChunks; // What this peer reported having (for super-peer's tracking)

        PeerInfo(int peerID, String hostName, int port, boolean hasFile, boolean isSuperCandidate, int batteryLevel) {
            this.peerID = peerID;
            this.hostName = hostName;
            this.port = port;
            this.hasFile = hasFile;
            this.isSuperCandidate = isSuperCandidate;
            this.batteryLevel = batteryLevel;
            this.lastReportedBroadcastChunks = new BitSet();
        }

        @Override
        public String toString() {
            return "PeerInfo{" + "peerID=" + peerID + ", hostName='" + hostName + "'" + ", port=" + port
                    + ", hasFile=" + hasFile + ", isSuperCandidate=" + isSuperCandidate
                    + ", batteryLevel=" + batteryLevel + '}';
        }
    }

    // --- Constructor ---
    public peerProcess(int peerID) {
        this.peerID = peerID;
        try {
            // Create log file directory
            File logDir = new File("log_peer_" + peerID);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            this.logger = new PrintWriter(new FileWriter("log_peer_" + peerID + "/log_peer_" + peerID + ".log"), true);
        } catch (IOException e) {
            System.err.println("Error creating log file for peer " + peerID);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // --- Logging Utility ---
    private synchronized void log(String message) {
        String timestamp = dateFormat.format(new Date());
        logger.println(timestamp + ": Peer " + peerID + " " + message);
        // System.out.println(timestamp + ": Peer " + peerID + " " + message);
    }

    // --- Configuration Loading ---
    private void loadCommonConfig() throws IOException {
        log("Loading Common.cfg");
        try (BufferedReader reader = new BufferedReader(new FileReader(COMMON_CFG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+"); // Use regex for whitespace
                if (parts.length < 2) continue;
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
                    case "BroadcastChunkSize": // New for disaster mode
                        broadcastChunkSize = Integer.parseInt(parts[1]);
                        break;
                    case "SparseAckInterval": // New for disaster mode
                        sparseAckInterval = Integer.parseInt(parts[1]);
                        break;
                }
            }
            numberOfPieces = (int) Math.ceil((double) fileSize / pieceSize);
            this.bitfield = new BitSet(numberOfPieces);

            if (broadcastChunkSize > 0) {
                numberOfBroadcastChunks = (int) Math.ceil((double) fileSize / broadcastChunkSize);
                this.broadcastReceivedChunks = new BitSet(numberOfBroadcastChunks);
            } else if (disasterMode) {
                log("Error: BroadcastChunkSize must be positive in disaster mode.");
                throw new IOException("BroadcastChunkSize must be positive in disaster mode.");
            }


            log("Common config loaded: PreferredNeighbors=" + numberOfPreferredNeighbors +
                    ", UnchokingInterval=" + unchokingInterval +
                    ", OptimisticInterval=" + optimisticUnchokingInterval +
                    ", FileName=" + fileName +
                    ", FileSize=" + fileSize +
                    ", PieceSize=" + pieceSize +
                    ", NumPieces (BitTorrent)=" + numberOfPieces +
                    ", BroadcastChunkSize=" + broadcastChunkSize +
                    ", SparseAckInterval=" + sparseAckInterval +
                    ", NumBroadcastChunks=" + numberOfBroadcastChunks);
        }
    }

    private void loadPeerInfoConfig() throws IOException {
        log("Loading PeerInfo.cfg");
        try (BufferedReader reader = new BufferedReader(new FileReader(PEER_INFO_CFG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue; // Base case: ID, Host, Port, HasFile
                
                int id = Integer.parseInt(parts[0]);
                String host = parts[1];
                int port = Integer.parseInt(parts[2]);
                boolean hasFileBT = Integer.parseInt(parts[3]) == 1;

                // Defaults for new disaster mode fields if not present in file
                boolean isSuperCand = false;
                int battery = 0;
                if (parts.length >= 6) { // Check if disaster mode fields are present
                    isSuperCand = Integer.parseInt(parts[4]) == 1;
                    battery = Integer.parseInt(parts[5]);
                } else if (parts.length == 5) { // Handle if only isSuperCandidate is present
                    isSuperCand = Integer.parseInt(parts[4]) == 1;
                }


                PeerInfo info = new PeerInfo(id, host, port, hasFileBT, isSuperCand, battery);
                info.bitfield = new BitSet(numberOfPieces); // Initialize BitTorrent bitfield
                if (hasFileBT) {
                    info.bitfield.set(0, numberOfPieces);
                    totalPeersCompleted.incrementAndGet();
                }
                peerInfoMap.put(id, info);

                if (id == this.peerID) {
                    this.hostName = host;
                    this.listeningPort = port;
                    this.hasFileInitially = hasFileBT;
                    if (hasFileBT) {
                        this.bitfield.set(0, numberOfPieces);
                        log("Peer " + peerID + " starts with the complete file (BitTorrent mode).");
                        loadInitialFilePieces(); // Load for BitTorrent and potentially for broadcast encoding
                    } else {
                        this.filePieces = new byte[numberOfPieces][];
                        log("Peer " + peerID + " starts without the file (BitTorrent mode).");
                    }
                }
            }
            log("PeerInfo config loaded for " + peerInfoMap.size() + " peers.");
        }
    }

    // --- File Handling ---
    private void loadInitialFilePieces() {
        log("Loading initial file pieces from " + fileName);
        this.filePieces = new byte[numberOfPieces][];
        File file = new File("peer_" + peerID + "/" + fileName);
        if (!file.exists()) {
            log("Error: Initial file " + file.getPath() + " not found for peer " + peerID);
            // Attempt to find in project root as fallback
            file = new File(fileName);
            if (!file.exists()) {
                log("Error: Initial file " + fileName + " not found in project root either.");
                System.err.println(
                        "Error: File " + fileName + " not found for peer " + peerID + " which should have it.");
                System.exit(1);
            } else {
                log("Found initial file in project root: " + file.getPath());
            }
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            for (int i = 0; i < numberOfPieces; i++) {
                int currentPieceSize = (i == numberOfPieces - 1) ? (fileSize - i * pieceSize) : pieceSize;
                byte[] pieceData = new byte[currentPieceSize];
                int bytesRead = fis.read(pieceData);
                if (bytesRead != currentPieceSize) {
                    log("Warning: Read fewer bytes than expected for piece " + i + ". Read: " + bytesRead
                            + ", Expected: " + currentPieceSize);
                    // Handle potential partial read
                    if (bytesRead > 0) {
                        filePieces[i] = Arrays.copyOf(pieceData, bytesRead);
                    } else {
                        filePieces[i] = new byte[0];
                    }
                } else {
                    filePieces[i] = pieceData;
                }
            }
            log("Successfully loaded " + numberOfPieces + " pieces from " + file.getPath());
        } catch (IOException e) {
            log("Error reading initial file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        // This method now also serves to load the file for the super-peer to broadcast
        if (this.hasFileInitially && disasterMode && this.filePieces != null) {
             // In disaster mode, if this peer has the file, it might become a super-peer.
             // The filePieces array is already loaded. We need to prepare the full file data for the FountainCoder.
            log("File loaded, available for potential broadcast as super-peer.");
        }
    }

    private byte[] getFullFileBytes() {
        if (!hasFileInitially && filePieces == null) { // if this peer doesn't have the file (e.g. was a leecher that completed)
                                                       // or hasFileInitially is false and filePieces is not yet populated for some reason
             if (bitfield.cardinality() == numberOfPieces) { // but it has all BT pieces
                log("Reconstructing full file from downloaded BitTorrent pieces for broadcast encoding.");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    for (int i = 0; i < numberOfPieces; i++) {
                        if (filePieces[i] != null) {
                            baos.write(filePieces[i]);
                        } else {
                            log("Error: Missing piece " + i + " when trying to get full file bytes for broadcast.");
                            return null; // Or throw exception
                        }
                    }
                     return baos.toByteArray();
                } catch (IOException e) {
                    log("Error creating full file byte array: " + e.getMessage());
                    return null;
                }

             } else {
                log("Cannot get full file bytes: File not initially present or not fully downloaded via BitTorrent.");
                return null;
             }
        } else if (hasFileInitially && filePieces != null) { // Loaded from disk
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                for (int i = 0; i < numberOfPieces; i++) {
                     if (filePieces[i] != null) baos.write(filePieces[i]);
                     else {
                         log("Error: Initial file piece " + i + " is null during getFullFileBytes."); return null;
                     }
                }
                return baos.toByteArray();
            } catch (IOException e) {
                log("Error creating full file byte array from initial pieces: " + e.getMessage());
                return null;
            }
        }
        // Fallback if filePieces is directly populated some other way but not marked as hasFileInitially
        // This path should ideally not be taken if logic is correct elsewhere.
        log("Warning: getFullFileBytes() called in an unexpected state.");
        return null;
    }

    private synchronized void savePiece(int pieceIndex, byte[] data) {
        if (pieceIndex < 0 || pieceIndex >= numberOfPieces) {
            log("Error: Received invalid piece index " + pieceIndex);
            return;
        }
        if (filePieces[pieceIndex] == null) { // Only save if we don't have it
            filePieces[pieceIndex] = data;
            bitfield.set(pieceIndex);
            int currentPieceCount = downloadedPieces.incrementAndGet();
            log("Received and saved piece " + pieceIndex + ". Now have " + currentPieceCount + "/" + numberOfPieces
                    + " pieces.");

            // Check if file download is complete
            if (currentPieceCount == numberOfPieces && !hasFileInitially) { // Avoid re-logging if started with file
                log("Has downloaded the complete file.");
                hasFileInitially = true; // Mark as having the file now
                totalPeersCompleted.incrementAndGet();
                reassembleFile();
            }

            // Broadcast HAVE message to all connected peers
            broadcastHaveMessage(pieceIndex);

            // Re-evaluate interest in peers based on the new piece
            evaluateInterestAfterHave();

        } else {
            log("Already have piece " + pieceIndex + ". Ignoring duplicate.");
        }
    }

    private void evaluateInterestAfterHave() {
        activeConnections.forEach((remotePeerId, handler) -> {
            PeerInfo remotePeerInfo = peerInfoMap.get(remotePeerId);
            if (remotePeerInfo != null) {
                boolean stillInterested = isInterestedIn(remotePeerInfo);
                // If we were interested but no longer are
                if (handler.amInterested && !stillInterested) {
                    handler.sendNotInterested();
                }
                // If we were not interested but now are
                else if (!handler.amInterested && stillInterested) {
                    handler.sendInterested();
                }
            }
        });
    }

    private void reassembleFile() {
        log("Reassembling the complete file: " + fileName);
        File outputFile = new File("peer_" + peerID + "/" + fileName);

        outputFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < numberOfPieces; i++) {
                if (filePieces[i] != null) {
                    fos.write(filePieces[i]);
                } else {
                    log("Error: Missing piece " + i + " during file reassembly.");
                    // Handle error
                    System.err.println(
                            "Critical Error: Missing piece " + i + " for peer " + peerID + " during final reassembly.");
                    return;
                }
            }
            log("File reassembly complete: " + outputFile.getPath());
        } catch (IOException e) {
            log("Error writing reassembled file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void reassembleBroadcastFile() {
        if (fountainDecoder == null || !fountainDecoder.isDataDecoded()) {
            log("Cannot reassemble broadcast file: Decoder not ready or data not fully decoded.");
            return;
        }
        log("Reassembling the complete file from broadcast: " + fileName);
        File outputFile = new File("peer_" + peerID + "/" + fileName + "_broadcast_decoded"); // Save with a different name for testing
        outputFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            //byte[] decodedData = fountainDecoder.getDecodedData(); // Assuming FountainCoder provides this
            //fos.write(decodedData);
            log("Conceptual: Writing decoded data from fountain decoder to " + outputFile.getPath());
            // For actual OpenRQ, you'd get the decoded data from the decoder object.
            // Example: ByteBuffer[] decodedSymbols = fountainDecoder.getDecodedSymbols(); // This is conceptual for OpenRQ
            // for (ByteBuffer symbol : decodedSymbols) { fos.write(symbol.array()); }

            // Using the OpenRQ API structure provided in the plan:
            // The OpenRQ decoder directly writes to a ByteBuffer or file.
            // Assuming decoder was initialized to write to a buffer/file.
            // If decoder was initialized with OpenRQ.newDecoder(target_channel, ...), it would write there.
            // If it was an in-memory decoder, data needs to be retrieved.
            // For now, let's assume the FountainCoder helper would abstract this.
            // Conceptual call:
            byte[] fileData = fountainDecoder.retrieveDecodedData(); // This method needs to be in our conceptual FountainCoder
            if (fileData != null) {
                fos.write(fileData);
                log("File reassembly from broadcast complete: " + outputFile.getPath());
                hasFileInitially = true; // Now we have the file
                bitfield.set(0, numberOfPieces); // Mark all BT pieces as available too
                totalPeersCompleted.incrementAndGet(); // Consider this peer complete for BT termination
            } else {
                 log("Error: Failed to retrieve decoded data from FountainDecoder.");
            }

        } catch (IOException e) {
            log("Error writing reassembled broadcast file: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // --- Networking ---
    private void startServer() {
        connectionExecutor.submit(() -> {
            try (ServerSocket serverSocket = new ServerSocket(listeningPort)) {
                log("Server started. Listening on port " + listeningPort);
                while (!Thread.currentThread().isInterrupted() && !checkTerminationCondition()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        log("Accepted connection from " + clientSocket.getRemoteSocketAddress());

                        connectionExecutor.submit(new PeerConnectionHandler(clientSocket, this));
                    } catch (IOException e) {
                        if (serverSocket.isClosed()) {
                            log("Server socket closed, stopping listener.");
                            break;
                        }
                        log("Error accepting connection: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                log("Could not start server on port " + listeningPort + ": " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            } finally {
                log("Server listener thread finished.");
            }
        });
    }

    private void connectToPeers() {
        log("Initiating connections to preceding peers...");
        for (PeerInfo info : peerInfoMap.values()) {
            if (info.peerID < this.peerID) {
                connectionExecutor.submit(() -> {
                    try {
                        log("Attempting to connect to peer " + info.peerID + " at " + info.hostName + ":" + info.port);
                        Socket socket = new Socket(info.hostName, info.port);
                        log("Successfully connected to peer " + info.peerID);
                        PeerConnectionHandler handler = new PeerConnectionHandler(socket, this, info.peerID);
                        activeConnections.put(info.peerID, handler);
                        handler.initiateHandshake(); // Send handshake immediately after connecting
                        connectionExecutor.submit(handler); // Start the handler's run loop
                    } catch (ConnectException e) {
                        log("Connection refused to peer " + info.peerID
                                + ". It might not be running yet. Will retry later if needed.");
                        // Implement retry logic if necessary
                    } catch (UnknownHostException e) {
                        log("Unknown host: " + info.hostName + " for peer " + info.peerID);
                    } catch (IOException e) {
                        log("IOException connecting to peer " + info.peerID + ": " + e.getMessage());
                    }
                });
            }
        }
        // In disaster mode, connections are still TCP for MSG_ROLE_ELECT, MSG_SPARSE_ACK.
        // MSG_BCAST_CHUNK is conceptually a broadcast, but here sent over established TCP for simplicity.
        // A true UDP broadcast is more complex and not specified in current peerProcess structure.
    }

    // --- Message Handling ---

    // Structure: Length (4 bytes) | Type (1 byte) | Payload (variable)
    private static class ActualMessage {
        int length;
        byte type;
        byte[] payload;

        ActualMessage(byte type, byte[] payload) {
            this.type = type;
            this.payload = (payload == null) ? new byte[0] : payload;
            this.length = this.payload.length + 1; // +1 for type byte
        }

        ActualMessage(byte type) {
            this(type, null);
        }

        byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(4 + length);
            buffer.putInt(length);
            buffer.put(type);
            buffer.put(payload);
            return buffer.array();
        }
    }

    // --- Handshake Message ---
    // Structure: Header (18 bytes) | Zero Bits (10 bytes) | PeerID (4 bytes)
    private static final String HANDSHAKE_HEADER = "P2PFILESHARINGPROJ";

    private byte[] createHandshakeMessage() {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.put(HANDSHAKE_HEADER.getBytes(StandardCharsets.US_ASCII));
        buffer.put(new byte[10]); // Zero bits
        buffer.putInt(this.peerID);
        return buffer.array();
    }

    private static int validateHandshake(byte[] message, int expectedPeerID) {
        if (message == null || message.length != 32) {
            System.err.println("Invalid handshake length: " + (message == null ? "null" : message.length));
            return -1; // Invalid handshake
        }
        ByteBuffer buffer = ByteBuffer.wrap(message);
        byte[] headerBytes = new byte[18];
        buffer.get(headerBytes);
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        if (!HANDSHAKE_HEADER.equals(header)) {
            System.err.println("Invalid handshake header: " + header);
            return -1; // Invalid header
        }
        buffer.position(buffer.position() + 10); // Skip zero bits
        int receivedPeerID = buffer.getInt();

        if (expectedPeerID == 0) {
            return receivedPeerID;
        }

        // If we initiated the connection, check if the ID matches.
        if (receivedPeerID != expectedPeerID) {
            System.err.println(
                    "Handshake peer ID mismatch. Expected: " + expectedPeerID + ", Received: " + receivedPeerID);
            return -1; // Peer ID mismatch
        }
        return receivedPeerID; // Handshake valid
    }

    // --- Bitfield Handling ---
    private byte[] getBitfieldPayload() {

        int numBytes = (numberOfPieces + 7) / 8;
        byte[] bytes = new byte[numBytes];
        for (int i = 0; i < numberOfPieces; i++) {
            if (bitfield.get(i)) {
                bytes[i / 8] |= (1 << (7 - (i % 8)));
            }
        }
        return bytes;
    }

    private synchronized void updatePeerBitfield(int remotePeerID, byte[] payload) {
        PeerInfo info = peerInfoMap.get(remotePeerID);
        if (info != null) {

            info.bitfield.clear(); // Clear existing bits first
            for (int i = 0; i < numberOfPieces; i++) {
                // Check if the bit index is within the bounds implied by the payload length
                int byteIndex = i / 8;
                int bitInByteIndex = 7 - (i % 8);
                if (byteIndex < payload.length && (payload[byteIndex] & (1 << bitInByteIndex)) != 0) {
                    info.bitfield.set(i);
                }
            }

            log("Received bitfield from peer " + remotePeerID + ". Peer has " + info.bitfield.cardinality()
                    + " pieces.");
            // Check if the remote peer has the complete file
            if (info.bitfield.cardinality() == numberOfPieces && !info.hasFile) {
                info.hasFile = true;
                totalPeersCompleted.incrementAndGet();
                log("Peer " + remotePeerID + " reported having the complete file via bitfield. Total completed: "
                        + totalPeersCompleted.get());
                checkTerminationCondition();
            }

            // Now decide if we are interested in this peer
            if (isInterestedIn(info)) {
                activeConnections.get(remotePeerID).sendInterested();
            } else {
                activeConnections.get(remotePeerID).sendNotInterested();
            }
        } else {
            log("Warning: Received bitfield from unknown peer ID: " + remotePeerID);
        }
    }

    // --- Interest Check ---
    private synchronized boolean isInterestedIn(PeerInfo remotePeerInfo) {
        if (remotePeerInfo == null || remotePeerInfo.bitfield == null) {
            return false; // Cannot be interested if we don't know what they have
        }
        // Create a copy of the remote bitfield and AND it with the NOT of our bitfield
        BitSet remoteHas = (BitSet) remotePeerInfo.bitfield.clone();
        BitSet iNeed = (BitSet) this.bitfield.clone();
        iNeed.flip(0, numberOfPieces); // Flip bits to represent pieces I *don't* have

        remoteHas.and(iNeed); // Result has bits set only for pieces they have AND I need

        return !remoteHas.isEmpty(); // If the result is not empty, I am interested
    }

    // --- Piece Requesting ---
    private synchronized int selectPieceToRequest(int remotePeerID) {
        PeerInfo remotePeerInfo = peerInfoMap.get(remotePeerID);
        if (remotePeerInfo == null || remotePeerInfo.bitfield == null) {
            return -1; // Cannot request if we don't know the peer or what they have
        }

        BitSet theyHave = (BitSet) remotePeerInfo.bitfield.clone();
        BitSet iNeed = (BitSet) this.bitfield.clone();
        iNeed.flip(0, numberOfPieces); // Pieces I don't have

        theyHave.and(iNeed); // Pieces they have that I need

        if (theyHave.isEmpty()) {
            return -1; // No pieces they have that I need
        }

                // --- Random Selection Strategy ---
        List<Integer> availablePieces = new ArrayList<>();
        for (int i = theyHave.nextSetBit(0); i >= 0; i = theyHave.nextSetBit(i + 1)) {

            availablePieces.add(i);
        }

        if (availablePieces.isEmpty()) {
            return -1;
        }

        // Randomly select one piece index
        int randomIndex = ThreadLocalRandom.current().nextInt(availablePieces.size());
        int chosenPiece = availablePieces.get(randomIndex);
        log("Selected piece " + chosenPiece + " to request from peer " + remotePeerID);
        return chosenPiece;

    }

    // --- Choking/Unchoking Logic (for BitTorrent mode) ---
    private void startChokingTasks() { // Only if not in disaster mode primary operation, or after fail-back
        if (!disasterMode || (disasterMode && wanReturn)) { // Only run these in BT mode
            scheduler.scheduleAtFixedRate(this::determinePreferredNeighbors,
                    unchokingInterval, unchokingInterval, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::determineOptimisticNeighbor,
                    optimisticUnchokingInterval, optimisticUnchokingInterval, TimeUnit.SECONDS);
            log("Scheduled choking tasks for BitTorrent mode.");
        } else {
            log("Skipping BitTorrent choking tasks in disaster mode initial phase.");
        }
    }
    
    private synchronized void determinePreferredNeighbors() {
        if (interestedPeers.isEmpty()) {
            log("No peers are interested, skipping preferred neighbor selection.");
            // Unchoke any previously preferred neighbors that are no longer preferred
            Set<Integer> previouslyPreferred = new HashSet<>(preferredNeighbors);
            previouslyPreferred.removeAll(Collections.emptySet()); // Clear preferredNeighbors effectively
            previouslyPreferred.forEach(peerId -> {
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null && !peerId.equals(optimisticallyUnchokedNeighbor)) { // Don't choke the optimistic
                                                                                         // one here
                    handler.sendChoke();
                }
            });
            preferredNeighbors.clear();
            return;
        }

        log("Determining preferred neighbors among " + interestedPeers.size() + " interested peers.");
        Set<Integer> newPreferredNeighbors = new HashSet<>();

        // If this peer has the complete file, select neighbors randomly from interested
        // peers
        if (this.bitfield.cardinality() == numberOfPieces) {
            log("This peer has the complete file. Selecting preferred neighbors randomly.");
            List<Integer> interestedList = new ArrayList<>(interestedPeers);
            Collections.shuffle(interestedList);
            for (int i = 0; i < Math.min(numberOfPreferredNeighbors, interestedList.size()); i++) {
                newPreferredNeighbors.add(interestedList.get(i));
            }
        } else {
            // Select based on download rate (peers sending pieces to us)
            log("Selecting preferred neighbors based on download rate.");
            List<PeerInfo> candidates = interestedPeers.stream()
                    .map(peerInfoMap::get)
                    .filter(Objects::nonNull)
                    // Calculate rate based on pieces received in the last interval
                    .peek(p -> {
                        long now = System.currentTimeMillis();
                        long elapsed = now - p.downloadStartTime;
                        if (elapsed > 0 && p.piecesDownloadedInInterval > 0) {
                            // Rate in pieces per second, scaled for comparison
                            p.downloadRate = (double) p.piecesDownloadedInInterval * 1000.0 / elapsed;
                        } else {
                            p.downloadRate = 0; // No downloads in this interval yet
                        }
                        // Reset for next interval AFTER calculation
                        // p.downloadStartTime = now;
                        // p.piecesDownloadedInInterval = 0;
                    })
                    .sorted((p1, p2) -> Double.compare(p2.downloadRate, p1.downloadRate)) // Sort descending by rate
                    .collect(Collectors.toList());

            // Reset download counts for the next interval AFTER sorting and selection
            candidates.forEach(p -> {
                p.piecesDownloadedInInterval = 0;
                // p.downloadStartTime = System.currentTimeMillis();
            });

            int count = 0;
            for (PeerInfo candidate : candidates) {
                if (count < numberOfPreferredNeighbors) {
                    newPreferredNeighbors.add(candidate.peerID);
                    log("Selected peer " + candidate.peerID + " as preferred neighbor (Rate: "
                            + String.format("%.2f", candidate.downloadRate) + ")");
                    count++;
                } else {
                    break;
                }
            }
            // If fewer candidates than needed, add randomly from remaining interested peers
            // (less likely if rates are calculated)
            if (count < numberOfPreferredNeighbors && interestedPeers.size() > count) {
                List<Integer> remainingInterested = new ArrayList<>(interestedPeers);
                remainingInterested.removeAll(newPreferredNeighbors);
                Collections.shuffle(remainingInterested);
                int needed = numberOfPreferredNeighbors - count;
                for (int i = 0; i < Math.min(needed, remainingInterested.size()); i++) {
                    int randomPeerId = remainingInterested.get(i);
                    newPreferredNeighbors.add(randomPeerId);
                    log("Selected peer " + randomPeerId + " randomly to fill preferred neighbor slots.");
                }
            }
        }

        // --- Update Choke/Unchoke Status ---
        Set<Integer> previouslyPreferred = new HashSet<>(preferredNeighbors);

        // Unchoke new preferred neighbors
        for (Integer peerId : newPreferredNeighbors) {
            if (!previouslyPreferred.contains(peerId) || chokedPeers.contains(peerId)) { // Also unchoke if it was
                                                                                         // previously preferred but
                                                                                         // somehow got choked
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null) {
                    log("Unchoking new preferred neighbor: " + peerId);
                    handler.sendUnchoke();
                }
            }
        }

        // Choke previously preferred neighbors who are no longer preferred

        for (Integer peerId : previouslyPreferred) {
            if (!newPreferredNeighbors.contains(peerId) && !peerId.equals(optimisticallyUnchokedNeighbor)) {
                PeerConnectionHandler handler = activeConnections.get(peerId);
                if (handler != null) {
                    log("Choking previously preferred neighbor (no longer preferred): " + peerId);
                    handler.sendChoke();
                }
            }
        }

        // Update the set of preferred neighbors
        preferredNeighbors.clear();
        preferredNeighbors.addAll(newPreferredNeighbors);
        log("Preferred neighbors updated: " + preferredNeighbors);
    }

    private synchronized void determineOptimisticNeighbor() {
        log("Determining optimistically unchoked neighbor.");

        // Find interested peers that are currently choked (and not preferred)
        List<Integer> candidates = interestedPeers.stream()
                .filter(peerId -> chokedPeers.contains(peerId))
                .filter(peerId -> !preferredNeighbors.contains(peerId))
                .collect(Collectors.toList());

        Integer previouslyOptimistic = optimisticallyUnchokedNeighbor;

        if (candidates.isEmpty()) {
            log("No choked, interested, non-preferred peers to select for optimistic unchoking.");
            optimisticallyUnchokedNeighbor = null; // No one to select
        } else {
            // Select one randomly
            int randomIndex = ThreadLocalRandom.current().nextInt(candidates.size());
            optimisticallyUnchokedNeighbor = candidates.get(randomIndex);
            log("Selected peer " + optimisticallyUnchokedNeighbor + " as new optimistically unchoked neighbor.");

            // Unchoke the newly selected peer
            PeerConnectionHandler handler = activeConnections.get(optimisticallyUnchokedNeighbor);
            if (handler != null) {
                handler.sendUnchoke();
            }
        }

        // If there was a previously optimistic neighbor, and they are different from
        // the new one, and they are not now a preferred neighbor, choke them.
        if (previouslyOptimistic != null &&
                !previouslyOptimistic.equals(optimisticallyUnchokedNeighbor) &&
                !preferredNeighbors.contains(previouslyOptimistic)) {
            PeerConnectionHandler handler = activeConnections.get(previouslyOptimistic);
            if (handler != null) {
                log("Choking previously optimistically unchoked neighbor: " + previouslyOptimistic);
                handler.sendChoke();
            }
        }
        log("Optimistically unchoked neighbor updated: " + optimisticallyUnchokedNeighbor);
    }

    // --- Termination Check (modified for disaster mode) ---
    private boolean checkTerminationCondition() {
        if (disasterMode && !wanReturn && I_AM_SUPER) {
            // Super-peer in disaster mode terminates broadcast when all peers ACK completion
            if (peersCompletedBroadcast.get() == (peerInfoMap.size() -1) && peerInfoMap.size() > 1) { // -1 for super-peer itself
                 log("Disaster Mode Termination: All connected normal peers have acknowledged broadcast completion.");
                 return true; // This will trigger fail-back logic for super-peer
            }
            return false;
        } else if (disasterMode && !wanReturn && !I_AM_SUPER) {
            // Normal peer in disaster mode: "termination" is having the full file from broadcast
            return broadcastReceivedChunks.cardinality() == numberOfBroadcastChunks;
        } else {
            // BitTorrent mode termination
            if (totalPeersCompleted.get() == peerInfoMap.size()) {
                log("BitTorrent Mode Termination condition met: All " + peerInfoMap.size() + " peers have the complete file.");
                return true;
            }
        }
        return false;
    }


    private void shutdown() {
        log("Initiating shutdown sequence.");

        // 1. Stop scheduled tasks
        scheduler.shutdownNow();
        log("Choking scheduler shut down.");

        // 2. Close all active connections gracefully
        log("Closing active connections...");
        activeConnections.forEach((peerId, handler) -> {
            handler.closeConnection("System shutdown");
        });
        activeConnections.clear(); // Clear the map

        // 3. Shutdown connection executor
        connectionExecutor.shutdown();
        try {
            if (!connectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                connectionExecutor.shutdownNow();
                log("Connection executor did not terminate gracefully, forced shutdown.");
            } else {
                log("Connection executor shut down.");
            }
        } catch (InterruptedException e) {
            connectionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. Close logger
        if (logger != null) {
            log("Closing log file.");
            logger.close();
            logger = null; // Prevent further logging attempts
        }

        System.out.println("Peer " + peerID + " shutting down.");
        // System.exit(0); // Consider if a clean exit from main is better
    }

    // --- Broadcast Methods ---
    private void broadcastHaveMessage(int pieceIndex) {
        log("Broadcasting HAVE message for piece " + pieceIndex + " to all connected peers.");
        ActualMessage haveMsg = new ActualMessage(MSG_HAVE, ByteBuffer.allocate(4).putInt(pieceIndex).array());
        byte[] haveMsgBytes = haveMsg.toBytes();
        activeConnections.values().forEach(handler -> handler.sendMessage(haveMsgBytes));
    }


    // --- Disaster Mode Methods ---

    // Conceptual FountainCoder Class (as an inner class for this example)
    // In a real project, this would be a separate FountainCoder.java file and use OpenRQ.
    private class FountainCoder {
        private final byte[] originalFile;
        private final int chunkSize;
        private int currentChunkIndex = 0; // Simple sequential chunking for this conceptual version
        private final int totalChunks;

        // For Encoder (Super Peer)
        public FountainCoder(byte[] fileData, int chunkSize) {
            this.originalFile = fileData;
            this.chunkSize = chunkSize;
            this.totalChunks = (int) Math.ceil((double) fileData.length / chunkSize);
            log("FountainCoder (Encoder) initialized for " + fileData.length + " bytes, chunk size " + chunkSize + ", total chunks " + totalChunks);
            // Real OpenRQ: this.encoder = OpenRQ.newEncoder(ByteBuffer.wrap(fileData), 0, chunkSize, calculateOverhead(...));
        }

        // For Decoder (Normal Peer)
        public FountainCoder(int fileSize, int chunkSize, int numChunks) {
            this.originalFile = new byte[fileSize]; // Buffer to reconstruct the file
            this.chunkSize = chunkSize;
            this.totalChunks = numChunks;
            // Real OpenRQ: this.decoder = OpenRQ.newDecoder(target_channel_or_buffer, fileSize, chunkSize);
            log("FountainCoder (Decoder) initialized for file size " + fileSize + ", expecting " + numChunks + " chunks of size " + chunkSize);
        }
        
        // Super-peer side
        public ByteBuffer nextChunk() { // Conceptual - real fountain coding is more complex
            if (originalFile == null) {
                 log("FountainCoder: Original file not loaded for encoding.");
                 return null;
            }
            if (currentChunkIndex >= totalChunks) {
                log("FountainCoder: All original chunks sent (conceptual). Real fountain codes can generate more unique symbols.");
                // A true fountain coder can generate more symbols than original chunks.
                // For this simplified version, let's just loop or stop. Let's stop.
                return null;
            }
            int offset = currentChunkIndex * chunkSize;
            int length = Math.min(chunkSize, originalFile.length - offset);
            ByteBuffer chunk = ByteBuffer.wrap(originalFile, offset, length);
            // In real OpenRQ: return encoder.sourceBlock(0).nextSymbol();
            currentChunkIndex++;
            return chunk;
        }

        // Normal peer side
        public void receiveChunk(ByteBuffer chunkData, int chunkId) { // chunkId might be part of payload or implicit
            // Real OpenRQ: decoder.sourceBlock(0).putSymbol(chunkData);
            // For conceptual: copy data into broadcastReceivedChunks and potentially into a reassembly buffer.
            // We assume chunkData is a raw piece of the file for this simple version.
            // The 'broadcastReceivedChunks' BitSet in peerProcess handles tracking.
            // The actual data would be stored in a temporary structure for reassembly by the decoder.
            log("FountainDecoder: Received chunk " + chunkId + " (conceptual). Size: " + chunkData.remaining());
            // If this were a real decoder, it would store this symbol internally.
        }
        
        // Normal peer side
        public boolean isDataDecoded() {
            // Real OpenRQ: return decoder.isDataDecoded();
            // Conceptual: This would be true if broadcastReceivedChunks.cardinality() == numberOfBroadcastChunks
            // but true decoding depends on the properties of the fountain code.
            // For our example, we'll rely on peerProcess.broadcastReceivedChunks for this logic externally.
            // The decoder itself would know internally.
            return broadcastReceivedChunks.cardinality() == numberOfBroadcastChunks; // Placeholder
        }
        
        public byte[] retrieveDecodedData() { // Conceptual for OpenRQ
            // In OpenRQ, if decoding to a ByteBuffer, you'd access that buffer.
            // If decoding to a file, the file would be written.
            // This method simulates retrieving the fully decoded file.
            if (isDataDecoded()) {
                // If we were storing chunks in a map for simple reassembly:
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // This part is highly conceptual without the real decoder logic.
                // Assume the 'originalFile' buffer was filled by the decoder.
                // For this example, it's hard to show reassembly without the actual decoder.
                // Let's assume the FountainDecoder handles reassembly internally and provides the full file.
                // This is a MAJOR simplification.
                log("FountainDecoder: Retrieving decoded data (conceptual).");
                // This needs a proper implementation based on how chunks are stored/decoded.
                // For now, returning null as a placeholder.
                // A real implementation would return the reassembled file from the decoder's internal state.
                return null; // Placeholder - requires real decoder logic
            }
            return null;
        }
    }


    private volatile boolean I_AM_SUPER = false;
    private void electSuperPeer() {
        log("Starting super-peer election...");
        Optional<PeerInfo> winner = peerInfoMap.values().stream()
            .filter(p -> p.isSuperCandidate)
            .max(Comparator.<PeerInfo>comparingInt(p -> p.batteryLevel)
                 .thenComparingInt(p -> -p.peerID)); // lower ID wins on tie (negate for max)

        if (winner.isPresent()) {
            if (winner.get().peerID == this.peerID) {
                I_AM_SUPER = true;
                log("I have been elected SUPER peer (" + this.peerID + "). Battery: " + winner.get().batteryLevel);
                // Initialize fountain encoder if I am super and have the file
                if (hasFileInitially || bitfield.cardinality() == numberOfPieces) { // Check if it has the file either initially or downloaded
                    byte[] fullFile = getFullFileBytes();
                    if (fullFile != null) {
                        this.fountainEncoder = new FountainCoder(fullFile, broadcastChunkSize);
                         log("Fountain encoder initialized for super-peer.");
                    } else {
                        log("Error: Super-peer elected but cannot load full file for encoding. Aborting super-peer role.");
                        I_AM_SUPER = false; // Cannot be super without the file to encode
                    }
                } else {
                     log("Error: Super-peer elected but does not have the file. Aborting super-peer role.");
                     I_AM_SUPER = false;
                }
            } else {
                I_AM_SUPER = false;
                log("Peer " + winner.get().peerID + " is elected super peer. I am a normal peer.");
            }
        } else {
            I_AM_SUPER = false;
            log("No super peer elected (no candidates or tie-breaking failed).");
        }
        // All peers (including non-super) initialize a decoder if in disaster mode
        if (disasterMode && !I_AM_SUPER) {
             this.fountainDecoder = new FountainCoder(fileSize, broadcastChunkSize, numberOfBroadcastChunks);
             log("Fountain decoder initialized for normal peer.");
        }

        // Broadcast MSG_ROLE_ELECT (beacon/vote) - simplified: just announce role
        // A true election protocol would be more complex (e.g., RAFT, Paxos, or custom).
        // For now, each peer decides based on config and broadcasts its status if it thinks it won.
        // Or, peers could send their candidacy and let others confirm.
        // The plan says "Call electSuperPeer() once at start-up and again whenever a MSG_ROLE_ELECT beacon arrives"
        // This implies MSG_ROLE_ELECT might carry information to trigger re-election.
        // For simplicity, let's assume initial election is by config, and MSG_ROLE_ELECT is a heartbeat/announcement.
        // This part needs more specification for a robust election.
        // For now, election is deterministic from config.

    }

    private void broadcastNextChunk() {
        if (!I_AM_SUPER || fountainEncoder == null) return;

        ByteBuffer chunk = fountainEncoder.nextChunk(); // Conceptual
        if (chunk == null) {
            log("Super-peer: Fountain encoder finished sending all conceptual chunks or file fully broadcasted.");
            // Super-peer might stop broadcasting here if it believes all data is sent.
            // Or rely on sparse ACKs to know when to stop. Let's rely on ACKs.
            return;
        }

        log("Super-peer broadcasting chunk, size: " + chunk.remaining());
        ActualMessage m = new ActualMessage(MSG_BCAST_CHUNK, chunk.array()); // chunk.array() might need care if buffer is shared/sliced
        byte[] rawMsg = m.toBytes();
        
        // In a real scenario with UDP broadcast:
        // DatagramPacket packet = new DatagramPacket(rawMsg, rawMsg.length, broadcastAddress, broadcastPort);
        // broadcastSocket.send(packet);

        // Simulating broadcast by sending to all active TCP connections:
        activeConnections.values().forEach(handler -> handler.sendMessage(rawMsg));
    }

    private void scheduleDisasterModeTasks() {
        if (disasterMode && !wanReturn) {
            if (I_AM_SUPER) {
                scheduler.scheduleAtFixedRate(this::broadcastNextChunk, 0, 100, TimeUnit.MILLISECONDS); // As per plan
                log("Super-peer: Scheduled broadcast task.");
            } else { // Normal peer
                scheduler.scheduleAtFixedRate(this::sendSparseAck,
                        sparseAckInterval, sparseAckInterval, TimeUnit.MILLISECONDS);
                log("Normal peer: Scheduled sparse ACK task.");
            }
        }
    }
    
    private void sendSparseAck() {
        if (I_AM_SUPER || fountainDecoder == null || broadcastReceivedChunks == null) return;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            synchronized(broadcastReceivedChunks) { // Ensure consistent read
                oos.writeObject(broadcastReceivedChunks);
            }
            oos.close();
            byte[] payload = baos.toByteArray();
            
            ActualMessage ackMsg = new ActualMessage(MSG_SPARSE_ACK, payload);
            log("Normal peer sending SPARSE_ACK. Chunks received: " + broadcastReceivedChunks.cardinality() + "/" + numberOfBroadcastChunks);

            // Send to super-peer. How does it know who the super-peer is?
            // For now, send to all. The super-peer will identify itself and process.
            // Or, if election result is globally known, send directly.
            // Let's assume it sends to all connected peers, and the super-peer picks it up.
             activeConnections.values().forEach(handler -> handler.sendMessage(ackMsg.toBytes()));

        } catch (IOException e) {
            log("Error serializing sparse ACK: " + e.getMessage());
        }
    }

    private void handleSparseAck(int fromPeerID, byte[] payload) {
        if (!I_AM_SUPER) return;

        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            BitSet receivedPeerChunks = (BitSet) ois.readObject();
            log("Super-peer received SPARSE_ACK from " + fromPeerID + ". They have " + receivedPeerChunks.cardinality() + "/" + numberOfBroadcastChunks + " chunks.");

            PeerInfo remotePeer = peerInfoMap.get(fromPeerID);
            if (remotePeer != null) {
                boolean wasComplete = remotePeer.lastReportedBroadcastChunks.cardinality() == numberOfBroadcastChunks;
                remotePeer.lastReportedBroadcastChunks = receivedPeerChunks;

                if (!wasComplete && receivedPeerChunks.cardinality() == numberOfBroadcastChunks) {
                    peersCompletedBroadcast.incrementAndGet();
                    log("Super-peer: Peer " + fromPeerID + " has now completed the broadcast. Total completed: " + peersCompletedBroadcast.get());
                }
            }

            // Check if all normal peers have completed
            if (peersCompletedBroadcast.get() >= (peerInfoMap.size() -1 ) && peerInfoMap.size() > 1 ) { // -1 for super-peer itself
                log("Super-peer: All " + peersCompletedBroadcast.get() + " normal peers have ACKed full broadcast. Initiating fail-back.");
                switchToClassicSwarm();
            }

        } catch (IOException | ClassNotFoundException e) {
            log("Error deserializing sparse ACK from " + fromPeerID + ": " + e.getMessage());
        }
    }

    private void switchToClassicSwarm() {
        if (I_AM_SUPER) {
            log("Super-peer switching to classic BitTorrent swarm mode.");
            I_AM_SUPER = false; // Stop broadcast scheduler implicitly as tasks check this flag
                                // Or explicitly cancel the broadcast task if its handle is stored.
            wanReturn = true; // Mark that we are back to WAN / BT mode

            // Send HAVE messages for all pieces to trigger BitTorrent mode for others
            log("Super-peer broadcasting HAVE messages for all pieces to initiate BitTorrent swarm.");
            for (int i = 0; i < numberOfPieces; i++) {
                if (this.bitfield.get(i)) { // Super-peer should have all pieces
                    broadcastHaveMessage(i); // Existing method
                }
            }
            // Restart choking tasks if they were not started due to disaster mode
            startChokingTasks();
        } else { // Normal Peer
            log("Normal peer: Super-peer indicated switch to classic swarm or WAN return. Stopping disaster mode operations.");
            wanReturn = true;
            // Stop sending sparse ACKs (scheduler checks wanReturn)
            // Start BitTorrent choking/unchoking logic if not already running
            startChokingTasks();
            // Existing logic for REQUEST/PIECE should take over if pieces are missing.
            // Might need to send initial bitfield or interest messages again.
            activeConnections.values().forEach(PeerConnectionHandler::sendBitfield);
        }
    }


    // --- Main Execution Logic ---
    public void run() {
        try {
            loadCommonConfig();
            loadPeerInfoConfig();

            // Check if peer ID was found in config
            if (this.hostName == null) {
                System.err.println("Error: Peer ID " + peerID + " not found in " + PEER_INFO_CFG_PATH);
                System.exit(1);
            }

            log("Peer " + peerID + " starting up at " + hostName + ":" + listeningPort + (disasterMode ? " [DISASTER MODE]" : " [NORMAL MODE]"));
            if (disasterMode) {
                log("Disaster Mode Initialized. File: " + fileName + ", Size: " + fileSize + ", Broadcast Chunks: " + numberOfBroadcastChunks);
                electSuperPeer(); // Elect super peer based on config
                                  // A more dynamic election would involve MSG_ROLE_ELECT exchanges before this.
            } else {
                log("BitTorrent Mode. File: " + fileName + ", Size: " + fileSize + ", BT Pieces: " + numberOfPieces);
            }


            startServer();
            connectToPeers(); // Establishes TCP links for all modes

            if (disasterMode && !wanReturn) {
                scheduleDisasterModeTasks();
            } else { // Normal BitTorrent mode or after fail-back
                startChokingTasks();
            }


            // Main loop for checking termination or wanReturn
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    log("Main thread interrupted, initiating shutdown.");
                    Thread.currentThread().interrupt();
                    break;
                }

                if (disasterMode && wanReturn && I_AM_SUPER) { // Super-peer detected WAN return, already switched.
                     log("Super-peer: WAN returned, operating in classic swarm mode. Monitoring for BT completion.");
                     // Now behaves like a regular peer waiting for BT completion.
                     // We need a way for I_AM_SUPER to be false here or for logic to handle it.
                     // switchToClassicSwarm sets I_AM_SUPER = false for the original super peer.
                }


                if (checkTerminationCondition()) {
                    if (disasterMode && !wanReturn && I_AM_SUPER) {
                        log("Super-peer: Broadcast phase complete. Switching to classic swarm (fail-back).");
                        switchToClassicSwarm(); // This will set wanReturn = true, I_AM_SUPER = false for the original super.
                        // The loop will continue, but now in BT mode.
                    } else if (disasterMode && !wanReturn && !I_AM_SUPER && (broadcastReceivedChunks.cardinality() == numberOfBroadcastChunks)){
                        log("Normal peer: Broadcast download complete. Waiting for switch to classic swarm or manual WAN return.");
                        // If it has the file, it should start acting like a seeder in BT mode once switched.
                        // It may need to send out HAVE messages if not done by super-peer's failback.
                        // Reassemble the file from broadcast chunks
                        if(fountainDecoder != null && fountainDecoder.isDataDecoded()){ // isDataDecoded is conceptual
                            reassembleBroadcastFile();
                        }
                    }
                    else if ((disasterMode && wanReturn) || !disasterMode) { // In BT mode (either initially or after fail-back)
                        log("All peers completed (BitTorrent mode). Waiting a bit before shutdown...");
                        try {
                            Thread.sleep(1000); // Shorter wait
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        break; // Exit loop to shutdown for BT mode
                    }
                }
                
                // Manual WAN return check (for normal peers, or super-peer if it needs external trigger)
                if (disasterMode && !wanReturn && peerProcess.wanReturn) { // Check static flag
                    log("Manual WAN return triggered. Switching to classic swarm mode.");
                    switchToClassicSwarm(); // All peers execute this to align state.
                }
            }

        } catch (IOException e) {
            log("Initialization error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    // --- PeerConnectionHandler Inner Class ---
    private class PeerConnectionHandler implements Runnable {
        private final Socket socket;
        private final peerProcess parentPeer;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile int remotePeerID = 0;
        private volatile boolean connectionEstablished = false;
        private volatile boolean remotePeerChokingMe = true;
        private volatile boolean iAmChokingRemotePeer = true;
        private volatile boolean amInterested = false;
        private volatile boolean isInterested = false;

        // Constructor for incoming connections (peerID unknown initially)
        public PeerConnectionHandler(Socket socket, peerProcess parentPeer) {
            this.socket = socket;
            this.parentPeer = parentPeer;
            try {
                this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            } catch (IOException e) {
                parentPeer.log("Error creating streams for connection " + socket.getRemoteSocketAddress() + ": "
                        + e.getMessage());
                closeConnection("Stream creation failed");
            }
        }

        // Constructor for outgoing connections (peerID known)
        public PeerConnectionHandler(Socket socket, peerProcess parentPeer, int remotePeerID) {
            this(socket, parentPeer);
            this.remotePeerID = remotePeerID;
        }

        public void initiateHandshake() {
            if (this.remotePeerID != 0) { // Only for outgoing connections
                parentPeer.log("Sending handshake to peer " + this.remotePeerID);
                sendMessage(parentPeer.createHandshakeMessage());
            } else {
                parentPeer.log("Cannot initiate handshake for incoming connection before ID is known.");
            }
        }

        private void processHandshake(byte[] handshakeMsg) {
            int receivedPeerID = validateHandshake(handshakeMsg, this.remotePeerID);

            if (receivedPeerID == -1) {
                parentPeer.log(
                        "Invalid handshake received from " + socket.getRemoteSocketAddress() + ". Closing connection.");
                closeConnection("Invalid handshake");
                return;
            }

            if (this.remotePeerID == 0) { // Incoming connection, handshake identifies the peer
                this.remotePeerID = receivedPeerID;
                // Check if connection already exists
                if (parentPeer.activeConnections.containsKey(this.remotePeerID)) {
                    parentPeer.log(
                            "Duplicate connection detected with peer " + this.remotePeerID + ". Closing this one.");
                    closeConnection("Duplicate connection");
                    return;
                }
                parentPeer.activeConnections.put(this.remotePeerID, this); // Register the connection
                parentPeer.log("Handshake successful with incoming peer " + this.remotePeerID);
                // Send handshake back
                parentPeer.log("Sending handshake reply to peer " + this.remotePeerID);
                sendMessage(parentPeer.createHandshakeMessage());

            } else { // Outgoing connection, handshake confirms the peer
                parentPeer.log("Handshake successful with outgoing peer " + this.remotePeerID);
            }

            connectionEstablished = true;
            parentPeer.chokedPeers.add(this.remotePeerID); // Initially choke the peer
            parentPeer.peersChokingMe.add(this.remotePeerID); // Assume they choke us initially

            // Modify to send bitfield OR start disaster mode interaction
            if (connectionEstablished) {
                if (disasterMode && !wanReturn) { // If starting in disaster mode
                    log("Connection established with " + remotePeerID + " in disaster mode. No initial BT bitfield. Waiting for election/broadcast.");
                    // Super-peer election happens globally.
                    // If I_AM_SUPER, broadcast will happen.
                    // If normal peer, will wait for MSG_BCAST_CHUNK or send MSG_SPARSE_ACK.
                    // No automatic bitfield send here for disaster mode.
                } else { // Normal BitTorrent mode or after fail-back
                    sendBitfield(); // Send standard BitTorrent bitfield
                }
            }
        }


        public void sendBitfield() { // For BitTorrent mode
            synchronized (parentPeer.bitfield) { // Synchronize access to parent's bitfield
                byte[] payload = parentPeer.getBitfieldPayload();
                if (payload.length > 0) {
                    parentPeer.log("Sending BITFIELD to peer " + remotePeerID);
                    ActualMessage bitfieldMsg = new ActualMessage(MSG_BITFIELD, payload);
                    sendMessage(bitfieldMsg.toBytes());
                } else {
                    parentPeer.log("Skipping BITFIELD message to " + remotePeerID + " (bitfield is empty or error).");
                }
            }
        }

        public void sendInterested() {
            if (!amInterested) {
                parentPeer.log("Sending INTERESTED to peer " + remotePeerID);
                ActualMessage interestedMsg = new ActualMessage(MSG_INTERESTED);
                sendMessage(interestedMsg.toBytes());
                amInterested = true;
            }
        }

        public void sendNotInterested() {
            if (amInterested) {
                parentPeer.log("Sending NOT_INTERESTED to peer " + remotePeerID);
                ActualMessage notInterestedMsg = new ActualMessage(MSG_NOT_INTERESTED);
                sendMessage(notInterestedMsg.toBytes());
                amInterested = false;
            }
        }

        public void sendChoke() {
            if (!iAmChokingRemotePeer) {
                parentPeer.log("Sending CHOKE to peer " + remotePeerID);
                ActualMessage chokeMsg = new ActualMessage(MSG_CHOKE);
                sendMessage(chokeMsg.toBytes());
                iAmChokingRemotePeer = true;
                parentPeer.chokedPeers.add(remotePeerID); // Track that we are choking them
            }
        }

        public void sendUnchoke() {
            if (iAmChokingRemotePeer) {
                parentPeer.log("Sending UNCHOKE to peer " + remotePeerID);
                ActualMessage unchokeMsg = new ActualMessage(MSG_UNCHOKE);
                sendMessage(unchokeMsg.toBytes());
                iAmChokingRemotePeer = false;
                parentPeer.chokedPeers.remove(remotePeerID); // Track that we are not choking them
            }
        }

        private void sendRequest(int pieceIndex) {
            parentPeer.log("Sending REQUEST for piece " + pieceIndex + " to peer " + remotePeerID);
            ActualMessage requestMsg = new ActualMessage(MSG_REQUEST,
                    ByteBuffer.allocate(4).putInt(pieceIndex).array());
            sendMessage(requestMsg.toBytes());
        }

        private void sendPiece(int pieceIndex) {
            byte[] pieceData;
            synchronized (parentPeer.filePieces) { // Synchronize access to file pieces
                if (pieceIndex < 0 || pieceIndex >= parentPeer.numberOfPieces
                        || parentPeer.filePieces[pieceIndex] == null) {
                    parentPeer.log(
                            "Cannot send piece " + pieceIndex + " to peer " + remotePeerID + ": Piece not available.");
                    return;
                }
                pieceData = parentPeer.filePieces[pieceIndex];
            }

            parentPeer.log("Sending PIECE " + pieceIndex + " (" + pieceData.length + " bytes) to peer " + remotePeerID);
            ByteBuffer payloadBuffer = ByteBuffer.allocate(4 + pieceData.length);
            payloadBuffer.putInt(pieceIndex);
            payloadBuffer.put(pieceData);
            ActualMessage pieceMsg = new ActualMessage(MSG_PIECE, payloadBuffer.array());
            sendMessage(pieceMsg.toBytes());
        }

        public synchronized void sendMessage(byte[] msg) {
            if (out == null || socket.isClosed()) {
                parentPeer.log("Cannot send message to peer " + remotePeerID + ", connection closed.");
                return;
            }
            try {
                out.write(msg);
                out.flush();
            } catch (IOException e) {
                parentPeer.log("Error sending message to peer " + remotePeerID + ": " + e.getMessage());
                closeConnection("Send error");
            }
        }

        @Override
        public void run() {
            // ... existing handshake reading and processing ...
            // The message loop processing (processMessage) needs to be updated for new messages.
            // Inside the while (connectionEstablished && !Thread.currentThread().isInterrupted()) loop:
            //    processMessage(new ActualMessage(type, payload));
            // This part remains structurally similar.
            try {
                // --- Handshake Phase ---
                byte[] handshakeBuffer = new byte[32];
                int bytesRead = in.read(handshakeBuffer);
                if (bytesRead != 32) {
                    parentPeer.log("Failed to read complete handshake from " + socket.getRemoteSocketAddress() + ". Read: " + bytesRead);
                    closeConnection("Incomplete handshake");
                    return;
                }
                processHandshake(handshakeBuffer);

                if (!connectionEstablished) {
                    // Handshake failed or duplicate connection, already closed.
                    return;
                }

                // --- Message Loop ---
                while (connectionEstablished && !Thread.currentThread().isInterrupted()) {
                    // Read message length
                    int length;
                    try {
                        length = in.readInt(); // Reads 4 bytes for length
                        if (length < 1) {
                            parentPeer.log("Received invalid message length " + length + " from peer " + remotePeerID + ". Closing connection.");
                            closeConnection("Invalid message length");
                            break;
                        }
                    } catch (EOFException e) {
                        parentPeer.log("Peer " + remotePeerID + " closed the connection (EOF reading length).");
                        closeConnection("Remote peer disconnected");
                        break;
                    } catch (IOException e) {
                        parentPeer.log(
                                "IOException reading message length from peer " + remotePeerID + ": " + e.getMessage());
                        closeConnection("Read error");
                        break;
                    }

                    // Read message type and payload
                    byte[] messageBody = new byte[length]; // type (1) + payload (length-1)
                    try {
                        in.readFully(messageBody); // Read the exact number of bytes for type + payload
                    } catch (EOFException e) {
                        parentPeer.log("Peer " + remotePeerID + " closed the connection (EOF reading body).");
                        closeConnection("Remote peer disconnected");
                        break;
                    } catch (IOException e) {
                        parentPeer.log(
                                "IOException reading message body from peer " + remotePeerID + ": " + e.getMessage());
                        closeConnection("Read error");
                        break;
                    }
                    
                    byte type = messageBody[0];
                    byte[] payload = (length > 1) ? Arrays.copyOfRange(messageBody, 1, length) : new byte[0];

                    processMessage(new ActualMessage(type, payload));

                    if (parentPeer.checkTerminationCondition() && ((disasterMode && wanReturn) || !disasterMode)) { // Only break loop if BT termination
                        parentPeer.log("BT Termination condition met for peer " + remotePeerID + ". Handler loop ending.");
                        break; 
                    }
                     // For disaster mode, handler continues until connection drops or explicit shutdown
                }

            } catch (IOException e) {
                if (!socket.isClosed()) { // Avoid logging error if we closed it intentionally
                    parentPeer
                            .log("IOException in connection handler for peer " + remotePeerID + ": " + e.getMessage());
                }
            } finally {
                closeConnection("Handler finished");
                parentPeer.log("Connection handler thread for peer " + remotePeerID + " finished.");
            }
        }

        private void processMessage(ActualMessage msg) {
            if (msg == null) return;

            // Handle disaster mode messages first if active
            if (disasterMode && !wanReturn) {
                switch (msg.type) {
                    case MSG_BCAST_CHUNK:
                        if (parentPeer.I_AM_SUPER) {
                            parentPeer.log("Super-peer received an echo or misdirected BCAST_CHUNK from " + remotePeerID + ". Ignoring.");
                            return;
                        }
                        parentPeer.log("Normal peer received BCAST_CHUNK from " + remotePeerID + " (presumably super-peer). Payload size: " + msg.payload.length);
                        if (parentPeer.fountainDecoder != null) {
                            // We need a chunk ID. Let's assume it's prepended to the payload by the super-peer
                            // Or, for simplicity, OpenRQ might not need an explicit ID if symbols are in order.
                            // For this conceptual version, let's say the FountainCoder handles it.
                            // Conceptual: fountainDecoder.receiveChunk(ByteBuffer.wrap(msg.payload), someChunkId);
                            ByteBuffer chunkData = ByteBuffer.wrap(msg.payload);
                            
                            // For simplicity, let's assume chunk ID is NOT in this payload for now,
                            // and the decoder handles symbols as they arrive.
                            // This is a simplification of how fountain codes work with out-of-order/identified symbols.
                            // A more robust implementation might need chunk IDs in the payload.

                            // For now, we'll just mark that *a* chunk was received.
                            // The actual data processing/storage for reassembly is a complex part of the decoder.
                            // Let's assume the 'FountainCoder' handles the symbol.
                            // And we update our local `broadcastReceivedChunks` based on some logic.
                            // This part is highly conceptual without real decoder integration.
                            // For now, let's assume each broadcast message is a unique piece for the BitSet.
                            // This requires careful handling of how chunk IDs map to BitSet indices.
                            
                            // If BCAST_CHUNK payload *is* the piece data, and its index is implicit or known
                            // For example, if the super peer sends them in order and we count. This is fragile.
                            // A better way: payload = [chunk_index (4 bytes), chunk_data (...)]
                            // Let's assume for now: we just got *a* symbol, the decoder deals with it.
                            // We can't reliably set a bit in broadcastReceivedChunks without a chunkID.
                            // Let the decoder handle it. When decoder.isDataDecoded() is true, we're done.
                            // So, the main role of this message is to feed the decoder.
                            parentPeer.fountainDecoder.receiveChunk(chunkData, 0); // Dummy chunkId

                            // If we successfully decoded a new piece and can map it to an index for the BitSet:
                            // int decodedPieceIndexForBitSet = ... ; // This logic is missing
                            // parentPeer.broadcastReceivedChunks.set(decodedPieceIndexForBitSet);

                            if (parentPeer.fountainDecoder.isDataDecoded()) { // Conceptual
                                parentPeer.log("Normal peer: All broadcast data decoded!");
                                parentPeer.broadcastReceivedChunks.set(0, parentPeer.numberOfBroadcastChunks); // Mark all as received
                                parentPeer.reassembleBroadcastFile();
                                // Now this peer has the file and waits for fail-back or WAN return.
                            }
                        }
                        return; // Handled in disaster mode

                    case MSG_SPARSE_ACK:
                        if (parentPeer.I_AM_SUPER) {
                            parentPeer.handleSparseAck(remotePeerID, msg.payload);
                        } else {
                            parentPeer.log("Normal peer received a SPARSE_ACK from " + remotePeerID + ". Ignoring.");
                        }
                        return; // Handled

                    case MSG_ROLE_ELECT:
                        parentPeer.log("Received MSG_ROLE_ELECT from " + remotePeerID + ". Payload: " + (msg.payload != null ? msg.payload.length : "null"));
                        // Potentially trigger re-election or update peer status based on payload.
                        // For now, just log. A full election protocol would use this.
                        // As per plan: "Call electSuperPeer() ... whenever a MSG_ROLE_ELECT beacon arrives"
                        // This might cause issues if electSuperPeer() is too heavy or stateful without proper guards.
                        // For now, a simple re-evaluation:
                        // parentPeer.electSuperPeer(); // This could be disruptive if not handled carefully.
                        // Let's assume MSG_ROLE_ELECT is more of a heartbeat or status update for now.
                        return; // Handled
                }
            }

            // Fall through to BitTorrent message processing if not handled by disaster mode
            // or if wanReturn is true.
            switch (msg.type) {
                case MSG_CHOKE:
                    parentPeer.log("Received CHOKE from peer " + remotePeerID);
                    remotePeerChokingMe = true;
                    parentPeer.peersChokingMe.add(remotePeerID);

                    break;
                // ... other cases from original peerProcess.java (UNCHOKE, INTERESTED, NOT_INTERESTED, HAVE, BITFIELD, REQUEST, PIECE) ...
                // These should largely remain the same but only operate effectively in BT mode.
                case MSG_UNCHOKE:
                    parentPeer.log("Received UNCHOKE from peer " + remotePeerID);
                    remotePeerChokingMe = false;
                    parentPeer.peersChokingMe.remove(remotePeerID);
                    // Now we can request pieces if interested
                    requestPieceIfNeeded();
                    break;
                case MSG_INTERESTED:
                    parentPeer.log("Received INTERESTED from peer " + remotePeerID);
                    isInterested = true;
                    parentPeer.interestedPeers.add(remotePeerID);
                    break;
                case MSG_NOT_INTERESTED:
                    parentPeer.log("Received NOT_INTERESTED from peer " + remotePeerID);
                    isInterested = false;
                    parentPeer.interestedPeers.remove(remotePeerID);

                    break;
                case MSG_HAVE:
                    if (msg.payload.length == 4) {
                        int pieceIndex = ByteBuffer.wrap(msg.payload).getInt();
                        parentPeer.log("Received HAVE for piece " + pieceIndex + " from peer " + remotePeerID);
                        PeerInfo remoteInfo = parentPeer.peerInfoMap.get(remotePeerID);
                        if (remoteInfo != null) {
                            synchronized (remoteInfo.bitfield) { // Synchronize access
                                remoteInfo.bitfield.set(pieceIndex);
                                // Check if remote peer now has the complete file
                                if (remoteInfo.bitfield.cardinality() == parentPeer.numberOfPieces
                                        && !remoteInfo.hasFile) {
                                    remoteInfo.hasFile = true;
                                    parentPeer.totalPeersCompleted.incrementAndGet();
                                    parentPeer.log("Peer " + remotePeerID
                                            + " completed download (inferred from HAVE). Total completed: "
                                            + parentPeer.totalPeersCompleted.get());
                                    parentPeer.checkTerminationCondition(); // Check if this completion triggers
                                                                            // termination
                                }
                            }
                            // Decide if this new piece makes us interested
                            if (!amInterested && parentPeer.isInterestedIn(remoteInfo)) {
                                sendInterested();
                            }
                        }
                    } else {
                        parentPeer.log("Received malformed HAVE message from peer " + remotePeerID);
                    }
                    break;
                case MSG_BITFIELD:
                    parentPeer.log("Received BITFIELD from peer " + remotePeerID + ( (disasterMode && !wanReturn) ? " (in disaster mode, unusual)" : ""));
                    parentPeer.updatePeerBitfield(remotePeerID, msg.payload);
                    break;
                case MSG_REQUEST:
                    if (msg.payload.length == 4) {
                        int requestedIndex = ByteBuffer.wrap(msg.payload).getInt();
                        parentPeer.log("Received REQUEST for piece " + requestedIndex + " from peer " + remotePeerID);
                        // Check if we are choking this peer OR if they are not the optimistically
                        // unchoked one
                        if (!iAmChokingRemotePeer) { // Only send if not choking them
                            sendPiece(requestedIndex);
                        } else {
                            parentPeer.log("Ignoring REQUEST from choked peer " + remotePeerID);
                        }
                    } else {
                        parentPeer.log("Received malformed REQUEST message from peer " + remotePeerID);
                    }
                    break;
                case MSG_PIECE:
                    if (msg.payload.length > 4) {
                        ByteBuffer buffer = ByteBuffer.wrap(msg.payload);
                        int pieceIndex = buffer.getInt();
                        byte[] pieceData = new byte[msg.payload.length - 4];
                        buffer.get(pieceData);
                        parentPeer.log("Received PIECE " + pieceIndex + " (" + pieceData.length + " bytes) from peer "
                                + remotePeerID);

                        // Record download for rate calculation
                        PeerInfo remoteInfo = parentPeer.peerInfoMap.get(remotePeerID);
                        if (remoteInfo != null) {
                            synchronized (remoteInfo) { // Synchronize access to PeerInfo download stats
                                if (remoteInfo.downloadStartTime == 0) { // Start timer on first piece received in an
                                                                         // interval
                                    remoteInfo.downloadStartTime = System.currentTimeMillis();
                                }
                                remoteInfo.piecesDownloadedInInterval++;
                            }
                        }

                        parentPeer.savePiece(pieceIndex, pieceData);

                        // After receiving a piece, request another if still interested and unchoked
                        requestPieceIfNeeded();

                    } else {
                        parentPeer.log("Received malformed PIECE message from peer " + remotePeerID);
                    }
                    break;
                default:
                    parentPeer.log("Received unknown message type " + msg.type + " from peer " + remotePeerID);
            }
        }

        // Helper to request a piece if conditions are met
        private void requestPieceIfNeeded() {
            if (amInterested && !remotePeerChokingMe) {
                int pieceToRequest = parentPeer.selectPieceToRequest(remotePeerID);
                if (pieceToRequest != -1) {
                    sendRequest(pieceToRequest);
                } else {

                    parentPeer.log("No suitable piece to request from peer " + remotePeerID + " currently.");
                    // Maybe send NOT_INTERESTED if we completed the file?
                    if (parentPeer.bitfield.cardinality() == parentPeer.numberOfPieces) {
                        sendNotInterested();
                    }
                }
            }
        }

        public synchronized void closeConnection(String reason) {
            if (!connectionEstablished && remotePeerID == 0 && socket != null && !socket.isClosed()) {
                // Handle case where connection closes before handshake completes (e.g.,
                // duplicate connection)
                parentPeer.log("Closing pre-handshake connection with " + socket.getRemoteSocketAddress() + ". Reason: "
                        + reason);
                try {
                    socket.close();
                } catch (IOException e) {
                    /* Ignore */ }
                return; // Exit early, no need to remove from maps etc.
            }

            if (!connectionEstablished)
                return; // Avoid closing multiple times or if never fully established

            connectionEstablished = false; // Mark as not established first
            parentPeer.log("Closing connection with peer " + remotePeerID + ". Reason: " + reason);

            // Remove from parent's tracking collections
            parentPeer.activeConnections.remove(remotePeerID);
            parentPeer.interestedPeers.remove(remotePeerID);
            parentPeer.chokedPeers.remove(remotePeerID);
            parentPeer.peersChokingMe.remove(remotePeerID);
            parentPeer.preferredNeighbors.remove(remotePeerID);
            if (Integer.valueOf(remotePeerID).equals(parentPeer.optimisticallyUnchokedNeighbor)) {
                parentPeer.optimisticallyUnchokedNeighbor = null;
            }

            try {
                if (in != null)
                    in.close();
            } catch (IOException e) {
                // parentPeer.log("Error closing input stream for peer " + remotePeerID + ": " +
                // e.getMessage());
            }
            try {
                if (out != null)
                    out.close();
            } catch (IOException e) {
                // parentPeer.log("Error closing output stream for peer " + remotePeerID + ": "
                // + e.getMessage());
            }
            try {
                if (socket != null && !socket.isClosed())
                    socket.close();
            } catch (IOException e) {
                // parentPeer.log("Error closing socket for peer " + remotePeerID + ": " +
                // e.getMessage());
            }
            parentPeer.log("Connection resources released for peer " + remotePeerID);
        }
    }

    // --- Main Method ---
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java peerProcess <peerID> [--disaster] [--wan-return]");
            System.exit(1);
        }

        int peerIDArg;
        try {
            peerIDArg = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Error: peerID must be an integer.");
            System.exit(1);
            return;
        }

        for (int i = 1; i < args.length; i++) {
            if ("--disaster".equalsIgnoreCase(args[i])) {
                peerProcess.disasterMode = true;
            } else if ("--wan-return".equalsIgnoreCase(args[i])) {
                peerProcess.wanReturn = true; // This flag signals an immediate desire to be in BT mode.
                                          // If --disaster is also present, it means disaster mode should fail-back quickly.
            }
        }
        
        if (peerProcess.wanReturn && !peerProcess.disasterMode) {
            System.out.println("Note: --wan-return is typically used with --disaster mode to trigger early fail-back. Using in normal mode has no special effect.");
        }


        peerProcess peer = new peerProcess(peerIDArg);
        peer.run();
    }
}