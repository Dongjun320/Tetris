package TETRIS;

import TETRIS.network.NetMessage;
import TETRIS.network.NetworkManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 멀티플레이 대기실(방) — 최대 4인 지원.
 *
 * 호스트는 클라이언트 접속/끊김 이벤트를 감지하고,
 * HELLO/READY 메시지를 받으면 PlayerInfo를 갱신해
 * 모든 클라이언트에게 PLAYER_LIST 를 브로드캐스트한다.
 *
 * 게스트는 PLAYER_LIST를 받아 슬롯을 갱신한다.
 * 모두(2인 이상) ready되면 호스트가 START 메시지를 보낸다.
 */
public class RoomPanel extends JPanel {

    private static final int PANEL_W = 720;
    private static final int PANEL_H = 480;
    private static final int SLOT_W  = 150;
    private static final int SLOT_H  = 170;
    private static final int SLOT_Y  = 130;

    private NetworkManager network;
    private boolean        isHost;
    private int            myId       = 1;
    private int            maxPlayers = 4;

    private String  myName  = System.getProperty("user.name", "Player");
    private boolean myReady = false;

    /** id → 정보. 호스트가 권위 사본 보유, 게스트는 PLAYER_LIST로 받아 보유. */
    private final Map<Integer, PlayerInfo> players = new LinkedHashMap<>();

    private final JTextField nameField;
    private final JLabel     titleLabel, infoLabel, hostMark;
    private final JButton    readyBtn, leaveBtn;

    private Runnable onLeave;
    private Runnable onStartGame;

    /** 한 플레이어의 방 내 상태. */
    public static class PlayerInfo {
        public int     id;
        public String  name;
        public boolean ready;
        public PlayerInfo(int id, String name, boolean ready) {
            this.id = id; this.name = name; this.ready = ready;
        }
    }

    public RoomPanel() {
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(20, 24, 40));
        setLayout(null);

        titleLabel = new JLabel("ROOM");
        titleLabel.setForeground(new Color(120, 220, 160));
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 24));
        titleLabel.setBounds(0, 26, PANEL_W, 32);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(titleLabel);

        hostMark = new JLabel(" ");
        hostMark.setForeground(new Color(150, 165, 200));
        hostMark.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        hostMark.setBounds(0, 58, PANEL_W, 18);
        hostMark.setHorizontalAlignment(SwingConstants.CENTER);
        add(hostMark);

        JLabel nameTitle = new JLabel("닉네임:");
        nameTitle.setForeground(new Color(170, 185, 220));
        nameTitle.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        nameTitle.setBounds(220, 86, 60, 30);
        add(nameTitle);

        nameField = new JTextField(myName);
        nameField.setBounds(280, 86, 220, 30);
        nameField.setBackground(new Color(40, 45, 65));
        nameField.setForeground(Color.WHITE);
        nameField.setCaretColor(Color.WHITE);
        nameField.setBorder(BorderFactory.createLineBorder(new Color(70, 85, 130), 1));
        nameField.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        nameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMyName(); }
        });
        add(nameField);

        infoLabel = new JLabel("READY 를 눌러 준비 완료");
        infoLabel.setForeground(new Color(150, 165, 200));
        infoLabel.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        infoLabel.setBounds(0, 320, PANEL_W, 20);
        infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(infoLabel);

        readyBtn = makeButton("READY", new Color(35, 130, 95), new Color(55, 170, 125));
        readyBtn.setBounds(210, 360, 140, 52);
        readyBtn.addActionListener(e -> toggleReady());
        add(readyBtn);

        leaveBtn = makeButton("LEAVE", new Color(120, 50, 60), new Color(160, 70, 80));
        leaveBtn.setBounds(370, 360, 140, 52);
        leaveBtn.addActionListener(e -> doLeave());
        add(leaveBtn);
    }

    // ── 외부 콜백/조회 ────────────────────────────────────
    public void setOnLeave(Runnable r)            { this.onLeave = r; }
    public void setOnStartGame(Runnable r)        { this.onStartGame = r; }
    public NetworkManager getNetwork()            { return network; }
    public boolean isHostSide()                   { return isHost; }
    public int     getMyPlayerId()                { return myId; }
    public int     getMaxPlayers()                { return maxPlayers; }
    public Map<Integer, PlayerInfo> getPlayers()  { return players; }

    /** 로비에서 전달받은 네트워크로 방 초기화 */
    public void setupConnection(NetworkManager nm, boolean host) {
        this.network    = nm;
        this.isHost     = host;
        this.myId       = nm.getMyPlayerId();
        this.maxPlayers = nm.getMaxPlayers();

        myReady = false;
        readyBtn.setText("READY");
        players.clear();
        if (isHost) {
            players.put(NetworkManager.HOST_ID, new PlayerInfo(NetworkManager.HOST_ID, myName, false));
        }

        titleLabel.setText("ROOM  (최대 " + maxPlayers + "인)");
        hostMark.setText(host ? "● 호스트로 입장"
                              : "● 게스트로 입장 (ID: " + myId + ")");
        infoLabel.setText(host ? "다른 PC에서 접속을 기다리는 중..."
                                : "호스트의 시작을 기다리는 중...");

        network.setOnMessage(msg -> SwingUtilities.invokeLater(() -> handleMessage(msg)));
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));
        if (isHost) {
            network.setOnClientConnect(id -> SwingUtilities.invokeLater(() -> onClientJoined(id)));
            network.setOnClientDisconnect(id -> SwingUtilities.invokeLater(() -> onClientLeft(id)));
        }
        if (!isHost) sendHello();
        repaint();
    }

    /** 게임 종료 후 방으로 돌아왔을 때 호출 */
    public void onReturnFromGame() {
        if (network != null) {
            network.setOnMessage(msg -> SwingUtilities.invokeLater(() -> handleMessage(msg)));
            network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));
            if (isHost) {
                network.setOnClientConnect(id -> SwingUtilities.invokeLater(() -> onClientJoined(id)));
                network.setOnClientDisconnect(id -> SwingUtilities.invokeLater(() -> onClientLeft(id)));
            }
        }
        myReady = false;
        readyBtn.setText("READY");
        for (PlayerInfo p : players.values()) p.ready = false;
        infoLabel.setText("게임 종료. 다시 READY 하면 새 게임");
        if (isHost) broadcastPlayerList();
        else        sendReady();
        repaint();
    }

    // ── 버튼 동작 ─────────────────────────────────────────
    private void toggleReady() {
        if (players.size() < 2) {
            infoLabel.setText("최소 2명이 필요합니다");
            return;
        }
        commitMyName();
        myReady = !myReady;
        readyBtn.setText(myReady ? "READY ✓" : "READY");
        if (isHost) {
            PlayerInfo me = players.get(NetworkManager.HOST_ID);
            if (me != null) me.ready = myReady;
            broadcastPlayerList();
            checkAutoStart();
        } else {
            sendReady();
        }
        repaint();
    }

    private void doLeave() {
        if (network != null) { network.close(); network = null; }
        if (onLeave != null) onLeave.run();
    }

    private void commitMyName() {
        String n = nameField.getText().trim();
        if (n.isEmpty()) n = "Player";
        if (n.length() > 16) n = n.substring(0, 16);
        nameField.setText(n);
        if (!n.equals(myName)) {
            myName = n;
            if (isHost) {
                PlayerInfo me = players.get(NetworkManager.HOST_ID);
                if (me != null) { me.name = n; broadcastPlayerList(); }
            } else {
                sendHello();
            }
        }
    }

    // ── 호스트: 클라이언트 입장/퇴장 ──────────────────────
    private void onClientJoined(int id) {
        players.put(id, new PlayerInfo(id, "...", false));
        infoLabel.setText("플레이어 " + id + " 입장 (총 " + players.size() + "명)");
        broadcastPlayerList();
        repaint();
    }

    private void onClientLeft(int id) {
        players.remove(id);
        infoLabel.setText("플레이어 " + id + " 퇴장");
        broadcastPlayerList();
        repaint();
    }

    // ── 메시지 수신 ────────────────────────────────────────
    private void handleMessage(NetMessage msg) {
        switch (msg.type) {
            case HELLO: {
                if (!isHost) return;
                int from = msg.getInt("from", 0);
                PlayerInfo p = players.get(from);
                if (p != null) p.name = msg.get("name", "Player" + from);
                broadcastPlayerList();
                repaint();
                break;
            }
            case READY: {
                if (!isHost) return;
                int from = msg.getInt("from", 0);
                PlayerInfo p = players.get(from);
                if (p != null) p.ready = msg.getInt("ready", 0) == 1;
                broadcastPlayerList();
                checkAutoStart();
                repaint();
                break;
            }
            case JOIN_OK: {
                myId       = msg.getInt("id", myId);
                maxPlayers = msg.getInt("maxPlayers", maxPlayers);
                titleLabel.setText("ROOM  (최대 " + maxPlayers + "인)");
                hostMark.setText("● 게스트로 입장 (ID: " + myId + ")");
                sendHello();
                repaint();
                break;
            }
            case PLAYER_LIST: {
                if (isHost) return;
                applyPlayerList(msg);
                repaint();
                break;
            }
            case START: {
                if (onStartGame != null) onStartGame.run();
                break;
            }
            default:
                break;
        }
    }

    private void applyPlayerList(NetMessage msg) {
        players.clear();
        int n = msg.getInt("n", 0);
        for (int i = 0; i < n; i++) {
            String s = msg.get("p" + i);
            if (s == null) continue;
            String[] parts = s.split(":", 3);
            if (parts.length < 3) continue;
            try {
                int id = Integer.parseInt(parts[0]);
                String name = parts[1];
                boolean ready = "1".equals(parts[2]);
                players.put(id, new PlayerInfo(id, name, ready));
            } catch (Exception ignore) {}
        }
    }

    // ── 송신 ───────────────────────────────────────────────
    private void sendHello() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.HELLO)
                .put("from", myId)
                .put("name", myName));
    }
    private void sendReady() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.READY)
                .put("from", myId)
                .put("ready", myReady ? 1 : 0));
    }
    private void broadcastPlayerList() {
        if (network == null || !isHost) return;
        NetMessage m = new NetMessage(NetMessage.Type.PLAYER_LIST);
        m.put("n", players.size());
        int idx = 0;
        for (PlayerInfo p : players.values()) {
            m.put("p" + idx, p.id + ":" + p.name + ":" + (p.ready ? 1 : 0));
            idx++;
        }
        network.broadcast(m);
    }

    private void checkAutoStart() {
        if (!isHost || players.size() < 2) return;
        for (PlayerInfo p : players.values()) if (!p.ready) return;
        // 모두 ready → 게임 시작
        network.broadcast(new NetMessage(NetMessage.Type.START));
        if (onStartGame != null) onStartGame.run();
    }

    private void onDisconnect() {
        if (isHost) {
            infoLabel.setText("⚠ 호스트가 닫혔습니다 — LEAVE 를 누르세요");
        } else {
            infoLabel.setText("❌ 호스트 연결이 끊어졌습니다 — LEAVE 를 누르세요");
            players.clear();
        }
        repaint();
    }

    // ── 렌더링 ────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int n = Math.max(maxPlayers, 2);
        int totalW = n * SLOT_W + (n - 1) * 12;
        int startX = (PANEL_W - totalW) / 2;

        java.util.List<PlayerInfo> list = new java.util.ArrayList<>(players.values());
        list.sort((a, b) -> Integer.compare(a.id, b.id));

        for (int slot = 0; slot < n; slot++) {
            int x = startX + slot * (SLOT_W + 12);
            PlayerInfo p = (slot < list.size()) ? list.get(slot) : null;
            drawSlot(g2, x, SLOT_Y, p, slot);
        }
    }

    private void drawSlot(Graphics2D g, int x, int y, PlayerInfo p, int slot) {
        boolean isMe = (p != null && p.id == myId);
        g.setColor(p == null ? new Color(28, 32, 50) : new Color(40, 50, 80));
        g.fillRoundRect(x, y, SLOT_W, SLOT_H, 12, 12);
        g.setColor(isMe ? new Color(120, 220, 160) : new Color(70, 80, 120));
        g.drawRoundRect(x, y, SLOT_W, SLOT_H, 12, 12);

        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        g.setColor(new Color(170, 185, 220));
        String header = (slot == 0) ? "HOST" : ("PLAYER " + (slot + 1));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(header, x + (SLOT_W - fm.stringWidth(header)) / 2, y + 22);

        if (p == null) {
            g.setColor(new Color(80, 90, 130));
            g.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
            String empty = "비어있음";
            fm = g.getFontMetrics();
            g.drawString(empty, x + (SLOT_W - fm.stringWidth(empty)) / 2, y + 95);
            return;
        }

        // 닉네임
        g.setFont(new Font("맑은 고딕", Font.BOLD, 17));
        g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        String name = p.name == null ? "..." : p.name;
        if (fm.stringWidth(name) > SLOT_W - 16) {
            while (name.length() > 1 && fm.stringWidth(name + "…") > SLOT_W - 16) name = name.substring(0, name.length()-1);
            name = name + "…";
        }
        g.drawString(name, x + (SLOT_W - fm.stringWidth(name)) / 2, y + 70);

        // ID 표시
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(140, 155, 195));
        String idStr = "ID " + p.id + (isMe ? "  (나)" : "");
        fm = g.getFontMetrics();
        g.drawString(idStr, x + (SLOT_W - fm.stringWidth(idStr)) / 2, y + 90);

        // ready 라이트
        int dotSize = 14;
        int dotX = x + SLOT_W / 2 - dotSize / 2;
        int dotY = y + 110;
        g.setColor(p.ready ? new Color(120, 230, 120) : new Color(80, 90, 130));
        g.fillOval(dotX, dotY, dotSize, dotSize);
        g.setColor(p.ready ? new Color(160, 255, 160) : new Color(120, 130, 170));
        g.drawOval(dotX, dotY, dotSize, dotSize);

        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        g.setColor(p.ready ? new Color(120, 230, 120) : new Color(180, 180, 130));
        String status = p.ready ? "준비 완료" : "대기 중";
        fm = g.getFontMetrics();
        g.drawString(status, x + (SLOT_W - fm.stringWidth(status)) / 2, y + 148);
    }

    // ── 커스텀 버튼 ────────────────────────────────────────
    private JButton makeButton(String text, Color base, Color hover) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = !isEnabled() ? new Color(60, 65, 90)
                            : (getModel().isRollover() ? hover : base);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(hover.brighter());
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 17));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                        (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
        };
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
