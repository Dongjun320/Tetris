package TETRIS.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.function.Consumer;

/**
 * TCP 소켓 통신 매니저.
 *
 * <ul>
 *   <li>호스트 모드: {@link #host(int)} → ServerSocket으로 한 명을 받아 연결</li>
 *   <li>게스트 모드: {@link #connect(String, int)} → 호스트 IP로 접속</li>
 * </ul>
 *
 * 메시지 수신은 별도 스레드에서 이루어지므로,
 * Swing UI를 갱신하려면 콜백 안에서 SwingUtilities.invokeLater()로 감싸야 한다.
 */
public class NetworkManager {

    public static final int DEFAULT_PORT = 5555;

    private volatile Socket       socket;
    private volatile ServerSocket serverSocket;
    private BufferedReader        in;
    private PrintWriter           out;
    private Thread                receiveThread;
    private volatile boolean      running;

    private Consumer<NetMessage>  onMessage;
    private Runnable              onConnect;
    private Runnable              onDisconnect;

    // ── 콜백 등록 ──────────────────────────────────────────
    public void setOnMessage(Consumer<NetMessage> h)  { this.onMessage = h; }
    public void setOnConnect(Runnable h)              { this.onConnect = h; }
    public void setOnDisconnect(Runnable h)           { this.onDisconnect = h; }

    // ── 호스트 모드 ────────────────────────────────────────
    /** 지정 포트에서 게스트 한 명을 기다리며, 연결되면 콜백 발생. */
    public void host(int port) {
        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setSoTimeout(0);            // 무제한 대기
                Socket s = serverSocket.accept();        // 게스트 접속 대기
                try { serverSocket.close(); } catch (Exception ignore) {}
                serverSocket = null;
                attach(s);
            } catch (IOException e) {
                System.err.println("[Net] Host error: " + e.getMessage());
                fireDisconnect();
            }
        }, "Net-Host");
        t.setDaemon(true);
        t.start();
    }

    // ── 게스트 모드 ────────────────────────────────────────
    /** 호스트의 IP/포트로 접속. */
    public void connect(String host, int port) {
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);  // 5초 타임아웃
                attach(s);
            } catch (IOException e) {
                System.err.println("[Net] Connect error: " + e.getMessage());
                fireDisconnect();
            }
        }, "Net-Guest");
        t.setDaemon(true);
        t.start();
    }

    // ── 연결 완료 후 스트림 설정 + 수신 루프 시작 ───────────
    private void attach(Socket s) throws IOException {
        this.socket = s;
        s.setTcpNoDelay(true);                           // 작은 패킷도 지연 없이 전송
        in  = new BufferedReader(new InputStreamReader(s.getInputStream(),  StandardCharsets.UTF_8));
        out = new PrintWriter   (new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        running = true;
        fireConnect();
        startReceiveLoop();
    }

    private void startReceiveLoop() {
        receiveThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    NetMessage msg = NetMessage.deserialize(line);
                    if (msg == null) continue;
                    if (msg.type == NetMessage.Type.DISCONNECT) {
                        running = false;
                        break;
                    }
                    if (onMessage != null) onMessage.accept(msg);
                }
            } catch (IOException e) {
                if (running) System.err.println("[Net] Receive error: " + e.getMessage());
            } finally {
                fireDisconnect();
            }
        }, "Net-Receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    // ── 메시지 송신 ────────────────────────────────────────
    /** 메시지를 한 줄로 전송. 송신 실패는 조용히 무시 (수신 루프가 끊김을 감지). */
    public synchronized void send(NetMessage msg) {
        if (out == null || msg == null) return;
        out.println(msg.serialize());
        // PrintWriter는 autoFlush=true 라서 별도 flush 불필요
    }

    // ── 상태 ───────────────────────────────────────────────
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && running;
    }

    /** 연결을 안전하게 종료. 반복 호출해도 안전. */
    public synchronized void close() {
        if (!running && socket == null && serverSocket == null) return;
        boolean wasRunning = running;
        running = false;

        // 정상 종료 알림 시도 (실패해도 무시)
        try {
            if (out != null && wasRunning) {
                out.println(new NetMessage(NetMessage.Type.DISCONNECT).serialize());
            }
        } catch (Exception ignore) {}

        try { if (in  != null) in.close();  } catch (Exception ignore) {}
        try { if (out != null) out.close(); } catch (Exception ignore) {}
        try { if (socket       != null) socket.close();       } catch (Exception ignore) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignore) {}

        socket = null;
        serverSocket = null;
    }

    private void fireConnect()    { if (onConnect    != null) onConnect.run();    }
    private void fireDisconnect() { if (onDisconnect != null) onDisconnect.run(); }

    // ── 로컬 IP 조회(안내용) ───────────────────────────────
    /**
     * LAN에서 외부 PC가 접속할 수 있는 IPv4 주소를 찾는다.
     * loopback(127.x), 가상 인터페이스, IPv6는 제외하고
     * 사설 IP 대역(192.168.x, 10.x, 172.16~31.x)을 우선 반환한다.
     */
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
}
