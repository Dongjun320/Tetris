package TETRIS.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * TCP 소켓 통신 매니저 (호스트-스타 모델, 최대 4인).
 *
 * <ul>
 *   <li>호스트 모드: {@link #host(int, int)} → ServerSocket으로 여러 클라이언트를 받음.
 *       각 클라이언트에 playerId(2,3,4)를 부여하고 별도 스레드로 메시지를 수신함.</li>
 *   <li>게스트 모드: {@link #connect(String, int)} → 호스트에 단일 연결.
 *       호스트가 JOIN_OK로 부여해주는 playerId를 받아 사용.</li>
 * </ul>
 *
 * 호스트의 playerId는 항상 <b>1</b>이고, 게스트는 접속 순서대로 2, 3, 4를 부여받는다.
 *
 * <p>받은 메시지는 모두 {@code onMessage} 콜백으로 전달된다.
 * 메시지의 {@code from} 필드를 보면 누가 보낸 건지 알 수 있다.
 * 호스트가 받은 메시지에서 {@code from}이 비어 있으면 자동으로 클라이언트 ID로 보정된다.</p>
 *
 * <p>UI 갱신은 콜백 안에서 {@code SwingUtilities.invokeLater()}로 감싸야 한다.</p>
 */
public class NetworkManager {

    public static final int DEFAULT_PORT = 5555;
    public static final int HOST_ID      = 1;

    // ── 공통 ──────────────────────────────────────────────
    private volatile boolean isHost;
    private volatile int     myPlayerId = HOST_ID;
    private volatile boolean running    = false;

    private Consumer<NetMessage> onMessage;
    private Runnable             onConnect;
    private Runnable             onDisconnect;
    private IntConsumer          onClientConnect;     // 호스트 전용: 새 클라이언트 입장(playerId)
    private IntConsumer          onClientDisconnect;  // 호스트 전용: 클라이언트 끊김(playerId)

    // ── 게스트 전용 ───────────────────────────────────────
    private Socket          guestSocket;
    private BufferedReader  guestIn;
    private PrintWriter     guestOut;
    private Thread          guestReceiveThread;

    // ── 호스트 전용 ───────────────────────────────────────
    private ServerSocket    serverSocket;
    private int             maxPlayers   = 4;
    private int             nextPlayerId = 2;
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();

    // ── 콜백 등록 ─────────────────────────────────────────
    public void setOnMessage(Consumer<NetMessage> h)       { this.onMessage = h; }
    public void setOnConnect(Runnable h)                   { this.onConnect = h; }
    public void setOnDisconnect(Runnable h)                { this.onDisconnect = h; }
    public void setOnClientConnect(IntConsumer h)          { this.onClientConnect = h; }
    public void setOnClientDisconnect(IntConsumer h)       { this.onClientDisconnect = h; }

    // ── 상태 조회 ─────────────────────────────────────────
    public boolean isHost()        { return isHost; }
    public int     getMyPlayerId() { return myPlayerId; }
    public int     getMaxPlayers() { return maxPlayers; }
    public boolean isConnected() {
        if (!running) return false;
        if (isHost)   return serverSocket != null || !clients.isEmpty();
        return guestSocket != null && guestSocket.isConnected() && !guestSocket.isClosed();
    }

    /** 호스트가 보유 중인 클라이언트 playerId 집합(자기 자신 제외). */
    public java.util.Set<Integer> getClientIds() {
        return Collections.unmodifiableSet(clients.keySet());
    }

    // ── 호스트 모드 ───────────────────────────────────────
    public void host(int port, int maxPlayers) {
        this.isHost       = true;
        this.myPlayerId   = HOST_ID;
        this.maxPlayers   = Math.max(2, Math.min(4, maxPlayers));
        this.nextPlayerId = 2;
        this.running      = true;

        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                if (onConnect != null) onConnect.run();   // 방이 열렸음을 알림
                while (running) {
                    Socket s;
                    try { s = serverSocket.accept(); }
                    catch (IOException ex) { break; }

                    // 정원 초과 시 거절
                    int currentTotal = clients.size() + 1;  // +1 = 호스트 본인
                    if (currentTotal >= this.maxPlayers) {
                        try { s.close(); } catch (Exception ignore) {}
                        continue;
                    }
                    int id;
                    synchronized (this) { id = nextPlayerId++; }
                    ClientHandler ch;
                    try {
                        ch = new ClientHandler(id, s);
                        clients.put(id, ch);
                        ch.start();
                        ch.send(new NetMessage(NetMessage.Type.JOIN_OK)
                                .put("id",         id)
                                .put("maxPlayers", this.maxPlayers));
                        if (onClientConnect != null) onClientConnect.accept(id);
                    } catch (Exception e) {
                        try { s.close(); } catch (Exception ignore) {}
                    }
                }
            } catch (IOException e) {
                System.err.println("[Net] Host listener error: " + e.getMessage());
            } finally {
                if (onDisconnect != null) onDisconnect.run();
            }
        }, "Net-HostAccept");
        t.setDaemon(true);
        t.start();
    }

    // ── 게스트 모드 ───────────────────────────────────────
    public void connect(String host, int port) {
        this.isHost  = false;
        this.running = true;

        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setTcpNoDelay(true);
                guestSocket = s;
                guestIn  = new BufferedReader(new InputStreamReader(s.getInputStream(),  StandardCharsets.UTF_8));
                guestOut = new PrintWriter   (new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                if (onConnect != null) onConnect.run();
                startGuestReceiveLoop();
            } catch (IOException e) {
                System.err.println("[Net] Connect error: " + e.getMessage());
                if (onDisconnect != null) onDisconnect.run();
            }
        }, "Net-Guest");
        t.setDaemon(true);
        t.start();
    }

    private void startGuestReceiveLoop() {
        guestReceiveThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = guestIn.readLine()) != null) {
                    NetMessage msg = NetMessage.deserialize(line);
                    if (msg == null) continue;
                    if (msg.type == NetMessage.Type.DISCONNECT) { running = false; break; }
                    if (msg.type == NetMessage.Type.JOIN_OK) {
                        myPlayerId = msg.getInt("id", 0);
                        maxPlayers = msg.getInt("maxPlayers", 4);
                    }
                    if (onMessage != null) onMessage.accept(msg);
                }
            } catch (IOException e) {
                if (running) System.err.println("[Net] Receive error: " + e.getMessage());
            } finally {
                if (onDisconnect != null) onDisconnect.run();
            }
        }, "Net-GuestRecv");
        guestReceiveThread.setDaemon(true);
        guestReceiveThread.start();
    }

    // ── 메시지 송신 ────────────────────────────────────────
    /** 호스트: 모든 클라이언트에 broadcast. 게스트: 호스트에게 송신. */
    public synchronized void send(NetMessage msg) {
        if (msg == null) return;
        if (isHost) {
            broadcast(msg);
        } else {
            if (guestOut != null) guestOut.println(msg.serialize());
        }
    }

    /** 호스트 전용: 특정 클라이언트(id≥2)에게 송신. id=1(호스트 자신)이면 loopback. */
    public synchronized void sendTo(int playerId, NetMessage msg) {
        if (msg == null) return;
        if (!isHost) { send(msg); return; }
        if (playerId == HOST_ID) {
            if (onMessage != null) onMessage.accept(msg);   // 자기 자신 = loopback
            return;
        }
        ClientHandler ch = clients.get(playerId);
        if (ch != null) ch.send(msg);
    }

    /** 호스트 전용: 모든 게스트에게 broadcast (호스트 자기 자신 제외). */
    public synchronized void broadcast(NetMessage msg) {
        if (msg == null || !isHost) return;
        String line = msg.serialize();
        for (ClientHandler ch : clients.values()) {
            ch.sendRaw(line);
        }
    }

    /** 호스트 전용: 한 명을 빼고 모두에게 broadcast. */
    public synchronized void broadcastExcept(int exceptId, NetMessage msg) {
        if (msg == null || !isHost) return;
        String line = msg.serialize();
        for (Map.Entry<Integer, ClientHandler> e : clients.entrySet()) {
            if (e.getKey() == exceptId) continue;
            e.getValue().sendRaw(line);
        }
    }

    // ── 종료 ──────────────────────────────────────────────
    public synchronized void close() {
        if (!running) return;
        running = false;

        if (isHost) {
            try { broadcast(new NetMessage(NetMessage.Type.DISCONNECT)); } catch (Exception ignore) {}
            for (ClientHandler ch : new java.util.ArrayList<>(clients.values())) ch.close();
            clients.clear();
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignore) {}
            serverSocket = null;
        } else {
            try { if (guestOut != null) guestOut.println(new NetMessage(NetMessage.Type.DISCONNECT).serialize()); } catch (Exception ignore) {}
            try { if (guestIn  != null) guestIn.close();  } catch (Exception ignore) {}
            try { if (guestOut != null) guestOut.close(); } catch (Exception ignore) {}
            try { if (guestSocket != null) guestSocket.close(); } catch (Exception ignore) {}
            guestSocket = null;
        }
    }

    // ── 로컬 IP (안내용) ──────────────────────────────────
    public static String getLocalIP() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr instanceof Inet6Address) continue;
                    String ip = addr.getHostAddress();
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) {
                        return ip;
                    }
                    fallback = ip;
                }
            }
        } catch (Exception ignore) {}
        return fallback;
    }

    // ─────────────────────────────────────────────────────
    // 내부 클래스: 호스트가 관리하는 클라이언트
    // ─────────────────────────────────────────────────────
    private class ClientHandler {
        final int            id;
        final Socket         socket;
        BufferedReader       in;
        PrintWriter          out;
        Thread               receiveThread;
        volatile boolean     alive;

        ClientHandler(int id, Socket socket) throws IOException {
            this.id     = id;
            this.socket = socket;
            socket.setTcpNoDelay(true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));
            out = new PrintWriter   (new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            alive = true;
        }

        void start() {
            receiveThread = new Thread(() -> {
                try {
                    String line;
                    while (alive && (line = in.readLine()) != null) {
                        NetMessage msg = NetMessage.deserialize(line);
                        if (msg == null) continue;
                        if (msg.type == NetMessage.Type.DISCONNECT) break;
                        // from이 비어 있으면 자동 보정
                        if (msg.get("from") == null) msg.put("from", id);
                        if (onMessage != null) onMessage.accept(msg);
                    }
                } catch (IOException e) {
                    if (alive) System.err.println("[Net] Host recv from " + id + ": " + e.getMessage());
                } finally {
                    alive = false;
                    clients.remove(id);
                    try { socket.close(); } catch (Exception ignore) {}
                    if (onClientDisconnect != null) onClientDisconnect.accept(id);
                }
            }, "Net-Host-Recv-" + id);
            receiveThread.setDaemon(true);
            receiveThread.start();
        }

        void send(NetMessage msg)  { sendRaw(msg.serialize()); }
        void sendRaw(String line)  { if (alive && out != null) out.println(line); }

        void close() {
            alive = false;
            try { if (out != null) out.println(new NetMessage(NetMessage.Type.DISCONNECT).serialize()); } catch (Exception ignore) {}
            try { if (in  != null) in.close();  } catch (Exception ignore) {}
            try { if (out != null) out.close(); } catch (Exception ignore) {}
            try { socket.close(); } catch (Exception ignore) {}
        }
    }
}
