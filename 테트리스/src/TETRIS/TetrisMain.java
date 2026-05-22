package TETRIS;

import javax.swing.*;
import java.awt.*;

public class TetrisMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("TETRIS");
            CardLayout cards = new CardLayout();
            JPanel container = new JPanel(cards);

            // 각 패널 생성
            GamePanel       singlePanel = new GamePanel();
            TwoPlayerPanel  twoPanel    = new TwoPlayerPanel();
            LobbyPanel      lobbyPanel  = new LobbyPanel();
            RoomPanel       roomPanel   = new RoomPanel();
            NetworkPanel    netPanel    = new NetworkPanel();

            // HomePanel (1P / 2P / ONLINE)
            HomePanel homePanel = new HomePanel(
                // 1 PLAYER
                () -> switchPanel(frame, container, cards, singlePanel, "single",
                                  singlePanel::startGame),
                // 2 PLAYER
                () -> switchPanel(frame, container, cards, twoPanel, "two",
                                  twoPanel::startGame),
                // ONLINE → 로비
                () -> {
                    lobbyPanel.resetState();
                    switchPanel(frame, container, cards, lobbyPanel, "lobby", null);
                }
            );

            // ── 단일 콜백: 홈 복귀 ──
            singlePanel.setBackCallback(
                () -> switchPanel(frame, container, cards, homePanel, "home", null)
            );
            twoPanel.setBackCallback(
                () -> switchPanel(frame, container, cards, homePanel, "home", null)
            );

            // ── 로비 콜백 ──
            lobbyPanel.setOnBack(
                () -> switchPanel(frame, container, cards, homePanel, "home", null)
            );
            // 접속 성공 → 방으로
            lobbyPanel.setOnConnected((network, isHost) -> {
                roomPanel.setupConnection(network, isHost);
                switchPanel(frame, container, cards, roomPanel, "room", null);
            });

            // ── 방 콜백 ──
            // LEAVE: 연결 끊고 홈으로
            roomPanel.setOnLeave(
                () -> switchPanel(frame, container, cards, homePanel, "home", null)
            );
            // 모두 READY → 게임으로 (호스트가 START 송신, 모든 클라이언트가 이 콜백 받음)
            roomPanel.setOnStartGame(() -> {
                netPanel.setupConnection(
                        roomPanel.getNetwork(),
                        roomPanel.isHostSide(),
                        roomPanel.getMyPlayerId(),
                        roomPanel.getMaxPlayers(),
                        roomPanel.getPlayers()
                );
                switchPanel(frame, container, cards, netPanel, "online",
                            netPanel::startGame);
            });

            // ── 네트워크 게임 콜백 ──
            // 강제 종료(연결 끊김 등) → 홈
            netPanel.setBackCallback(
                () -> switchPanel(frame, container, cards, homePanel, "home", null)
            );
            // 게임 끝 → 방으로 복귀
            netPanel.setToRoomCallback(() -> {
                switchPanel(frame, container, cards, roomPanel, "room", null);
                roomPanel.onReturnFromGame();
            });

            // 컨테이너에 등록
            container.add(homePanel,   "home");
            container.add(singlePanel, "single");
            container.add(twoPanel,    "two");
            container.add(lobbyPanel,  "lobby");
            container.add(roomPanel,   "room");
            container.add(netPanel,    "online");

            frame.setContentPane(container);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            // 초기: 홈 화면
            switchPanel(frame, container, cards, homePanel, "home", null);
            frame.setVisible(true);
        });
    }

    /**
     * 패널 전환 헬퍼.
     */
    private static void switchPanel(JFrame frame, JPanel container, CardLayout cards,
                                     JPanel targetPanel, String cardName, Runnable onSwitch) {
        cards.show(container, cardName);
        container.setPreferredSize(targetPanel.getPreferredSize());
        frame.pack();
        frame.setLocationRelativeTo(null);
        if (onSwitch != null) {
            onSwitch.run();
        }
    }
}
