package TETRIS;

import TETRIS.network.NetworkManager;

import javax.swing.*;
import java.awt.*;
import java.util.function.BiConsumer;

/**
 * 온라인 멀티 로비 화면.
 *
 * <ul>
 *   <li>방 만들기(호스트): 포트를 열고 게스트 접속을 대기</li>
 *   <li>방 참가(게스트): 상대 IP를 입력해 접속</li>
 * </ul>
 *
 * 연결이 완료되면 onConnected(매니저, isHost) 콜백이 호출된다.
 */
public class LobbyPanel extends JPanel {

    private Runnable                       onBack;
    private BiConsumer<NetworkManager, Boolean> onConnected;

    private final JLabel     statusLabel;
    private final JLabel     myIpLabel;
    private final JTextField ipField;
    private final JButton    hostBtn, joinBtn, backBtn;
    private NetworkManager   network;

    public LobbyPanel() {
        setPreferredSize(new Dimension(560, 480));
        setBackground(new Color(20, 24, 40));
        setLayout(null);

        // 제목
        JLabel title = new JLabel("ONLINE MULTIPLAYER");
        title.setForeground(new Color(120, 180, 255));
        title.setFont(new Font("맑은 고딕", Font.BOLD, 24));
        title.setBounds(0, 38, 560, 36);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        add(title);

        JLabel subtitle = new JLabel("같은 와이파이에 연결된 두 PC가 필요합니다");
        subtitle.setForeground(new Color(130, 145, 195));
        subtitle.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        subtitle.setBounds(0, 76, 560, 20);
        subtitle.setHorizontalAlignment(SwingConstants.CENTER);
        add(subtitle);

        // 내 IP 표시 박스
        myIpLabel = new JLabel("내 IP:  " + NetworkManager.getLocalIP());
        myIpLabel.setForeground(new Color(220, 230, 255));
        myIpLabel.setFont(new Font("Consolas", Font.BOLD, 15));
        myIpLabel.setBounds(0, 112, 560, 28);
        myIpLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(myIpLabel);

        // 호스트 버튼
        hostBtn = makeButton("⌂  방 만들기 (호스트)", new Color(35, 110, 175), new Color(55, 140, 215));
        hostBtn.setBounds(130, 158, 300, 52);
        hostBtn.addActionListener(e -> startHost());
        add(hostBtn);

        // 구분선
        JLabel divider = new JLabel("─ ─ ─ ─ ─ ─  또는  ─ ─ ─ ─ ─ ─");
        divider.setForeground(new Color(80, 90, 130));
        divider.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        divider.setBounds(0, 222, 560, 20);
        divider.setHorizontalAlignment(SwingConstants.CENTER);
        add(divider);

        // 상대 IP 입력
        JLabel ipLabel = new JLabel("상대 IP:");
        ipLabel.setForeground(new Color(170, 185, 220));
        ipLabel.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        ipLabel.setBounds(130, 254, 80, 30);
        add(ipLabel);

        ipField = new JTextField("192.168.0.");
        ipField.setBounds(200, 254, 230, 30);
        ipField.setBackground(new Color(40, 45, 65));
        ipField.setForeground(Color.WHITE);
        ipField.setCaretColor(Color.WHITE);
        ipField.setBorder(BorderFactory.createLineBorder(new Color(70, 85, 130), 1));
        ipField.setFont(new Font("Consolas", Font.PLAIN, 15));
        add(ipField);

        // 게스트 버튼
        joinBtn = makeButton("➜  방 참가하기 (게스트)", new Color(110, 35, 135), new Color(150, 55, 175));
        joinBtn.setBounds(130, 294, 300, 52);
        joinBtn.addActionListener(e -> startGuest());
        add(joinBtn);

        // 상태 표시
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(255, 200, 80));
        statusLabel.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        statusLabel.setBounds(20, 364, 520, 24);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel);

        // 뒤로
        backBtn = makeButton("←  홈으로", new Color(60, 65, 95), new Color(85, 90, 125));
        backBtn.setBounds(200, 410, 160, 40);
        backBtn.addActionListener(e -> doBack());
        add(backBtn);
    }

    // ── 외부 콜백 등록 ─────────────────────────────────────
    public void setOnBack(Runnable r)                                   { this.onBack = r; }
    public void setOnConnected(BiConsumer<NetworkManager, Boolean> c)   { this.onConnected = c; }

    /** 화면 진입 시 초기화 */
    public void resetState() {
        statusLabel.setText(" ");
        myIpLabel.setText("내 IP:  " + NetworkManager.getLocalIP());
        hostBtn.setEnabled(true);
        joinBtn.setEnabled(true);
        ipField.setEnabled(true);
        if (network != null) { network.close(); network = null; }
    }

    private void doBack() {
        if (network != null) { network.close(); network = null; }
        if (onBack != null) onBack.run();
    }

    // ── 호스트 시작 ────────────────────────────────────────
    private void startHost() {
        statusLabel.setForeground(new Color(255, 200, 80));
        statusLabel.setText("⏳ 게스트 접속을 기다리는 중...  (포트 " + NetworkManager.DEFAULT_PORT + ")");
        setBusy(true);

        network = new NetworkManager();
        network.setOnConnect(() -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(120, 230, 120));
            statusLabel.setText("✅ 게스트 접속! 게임을 시작합니다");
            if (onConnected != null) onConnected.accept(network, true);
        }));
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(230, 90, 90));
            statusLabel.setText("❌ 연결이 끊어졌습니다");
            setBusy(false);
        }));
        network.host(NetworkManager.DEFAULT_PORT);
    }

    // ── 게스트 접속 ────────────────────────────────────────
    private void startGuest() {
        String ip = ipField.getText().trim();
        if (ip.isEmpty() || ip.endsWith(".")) {
            statusLabel.setForeground(new Color(230, 90, 90));
            statusLabel.setText("⚠ 올바른 IP를 입력해주세요");
            return;
        }
        statusLabel.setForeground(new Color(255, 200, 80));
        statusLabel.setText("⏳ " + ip + " 에 접속 중...");
        setBusy(true);

        network = new NetworkManager();
        network.setOnConnect(() -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(120, 230, 120));
            statusLabel.setText("✅ 호스트에 접속 성공!");
            if (onConnected != null) onConnected.accept(network, false);
        }));
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(230, 90, 90));
            statusLabel.setText("❌ 접속 실패 또는 연결이 끊어졌습니다");
            setBusy(false);
        }));
        network.connect(ip, NetworkManager.DEFAULT_PORT);
    }

    private void setBusy(boolean busy) {
        hostBtn.setEnabled(!busy);
        joinBtn.setEnabled(!busy);
        ipField.setEnabled(!busy);
    }

    // ── 커스텀 버튼 ────────────────────────────────────────
    private JButton makeButton(String text, Color base, Color hover) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill;
                if (!isEnabled())                       fill = new Color(60, 65, 90);
                else if (getModel().isRollover())       fill = hover;
                else                                    fill = base;
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(hover.brighter());
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(isEnabled() ? Color.WHITE : new Color(160, 165, 180));
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 16));
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
