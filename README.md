# P2P File Sharing System

This project is an **advanced Peer-to-Peer (P2P) file-sharing system** inspired by **BitTorrent**, implemented in **Java**. It features both traditional BitTorrent protocol implementation and an innovative **Disaster Mode** for unreliable network conditions. This project is part of CNT5106C **Computer Networks (Spring 2025)**.

## 🌟 Key Features

### 🔄 **Traditional BitTorrent Mode**
✔ **TCP Socket Communication** - Establishes reliable P2P connections  
✔ **Handshake Protocol** - 32-byte handshake for peer authentication  
✔ **Bitfield Exchange** - Efficient tracking of file piece availability  
✔ **Interest Management** - Intelligent interested/not-interested messaging  
✔ **Piece-based File Transfer** - Configurable piece size for optimal transfer  
✔ **Choking/Unchoking Algorithm** - Prioritizes high-bandwidth peers  
✔ **Preferred Neighbors** - Dynamic selection based on download rates  
✔ **Optimistic Unchoking** - Periodically tries new peers for better rates  
✔ **File Reconstruction** - Automatic reassembly of complete files  
✔ **Graceful Termination** - Stops when all peers have complete files  

### 🚨 **Disaster Mode (Network Disruption Resilience)**
✔ **Super-Peer Election** - Battery-level based leader election  
✔ **Fountain Coding** - Redundant encoding for unreliable networks  
✔ **Broadcast Distribution** - Efficient one-to-many chunk distribution  
✔ **Sparse ACK Protocol** - Periodic acknowledgment with bitmap tracking  
✔ **Automatic Failback** - Seamless transition back to BitTorrent mode  
✔ **WAN Return Support** - CLI flag for early failback testing  

### 📊 **Advanced Features**
✔ **Concurrent Processing** - Multi-threaded connection handling  
✔ **Rate-based Selection** - Download rate calculation for peer prioritization  
✔ **Comprehensive Logging** - Detailed activity logs for debugging  
✔ **Configuration-driven** - External config files for easy customization  
✔ **Random Piece Selection** - Prevents bottlenecks in piece distribution  
✔ **Connection Management** - Robust connection lifecycle handling

## 🛠️ Tech Stack

- **Language**: Java 17+
- **Networking**: TCP Sockets, ServerSocket, DataInputStream/DataOutputStream
- **Concurrency**: ScheduledExecutorService, ConcurrentHashMap, AtomicInteger
- **Data Structures**: BitSet, ByteBuffer, Collections Framework
- **I/O**: File I/O, Object Serialization, Buffered Streams
- **Threading**: Multi-threaded architecture with connection pooling
- **External Dependencies**: OpenRQ (Conceptual - for Fountain Coding)

## 🚀 Getting Started

### **1️⃣ Prerequisites**

- **JDK 17** or higher
- **Git** for cloning the repository
- **Terminal/Command Prompt** for execution

### **2️⃣ Installation**

```bash
# Clone the repository
git clone https://github.com/anayy09/P2PFileSharing.git
cd P2PFileSharing

# Compile the Java source
javac peerProcess.java
```

### **3️⃣ Configuration**

Ensure configuration files are properly set up:

- **`Common.cfg`** - Global parameters (piece size, intervals, file info)
- **`PeerInfo.cfg`** - Peer details (ID, host, port, initial file status, battery level)

### **4️⃣ Execution**

#### **Standard BitTorrent Mode**
```bash
# Start individual peers (each in separate terminal)
java peerProcess 1001
java peerProcess 1002  
java peerProcess 1003
```

#### **Disaster Mode**
```bash
# Enable disaster mode with fountain coding
java peerProcess 1001 --disaster

# Test early failback to BitTorrent
java peerProcess 1001 --disaster --wan-return
```

## 📂 Project Structure

```text
P2PFileSharing/
├── .git/                    # Git version control
├── .gitignore              # Git ignore patterns
├── LICENSE.txt             # MIT License
├── README.md               # Project documentation
├── Common.cfg              # Global configuration
├── PeerInfo.cfg            # Peer network topology
├── peerProcess.java        # Core P2P implementation
├── peer_1001/             # Peer 1001 file directory
│   └── tree.jpg           # Sample file (24MB)
├── peer_1002/             # Peer 1002 file directory  
├── peer_1003/             # Peer 1003 file directory
└── log_peer_*/            # Runtime logs (auto-generated)
    └── log_peer_*.log     # Detailed activity logs
```

## ⚙️ Configuration Files

### **Common.cfg**
```properties
NumberOfPreferredNeighbors 3      # Max preferred connections
UnchokingInterval 5               # Seconds between unchoke evaluations  
OptimisticUnchokingInterval 10    # Optimistic unchoke frequency
FileName tree.jpg                 # File to be shared
FileSize 24301474                # File size in bytes
PieceSize 16384                  # BitTorrent piece size
BroadcastChunkSize 1024          # Disaster mode chunk size
SparseAckInterval 5000           # ACK interval in milliseconds
```

### **PeerInfo.cfg**
```properties
# Format: PeerID Host Port HasFile IsSuperCandidate BatteryLevel
1001 localhost 6001 1 1 87    # Initial file owner, super candidate  
1002 localhost 6002 0 1 95    # No file, super candidate
1003 localhost 6003 0 0 70    # No file, regular peer
1004 localhost 6004 0 0 60    # No file, regular peer
1005 localhost 6005 0 1 90    # No file, super candidate
1006 localhost 6006 0 0 50    # No file, regular peer
```

## 📝 Logging Events

The system logs:
✔ TCP connections (`connected`, `disconnected`)

✔ Bitfield exchange (`sent`, `received`)

✔ **Interested / Not Interested messages**

✔ File transfer status

✔ Choking/unchoking events

✔ Termination condition when all peers have the complete file

Example logs:

```
[Time]: Peer 1001 received the ‘interested’ message from Peer 1002.
[Time]: Peer 1001 sent the ‘not interested’ message to Peer 1003.
[Time]: Peer 1002 has downloaded the piece [4] from Peer 1001.
[Time]: Peer 1001 has downloaded the complete file.
```
