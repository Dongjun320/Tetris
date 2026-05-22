package TETRIS;

import TETRIS.network.NetMessage;
import TETRIS.network.NetworkManager;

import javax.swing.Timer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * 온라인 2인 멀티 패널.
 *
 * 좌측(P1) = 내 보드  /  우측(P2) = 상대 보드(네트워크로 동기화).
 *
 * <pre>
 * 송신:
 *   - 매 50ms마다 내 STATE 송신 (보드 + 현재 피스 + 점수 등)
 *   - 라인 클리어로 공격 발생 시 GARBAGE 메시지 즉시 송신
 *   - 패배 시 GAMEOVER 송신
 *
 * 수신:
 *   - STATE: 상대 보드 상태 갱신
 *   - GARBAGE: 내 delayedGarbage에 추가 (2초 후 적용)
 *   - GAMEOVER: 상대가 패배 → 나의 승리
 *   - DISCONNECT: 연결 끊김 처리
 * </pre>
 */
public class NetworkPanel extends JPanel implements KeyListener {

    // ── 레이아웃 상수 (TwoPlayerPanel과 동일한 기준) ─────────
    static final int CELL    = 28;
    static final int BOARD_W = CELL * Board.COLS;
    static final int BOARD_H = CELL * Board.ROWS;
    static final int SIDE_W  = 108;
    static final int MINI_C  = 20;
    static final int BY      = 36;

    static final int P1_BX   = 10;
    static final int P1_SX   = P1_BX + BOARD_W + 8;
    static final int CTR_X   = P1_SX + SIDE_W;
    static final int CTR_W   = 180;
    static final int P2_SX   = CTR_X + CTR_W;
    static final int P2_BX   = P2_SX + SIDE_W + 8;

    static final int PANEL_W = P2_BX + BOARD_W + 10;
    static final int PANEL_H = BY + BOARD_H + 40;

    // ── 색상 팔레트 (보드 직렬화에 사용) ────────────────────
    private static final Color GARBAGE_COLOR = new Color(110, 115, 130);
    private static final Color[] PALETTE = {
            null,                          // 0 = 빈
            new Color(0,   240, 240),      // 1 = I
            new Color(240, 240, 0),        // 2 = O
            new Color(160, 0,   240),      // 3 = T
            new Color(0,   240, 0),        // 4 = S
            new Color(240, 0,   0),        // 5 = Z
            new Color(0,   0,   240),      // 6 = J
            new Color(240, 160, 0),        // 7 = L
            GARBAGE_COLOR                  // 8 = 가비지
    };

    // ── 내 플레이어 상태 ───────────────────────────────────
    private final Board   board    = new Board();
    private Tetromino     current, next, hold;
    private boolean       canHold  = true, lastRot = false, alive = true;
    private int           score    = 0, level = 1, lines = 0;
    private String        tMsg     = "";
    private int           delayedGarbage = 0;
    private long          garbageTime    = 0;

    // ── 상대 플레이어 상태 (수신값으로 갱신) ──────────────
    private final int[][] oppBoard = new int[Board.ROWS][Board.COLS];
    private Tetromino.Type oppCurType, oppNextType, oppHoldType;
    private int           oppCurX, oppCurY, oppCurRot;
    private int           oppScore = 0, oppLevel = 1, oppLines = 0;
    private int           oppDelayedGarbage = 0;
    private long          oppGarbageTime    = 0;
    private boolean       oppAlive = true;

    // ── 네트워크/게임 ──────────────────────────────────────
    private NetworkManager  network;
    private boolean         isHost;
    private Timer           timer;
    private long            lastDrop    = 0;
    private long            lastNetSend = 0;
    private boolean         gameOver = false;
    private int             winner = 0;          // 1=나, 2=상대, 0=취소/끊김
    private String          endMessage = "";
    private Runnable        backCallback;        // 홈으로 (연결 종료)
    private Runnable        toRoomCallback;      // 방으로 복귀

    private static final long NET_INTERVAL = 50;   // 20Hz로 상태 송신

    // ── 초기화 ────────────────────────────────────────────
    public NetworkPanel() {
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(22, 26, 42));
        setFocusable(true);
        addKeyListener(this);
        timer = new Timer(16, e -> gameTick());
    }

    public void setBackCallback(Runnable r)   { this.backCallback = r; }
    public void setToRoomCallback(Runnable r) { this.toRoomCallback = r; }

    /** 로비에서 연결이 성공한 직후 호출 */
    public void setupConnection(NetworkManager nm, boolean isHost) {
        this.network = nm;
        this.isHost  = isHost;
        network.setOnMessage(this::onNetMessage);
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));
    }

    /** 게임 시작 */
    public void startGame() {
        gameOver = false; winner = 0; endMessage = "";
        // 내 상태 초기화
        board.clear();
        canHold = true; lastRot = false; alive = true;
        score = 0; level = 1; lines = 0; tMsg = "";
        delayedGarbage = 0; garbageTime = 0;
        hold = null;
        next = Tetromino.createRandom();
        spawnMe();
        // 상대 상태 초기화
        for (int[] row : oppBoard) java.util.Arrays.fill(row, 0);
        oppCurType = oppNextType = oppHoldType = null;
        oppScore = 0; oppLevel = 1; oppLines = 0;
        oppDelayedGarbage = 0; oppAlive = true;

        lastDrop    = System.currentTimeMillis();
        lastNetSend = 0;
        timer.restart();
        requestFocusInWindow();
    }

    private void spawnMe() {
        // 받은 가비지 적용
        for (int i = 0; i < 0; i++) {} // (사용 안 함, 호환용 자리)
        current = next;
        next = Tetromino.createRandom();
        canHold = true; lastRot = false;
        if (!board.isValidPosition(current)) {
            alive = false;
            sendGameOver();
            finishGame(2, "패배");
        }
    }

    // ── 게임 루프 ──────────────────────────────────────────
    private void gameTick() {
        if (gameOver) { repaint(); return; }
        long now = System.currentTimeMillis();

        // 내 가비지 2초 후 적용
        if (delayedGarbage > 0 && now - garbageTime >= 2000) {
            for (int i = 0; i < delayedGarbage; i++)
                board.addGarbageLine((int)(Math.random() * Board.COLS));
            delayedGarbage = 0;
            // 적용 직후 죽었는지 검사
            if (current != null && !board.isValidPosition(current)) {
                alive = false;
                sendGameOver();
                finishGame(2, "패배");
                repaint();
                return;
            }
        }

        // 내 자동 낙하
        if (alive && now - lastDrop >= getSpeed()) {
            lastDrop = now;
            tickDown();
        }

        // 주기적 상태 송신
        if (now - lastNetSend >= NET_INTERVAL) {
            lastNetSend = now;
            sendMyState();
        }
        repaint();
    }

    private void tickDown() {
        current.moveDown();
        if (!board.isValidPosition(current)) {
            current.setY(current.getY() - 1);
            land();
        }
    }

    private void land() {
        boolean ts = isTSpin();
        tMsg = ts ? "T-SPIN!" : "";
        board.placePiece(current);
        int cl = board.clearLines();
        addScore(cl, ts);
        int gb = calcGarbage(cl, ts);
        if (gb > 0) sendGarbage(gb);
        spawnMe();
    }

    private long getSpeed() { return Math.max(100, 1000 - (level - 1) * 90); }

    private void addScore(int cl, boolean ts) {
        lines += cl;
        int[] ls = {0, 100, 300, 500, 800};
        int[] tv = {400, 800, 1200, 1600};
        if (ts)      score += tv[Math.min(cl, 3)] * level;
        else if (cl > 0) score += ls[Math.min(cl, 4)] * level;
        level = lines / 10 + 1;
    }

    private int calcGarbage(int cl, boolean ts) {
        if (cl == 1) return 0;
        int base = cl - 1;
        if (ts) base *= 2;
        return Math.min(base, 4);
    }

    // ── T-스핀 ────────────────────────────────────────────
    private boolean isTSpin() {
        return current.getType() == Tetromino.Type.T && lastRot && corners() >= 3;
    }
    private int corners() {
        int px = current.getX(), py = current.getY(), n = 0;
        for (int[] c : new int[][]{{0,0},{0,2},{2,0},{2,2}}) {
            int r = py + c[0], col = px + c[1];
            if (col < 0 || col >= Board.COLS || r < 0 || r >= Board.ROWS
                    || board.getCell(r, col) != null) n++;
        }
        return n;
    }

    // ── 키 입력 ───────────────────────────────────────────
    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        // 게임 종료 후: 어떤 키든(엔터/스페이스/R) 방으로 복귀 + ESC도 동일
        if (gameOver) {
            if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_R
                    || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) {
                returnToRoom();
            }
            return;
        }
        // 게임 중 ESC: 항복(상대에게 GAMEOVER 송신) 후 방으로
        if (k == KeyEvent.VK_ESCAPE) {
            if (alive) { sendGameOver(); alive = false; }
            timer.stop();
            returnToRoom();
            return;
        }
        if (!alive) return;
        switch (k) {
            case KeyEvent.VK_A:     ml();   repaint(); break;
            case KeyEvent.VK_D:     mr();   repaint(); break;
            case KeyEvent.VK_W:     rot();  repaint(); break;
            case KeyEvent.VK_S:     sd();   repaint(); break;
            case KeyEvent.VK_SPACE: hd();   repaint(); break;
            case KeyEvent.VK_Q:     doHold(); repaint(); break;
        }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    private void ml()  { current.moveLeft();  if (!board.isValidPosition(current)) current.moveRight(); lastRot = false; }
    private void mr()  { current.moveRight(); if (!board.isValidPosition(current)) current.moveLeft();  lastRot = false; }
    private void rot() { if (board.tryRotate(current) != null) lastRot = true; }
    private void sd()  { current.moveDown(); if (!board.isValidPosition(current)) { current.setY(current.getY()-1); land(); } }
    private void hd()  { while (board.isValidPosition(current)) current.moveDown(); current.setY(current.getY()-1); land(); }
    private void doHold() {
        if (!canHold) return;
        canHold = false; lastRot = false;
        if (hold == null) {
            hold = Tetromino.create(current.getType());
            spawnMe();
        } else {
            Tetromino tmp = Tetromino.create(hold.getType());
            hold    = Tetromino.create(current.getType());
            current = tmp;
            if (!board.isValidPosition(current)) {
                alive = false;
                sendGameOver();
                finishGame(2, "패배");
            }
        }
    }

    // ── 네트워크 수신 처리 ─────────────────────────────────
    private void onNetMessage(NetMessage msg) {
        SwingUtilities.invokeLater(() -> handleMessage(msg));
    }

    private void handleMessage(NetMessage msg) {
        switch (msg.type) {
            case STATE:    applyOppState(msg); break;
            case GARBAGE:  receiveGarbage(msg.getInt("lines", 0)); break;
            case GAMEOVER: finishGame(1, "승리!"); break;
            // RESTART/방 관련 메시지는 RoomPanel에서 처리하므로 여기선 무시
            default: break;
        }
    }

    private void applyOppState(NetMessage msg) {
        // 보드
        String b = msg.get("board", "");
        if (b.length() == Board.ROWS * Board.COLS) {
            for (int r = 0; r < Board.ROWS; r++) {
                for (int c = 0; c < Board.COLS; c++) {
                    char ch = b.charAt(r * Board.COLS + c);
                    int code = (ch >= '0' && ch <= '9') ? ch - '0'
                             : (ch >= 'A' && ch <= 'Z') ? 10 + (ch - 'A') : 0;
                    oppBoard[r][c] = Math.min(code, PALETTE.length - 1);
                }
            }
        }
        // 현재 피스
        oppCurType  = parseType(msg.get("cur"));
        oppCurX     = msg.getInt("cx", 0);
        oppCurY     = msg.getInt("cy", 0);
        oppCurRot   = msg.getInt("cr", 0);
        oppNextType = parseType(msg.get("next"));
        oppHoldType = parseType(msg.get("hold"));
        oppScore    = msg.getInt("sc", 0);
        oppLevel    = msg.getInt("lv", 1);
        oppLines    = msg.getInt("ln", 0);
        oppDelayedGarbage = msg.getInt("dg", 0);
        oppGarbageTime    = msg.getLong("gt", 0L) > 0
                ? System.currentTimeMillis() - (System.currentTimeMillis() - msg.getLong("gt", 0L))
                : 0;
        // ↑ 단순화: 상대의 garbageTime은 화면 표시용으로 대략만 사용
        oppAlive = msg.getInt("alive", 1) == 1;
    }

    private Tetromino.Type parseType(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return null;
        try { return Tetromino.Type.valueOf(s); } catch (Exception e) { return null; }
    }

    private void receiveGarbage(int n) {
        if (n <= 0) return;
        delayedGarbage += n;
        garbageTime = System.currentTimeMillis();
    }

    private void onDisconnect() {
        if (gameOver) return;
        finishGame(0, "연결 끊김");
    }

    // ── 네트워크 송신 ──────────────────────────────────────
    private void sendMyState() {
        if (network == null) return;
        StringBuilder b = new StringBuilder(200);
        // 1) 고정 블록 인코딩
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                Color col = board.getCell(r, c);
                b.append((char)('0' + colorToCode(col)));
            }
        }
        NetMessage msg = new NetMessage(NetMessage.Type.STATE)
                .put("board", b.toString())
                .put("cur",  current != null ? current.getType().name() : "-")
                .put("cx",   current != null ? current.getX() : 0)
                .put("cy",   current != null ? current.getY() : 0)
                .put("cr",   current != null ? current.getRotation() : 0)
                .put("next", next  != null ? next.getType().name() : "-")
                .put("hold", hold  != null ? hold.getType().name() : "-")
                .put("sc",   score)
                .put("lv",   level)
                .put("ln",   lines)
                .put("dg",   delayedGarbage)
                .put("gt",   garbageTime)
                .put("alive", alive ? 1 : 0);
        network.send(msg);
    }

    private void sendGarbage(int lines) {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.GARBAGE).put("lines", lines));
    }

    private void sendGameOver() {
        if (network == null) return;
        network.send(new NetMessage(NetMessage.Type.GAMEOVER));
    }

    private int colorToCode(Color c) {
        if (c == null) return 0;
        for (int i = 1; i < PALETTE.length; i++) {
            if (PALETTE[i] != null && PALETTE[i].getRGB() == c.getRGB()) return i;
        }
        // 매칭 안 되면 가비지로 취급
        return 8;
    }

    // ── 종료 처리 ─────────────────────────────────────────
    private void finishGame(int who, String msg) {
        if (gameOver) return;
        gameOver = true;
        winner = who;       // 1=나, 2=상대, 0=취소(끊김)
        endMessage = msg;
        timer.stop();
    }

    private void exitGame() {
        timer.stop();
        if (network != null) { network.close(); network = null; }
        if (backCallback != null) SwingUtilities.invokeLater(backCallback);
    }

    /** 게임 끝 → 방으로 복귀 (연결은 유지). 방에서 다시 준비 누르면 새 게임. */
    private void returnToRoom() {
        timer.stop();
        if (toRoomCallback != null) {
            SwingUtilities.invokeLater(toRoomCallback);
        } else if (backCallback != null) {
            // 폴백: 방 콜백 미설정 시 홈으로
            SwingUtilities.invokeLater(backCallback);
        }
    }

    // ── 렌더링 ────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawPlayerLabel(g2, isHost ? "YOU (HOST)" : "YOU", P1_BX, new Color(80, 160, 255));
        drawPlayerLabel(g2, "OPPONENT",            P2_BX, new Color(200, 80, 255));

        drawMyBoard(g2);
        drawOppBoard(g2);

        drawMySide(g2);
        drawOppSide(g2);

        drawCenter(g2);
        drawBottomHint(g2);

        if (gameOver) drawGameOver(g2);
    }

    private void drawPlayerLabel(Graphics2D g, String label, int bx, Color color) {
        g.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, bx + (BOARD_W - fm.stringWidth(label)) / 2, BY - 6);
    }

    // ── 내 보드 렌더링 ─────────────────────────────────────
    private void drawMyBoard(Graphics2D g) {
        int bx = P1_BX;
        drawBoardBg(g, bx);
        // 고정 블록
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                Color col = board.getCell(r, c);
                if (col != null) drawCell(g, bx + c*CELL, BY + r*CELL, col, CELL);
            }
        }
        // 고스트
        if (current != null) {
            Tetromino ghost = current.cloneAt(current.getX(), current.getY());
            while (board.isValidPosition(ghost)) ghost.moveDown();
            ghost.setY(ghost.getY() - 1);
            if (ghost.getY() != current.getY()) {
                Color base = current.getColor();
                Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 50);
                Color brd  = new Color(base.getRed(), base.getGreen(), base.getBlue(), 130);
                for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                    if (ghost.getShape()[r][c] == 1 && ghost.getY() + r >= 0) {
                        int px = bx + (ghost.getX()+c)*CELL, py = BY + (ghost.getY()+r)*CELL;
                        g.setColor(fill); g.fillRect(px+1, py+1, CELL-2, CELL-2);
                        g.setColor(brd);  g.drawRect(px+1, py+1, CELL-3, CELL-3);
                    }
            }
        }
        // 현재 블록
        if (current != null) {
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                if (current.getShape()[r][c] == 1 && current.getY() + r >= 0)
                    drawCell(g, bx + (current.getX()+c)*CELL, BY + (current.getY()+r)*CELL,
                             current.getColor(), CELL);
        }
        // T-스핀 메시지
        if (!tMsg.isEmpty()) {
            g.setFont(new Font("맑은 고딕", Font.BOLD, 16));
            g.setColor(new Color(255,220,50));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(tMsg, bx + (BOARD_W - fm.stringWidth(tMsg))/2, BY + BOARD_H/2 - 10);
        }
    }

    // ── 상대 보드 렌더링 ───────────────────────────────────
    private void drawOppBoard(Graphics2D g) {
        int bx = P2_BX;
        drawBoardBg(g, bx);
        // 고정 블록
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                int code = oppBoard[r][c];
                if (code > 0 && code < PALETTE.length && PALETTE[code] != null)
                    drawCell(g, bx + c*CELL, BY + r*CELL, PALETTE[code], CELL);
            }
        }
        // 상대 현재 피스 (간단 표시: 색만)
        if (oppCurType != null) {
            Tetromino oppPiece = Tetromino.create(oppCurType);
            oppPiece.setX(oppCurX); oppPiece.setY(oppCurY); oppPiece.setRotation(oppCurRot & 3);
            Color col = oppPiece.getColor();
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                if (oppPiece.getShape()[r][c] == 1 && oppCurY + r >= 0)
                    drawCell(g, bx + (oppCurX+c)*CELL, BY + (oppCurY+r)*CELL, col, CELL);
        }
    }

    private void drawBoardBg(Graphics2D g, int bx) {
        g.setColor(new Color(80, 85, 115));
        g.fillRect(bx-2, BY-2, BOARD_W+4, BOARD_H+4);
        g.setColor(new Color(16, 20, 36));
        g.fillRect(bx, BY, BOARD_W, BOARD_H);
        g.setColor(new Color(35, 40, 60));
        for (int r = 0; r <= Board.ROWS; r++) g.drawLine(bx, BY+r*CELL, bx+BOARD_W, BY+r*CELL);
        for (int c = 0; c <= Board.COLS; c++) g.drawLine(bx+c*CELL, BY, bx+c*CELL, BY+BOARD_H);
    }

    // ── 사이드 패널 ───────────────────────────────────────
    private void drawMySide(Graphics2D g) {
        int sx = P1_SX;
        Font lf = new Font("맑은 고딕", Font.BOLD, 11);
        Font vf = new Font("맑은 고딕", Font.BOLD, 17);
        int y = BY;
        drawMiniBox(g, sx, y,     "HOLD",  hold,   !canHold, lf);
        drawMiniBox(g, sx, y+98,  "NEXT",  next,   false,    lf);
        drawInfoBox(g, sx, y+204, "SCORE", String.valueOf(score), lf, vf);
        drawInfoBox(g, sx, y+258, "LEVEL", String.valueOf(level), lf, vf);
        drawInfoBox(g, sx, y+312, "LINES", String.valueOf(lines), lf, vf);
        // 대기 가비지
        if (delayedGarbage > 0) {
            g.setColor(new Color(55, 42, 22));
            g.fillRoundRect(sx, y+374, SIDE_W, 36, 8, 8);
            g.setColor(new Color(255, 150, 30));
            g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
            int remain = (int)Math.max(0, (2000 - (System.currentTimeMillis() - garbageTime))) / 1000;
            g.drawString("공격 대기 " + delayedGarbage, sx+5, y+386);
            g.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            g.drawString(remain + "초 후 적용", sx+15, y+398);
        }
    }

    private void drawOppSide(Graphics2D g) {
        int sx = P2_SX;
        Font lf = new Font("맑은 고딕", Font.BOLD, 11);
        Font vf = new Font("맑은 고딕", Font.BOLD, 17);
        int y = BY;
        Tetromino oh = oppHoldType != null ? Tetromino.create(oppHoldType) : null;
        Tetromino on = oppNextType != null ? Tetromino.create(oppNextType) : null;
        drawMiniBox(g, sx, y,     "HOLD",  oh, false, lf);
        drawMiniBox(g, sx, y+98,  "NEXT",  on, false, lf);
        drawInfoBox(g, sx, y+204, "SCORE", String.valueOf(oppScore), lf, vf);
        drawInfoBox(g, sx, y+258, "LEVEL", String.valueOf(oppLevel), lf, vf);
        drawInfoBox(g, sx, y+312, "LINES", String.valueOf(oppLines), lf, vf);
        if (oppDelayedGarbage > 0) {
            g.setColor(new Color(55, 42, 22));
            g.fillRoundRect(sx, y+374, SIDE_W, 36, 8, 8);
            g.setColor(new Color(255, 150, 30));
            g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
            g.drawString("공격 대기 " + oppDelayedGarbage, sx+5, y+386);
        }
    }

    private void drawMiniBox(Graphics2D g, int x, int y, String label,
                              Tetromino piece, boolean grayed, Font lf) {
        g.setColor(new Color(55, 60, 88));
        g.fillRoundRect(x, y, SIDE_W, 90, 8, 8);
        g.setColor(new Color(170, 185, 220));
        g.setFont(lf);
        g.drawString(label, x+42, y+14);
        if (piece == null) return;
        Color col = grayed ? grayOut(piece.getColor()) : piece.getColor();
        int[][] sh = piece.getShape();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
            if (sh[r][c] == 1) drawCell(g, x+8+c*MINI_C, y+18+r*MINI_C, col, MINI_C);
    }

    private Color grayOut(Color c) {
        int avg = (c.getRed()+c.getGreen()+c.getBlue())/3;
        return new Color((avg+100)/2, (avg+100)/2, (avg+115)/2);
    }

    private void drawInfoBox(Graphics2D g, int x, int y, String label, String value,
                              Font lf, Font vf) {
        g.setColor(new Color(55, 60, 88));
        g.fillRoundRect(x, y, SIDE_W, 48, 8, 8);
        g.setColor(new Color(170, 185, 220));
        g.setFont(lf);
        g.drawString(label, x+6, y+14);
        g.setColor(Color.WHITE);
        g.setFont(vf);
        g.drawString(value, x+6, y+38);
    }

    // ── 중앙 패널 ─────────────────────────────────────────
    private void drawCenter(Graphics2D g) {
        int cx = CTR_X + CTR_W / 2;

        g.setColor(new Color(28, 33, 52));
        g.fillRect(CTR_X, BY, CTR_W, BOARD_H);
        g.setColor(new Color(60, 65, 100));
        g.drawRect(CTR_X, BY, CTR_W, BOARD_H);

        Font kf = new Font("맑은 고딕", Font.BOLD, 11);
        Font df = new Font("맑은 고딕", Font.PLAIN, 11);
        FontMetrics fm;
        int y = BY + 30;

        // VS
        g.setFont(new Font("맑은 고딕", Font.BOLD, 26));
        fm = g.getFontMetrics();
        g.setColor(new Color(200, 200, 255));
        g.drawString("VS", cx - fm.stringWidth("VS")/2, y);

        y += 10;
        drawDivider(g, y);

        // 내 조작
        y += 18;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        fm = g.getFontMetrics();
        g.setColor(new Color(100, 165, 255));
        String myTitle = "YOUR CONTROLS";
        g.drawString(myTitle, cx - fm.stringWidth(myTitle)/2, y);

        y += 14;
        drawKeyRow(g, CTR_X + 10, y, "A / D",  "이동",        kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "W",      "회전",        kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "S",      "소프트 드롭", kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Space",  "하드 드롭",   kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Q",      "홀드",        kf, df);

        y += 14;
        drawDivider(g, y);

        // 연결 정보
        y += 18;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 11));
        fm = g.getFontMetrics();
        g.setColor(new Color(120, 200, 140));
        String label = isHost ? "● HOSTING" : "● CONNECTED";
        g.drawString(label, cx - fm.stringWidth(label)/2, y);

        y += 18;
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
        fm = g.getFontMetrics();
        g.setColor(new Color(150, 165, 200));
        String tip1 = "2줄+ 클리어 시 공격";
        String tip2 = "최대 4줄, 2초 후 적용";
        g.drawString(tip1, cx - fm.stringWidth(tip1)/2, y); y += 13;
        g.drawString(tip2, cx - fm.stringWidth(tip2)/2, y);
    }

    private void drawDivider(Graphics2D g, int y) {
        g.setColor(new Color(60, 66, 105));
        g.drawLine(CTR_X + 10, y, CTR_X + CTR_W - 10, y);
    }

    private void drawKeyRow(Graphics2D g, int x, int y, String key, String desc, Font kf, Font df) {
        g.setFont(kf);
        g.setColor(new Color(160, 200, 255));
        g.drawString(key, x, y);
        g.setFont(df);
        g.setColor(new Color(140, 155, 195));
        g.drawString(desc, x + 54, y);
    }

    private void drawBottomHint(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(100, 115, 155));
        String hint = "ESC: 항복 후 방으로";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, (PANEL_W - fm.stringWidth(hint))/2, BY + BOARD_H + 22);
    }

    // ── 게임 종료 화면 ────────────────────────────────────
    private void drawGameOver(Graphics2D g) {
        boolean win = winner == 1;
        boolean lose = winner == 2;
        // 내 보드 오버레이
        drawWinLoseOverlay(g, P1_BX, win ? "WIN!" : (lose ? "LOSE" : "END"), win);
        // 상대 보드 오버레이
        drawWinLoseOverlay(g, P2_BX, lose ? "WIN!" : (win ? "LOSE" : "END"), lose);

        g.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        g.setColor(new Color(180, 185, 210));
        String hint = "아무 키 → 방으로 돌아가기";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, (PANEL_W - fm.stringWidth(hint))/2, BY + BOARD_H + 26);
    }

    private void drawWinLoseOverlay(Graphics2D g, int bx, String msg, boolean win) {
        g.setColor(new Color(0, 0, 0, win ? 100 : 150));
        g.fillRect(bx, BY, BOARD_W, BOARD_H);
        g.setFont(new Font("맑은 고딕", Font.BOLD, 36));
        g.setColor(win ? new Color(80, 220, 80) : new Color(220, 70, 70));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, bx + (BOARD_W - fm.stringWidth(msg))/2, BY + BOARD_H/2);
    }

    private void drawCell(Graphics2D g, int x, int y, Color color, int size) {
        g.setColor(color);
        g.fillRect(x+1, y+1, size-2, size-2);
        g.setColor(color.brighter());
        g.drawLine(x+1, y+1, x+size-2, y+1);
        g.drawLine(x+1, y+1, x+1, y+size-2);
        g.setColor(color.darker());
        g.drawLine(x+size-2, y+1, x+size-2, y+size-2);
        g.drawLine(x+1, y+size-2, x+size-2, y+size-2);
    }
}
