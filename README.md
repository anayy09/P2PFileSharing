# P2P File Sharing System

This project is a **Peer-to-Peer (P2P) file-sharing system**, inspired by **BitTorrent**, implemented in **Java**. It is part of the **Computer Networks Course (CNT5106C)**.

## Features
✔ Establishes P2P connections using **TCP sockets**  
✔ Implements a **handshake process** to authenticate peers  
✔ Reads configuration files (`Common.cfg`, `PeerInfo.cfg`)  
✔ Logs network activity  

## Getting Started
### **1️⃣ Setup**
1. Install **JDK 17 or higher**.
2. Clone the repository:
   ```sh
   git clone https://github.com/YourUsername/P2PFileSharing.git
   cd P2PFileSharing
    ```

### **2️⃣ Compile & Run**

#### **Run the Server (Peer 1001)**

```sh
javac src/Server.java
java Server
```

#### **Run the Client (Peer 1002)**

```sh
javac src/Client.java
java Client
```

## 📂 Project Structure

```
P2PFileSharing
│── .gitignore
│── README.md
│── config/
│   │── Common.cfg  (Shared file configuration)
│   │── PeerInfo.cfg (Peer information)
│── src/
│   │── Client.java  (P2P Client)
│   │── Server.java  (P2P Server)
│── peer_1001/  (File directory for each peer)
│── peer_1002/
```
