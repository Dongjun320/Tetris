package TETRIS;

import TETRIS.network.NetMessage;
import TETRIS.network.NetworkManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * 멀티플레이 대기실(방).
 *
 * <pre>
 *  +-----------------------------------------------+
 *  |                ROOM (호스트/게스트)            |
 *  +----------------------+------------------------+
 *  |   YOU                |   OPPONENT             |
 *  |   [닉네임 입력]      |   상대 닉네임          |
 *  |   ● 준비 / ○ 대기   |   ● 준비 / ○ 대기      |
 *  +----------------------+------------------------+
 *  |       [READY]                [LEAVE]          |
 *  +-----------------------------------------------+
 * </pre>
 *
 * 동작:
 *  - 입장 직후 HELLO(name=닉네임) 송신
 *  - READY 버튼 토글 → READY(ready=true/false) 송신
 *  - 양쪽 모두 READY → 호스트가 START 송신 → onStart 콜백 발생
 *  - 게스트는 START 수신 시 onStart 콜백 발생
 *  - LEAVE: 연결 닫고 onLeave 콜백
 *  - 게임이 끝나고 다시 방에 돌아오면 양쪽 ready 상태 자동 해제
 */
public class RoomPanel extends JPanel {

    private NetworkManager network;
    private boolean        isHost;

    private String myName    = System.getProperty("user.name", "Player");
    private String oppName   = "...";
    private boolean myReady  = false;
    private boolean oppReady = false;
    private boolean oppPresent = false;       // HELLO를 받았는지

    private final JTextField nameField;
    private final JLabel     myStatusLabel, oppStatusLabel, oppNameLabel, oppNameTitle, hostMark, infoLabel;
    private final JButton    readyBtn, leaveBtn;

    private Runnable onLeave;                  // 방 떠나기 (연결 종료 + 로비/홈)
    private Runnable onStartGame;               // 게임 시작 (NetworkPanel로 전환)

    public RoomPanel() {
        setPreferredSize(new Dimension(560, 420));
        setBackground(new Color(20, 24, 40));
        setLayout(null);

        // ── 타이틀 ──
        JLabel title = new JLabel("ROOM");
        title.setForeground(new Color(120, 220, 160));
        title.setFont(new Font("맑은 고딕", Font.BOLD, 24));
        title.setBounds(0, 30, 560, 32);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        add(title);

        hostMark = new JLabel(" ");
        hostMark.setForeground(new Color(150, 165, 200));
        hostMark.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        hostMark.setBounds(0, 62, 560, 18);
        hostMark.setHorizontalAlignment(SwingConstants.CENTER);
        add(hostMark);

        // ── 내 박스 ──
        JLabel myTitle = new JLabel("YOU");
        myTitle.setForeground(new Color(100, 165, 255));
        myTitle.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        myTitle.setBounds(50, 100, 220, 22);
        myTitle.setHorizontalAlignment(SwingConstants.CENTER);
        add(myTitle);

        nameField = new JTextField(myName);
        nameField.setBounds(70, 128, 180, 32);
        nameField.setBackground(new Color(40, 45, 65));
        nameField.setForeground(Color.WHITE);
        nameField.setCaretColor(Color.WHITE);
        nameField.setBorder(BorderFactory.createLineBorder(new Color(70, 85, 130), 1));
        nameField.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        nameField.setHorizontalAlignment(JTextField.CENTER);
        nameField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitMyName(); }
        });
        add(nameField);

        myStatusLabel = new JLabel("○  대기 중");
        myStatusLabel.setForeground(new Color(200, 200, 130));
        myStatusLabel.setFont(new Font("맑은 고딕", Font.BOLD, 16));
        myStatusLabel.setBounds(50, 172, 220, 28);
        myStatusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(myStatusLabel);

        // ── 상대 박스 ──
        oppNameTitle = new JLabel("OPPONENT");
        oppNameTitle.setForeground(new Color(200, 100, 255));
        oppNameTitle.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        oppNameTitle.setBounds(290, 100, 220, 22);
        oppNameTitle.setHorizontalAlignment(SwingConstants.CENTER);
        add(oppNameTitle);

        oppNameLabel = new JLabel(oppName);
        oppNameLabel.setForeground(Color.WHITE);
        oppNameLabel.setFont(new Font("맑은 고딕", Font.BOLD, 15));
        oppNameLabel.setBounds(290, 128, 220, 32);
        oppNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        oppNameLabel.setOpaque(true);
        oppNameLabel.setBackground(new Color(40, 45, 65));
        oppNameLabel.setBorder(BorderFactory.createLineBorder(new Color(70, 85, 130), 1));
        add(oppNameLabel);

        oppStatusLabel = new JLabel("○  접속 대기");
        oppStatusLabel.setForeground(new Color(180, 180, 200));
        oppStatusLabel.setFont(new Font("맑은 고딕", Font.BOLD, 16));
        oppStatusLabel.setBounds(290, 172, 220, 28);
        oppStatusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(oppStatusLabel);

        // ── 안내 ──
        infoLabel = new JLabel("READY 를 눌러 준비 완료");
        infoLabel.setForeground(new Color(150, 165, 200));
        infoLabel.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        infoLabel.setBounds(0, 220, 560, 20);
        infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(infoLabel);

        // ── 버튼 ──
        readyBtn = makeButton("READY", new Color(35, 130, 95), new Color(55, 170, 125));
        readyBtn.setBounds(130, 260, 140, 52);
        readyBtn.addActionListener(e -> toggleReady());
        add(readyBtn);

        leaveBtn = makeButton("LEAVE", new Color(120, 50, 60), new Color(160, 70, 80));
        leaveBtn.setBounds(290, 260, 140, 52);
        leaveBtn.addActionListener(e -> doLeave());
        add(leaveBtn);
    }

    // ── 외부 콜백 ─────────────────────────────────────────
    public void setOnLeave(Runnable r)     { this.onLeave = r; }
    public void setOnStartGame(Runnable r) { this.onStartGame = r; }

    // ── 외부 접근 (TetrisMain에서 게임 패널로 연결 넘길 때 사용) ──
    public NetworkManager getNetwork() { return network; }
    public boolean isHostSide()        { return isHost; }

    /** 로비에서 전달받은 네트워크로 방 초기화 */
    public void setupConnection(NetworkManager nm, boolean host) {
        this.network = nm;
        this.isHost  = host;
        // 상태 초기화
        myReady = false;
        oppReady = false;
        oppPresent = false;
        oppName = "...";
        oppNameLabel.setText(oppName);
        hostMark.setText(host ? "● 호스트로 입장" : "● 게스트로 입장");
        infoLabel.setText("READY 를 눌러 준비 완료");
        updateStatusUi();

        network.setOnMessage(msg -> SwingUtilities.invokeLater(() -> handleMessage(msg)));
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));

        // 입장 직후 내 이름 알림
        sendHello();
    }

    /** 게임 종료 후 방으로 돌아왔을 때 호출 */
    public void onReturnFromGame() {
        // 게임 패널이 덮어썼던 메시지 핸들러를 방 핸들러로 복원
        if (network != null) {
            network.setOnMessage(msg -> SwingUtilities.invokeLater(() -> handleMessage(msg)));
            network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));
        }
        // 양쪽 ready 자동 해제
        myReady = false;
        oppReady = false;
        infoLabel.setText("게임 종료. 다시 READY 하면 새 게임");
        updateStatusUi();
        if (network != null) sendReady();   // 상대에게도 내 ready=false 알림
    }

    // ── UI 갱신 ────────────────────────────────────────────
    private void updateStatusUi() {
        myStatusLabel.setText(myReady ? "● 준비 완료" : "○  대기 중");
        myStatusLabel.setForeground(myReady ? new Color(120, 230, 120) : new Color(200, 200, 130));

        if (!oppPresent) {
            oppStatusLabel.setText("○  접속 대기");
            oppStatusLabel.setForeground(new Color(180, 180, 200));
        } else {
            oppStatusLabel.setText(oppReady ? "● 준비 완료" : "○  대기 중");
            oppStatusLabel.setForeground(oppReady ? new Color(120, 230, 120) : new Color(200, 200, 130));
        }

        readyBtn.setText(myReady ? "READY ✓" : "READY");
    }

    private void commitMyName() {
        String n = nameField.getText().trim();
        if (n.isEmpty()) n = "Player";
        if (n.length() > 16) n = n.substring(0, 16);
        nameField.setText(n);
        if (!n.equals(myName)) {
            myName = n;
            sendHello();
        }
    }

    // ── 버튼 동작 ─────────────────────────────────────────
    private void toggleReady() {
        if (!oppPresent) {
            infoLabel.setText("상대가 아직 접속하지 않았습니다");
            return;
        }
        commitMyName();
        myReady = !myReady;
        updateStatusUi();
        sendReady();
        tryStartIfBothReady();
    }

    private void doLeave() {
        if (network != null) { network.close(); network = null; }
        if (onLeave != null) onLeave.run();
    }

    // ── 송신 ───────────────────────────────────────────────
    private void sendHello() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.HELLO).put("name", myName));
    }
    private void sendReady() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.READY).put("ready", myReady ? 1 : 0));
    }
    private void sendStart() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.START));
    }

    // ── 수신 ───────────────────────────────────────────────
    private void handleMessage(NetMessage msg) {
        switch (msg.type) {
            case HELLO:
                oppName = msg.get("name", "Opponent");
                oppNameLabel.setText(oppName);
                if (!oppPresent) {
                    oppPresent = true;
                    // 새로 들어온 사람에게 내 이름도 다시 알림(중복 송신해도 무해)
                    sendHello();
                    // 현재 내 ready 상태도 알림
                    sendReady();
                    infoLabel.setText("상대 접속됨 — READY 누르면 시작 준비");
                }
                updateStatusUi();
                break;
            case READY:
                oppReady = msg.getInt("ready", 0) == 1;
                updateStatusUi();
                tryStartIfBothReady();
                break;
            case START:
                // 호스트의 시작 신호 → 게임 패널로
                if (onStartGame != null) onStartGame.run();
                break;
            default:
                // 게임 중 메시지(STATE/GARBAGE 등)는 방에선 무시
                break;
        }
    }

    private void tryStartIfBothReady() {
        if (!myReady || !oppReady || !oppPresent) return;
        // 호스트가 START 신호 송신, 게스트는 수신 대기
        if (isHost) {
            sendStart();
            if (onStartGame != null) onStartGame.run();
        } else {
            infoLabel.setText("양쪽 준비 완료 — 호스트 시작 대기 중...");
        }
    }

    private void onDisconnect() {
        infoLabel.setText("❌ 상대 연결이 끊어졌습니다 — LEAVE 를 누르세요");
        oppPresent = false;
        oppReady = false;
        oppName = "...";
        oppNameLabel.setText(oppName);
        updateStatusUi();
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
