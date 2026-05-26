package TETRIS;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class GamePanel extends JPanel implements KeyListener {

    // ── 레이아웃 상수 ──────────────────────────────
    private static final int CELL = 33;  // 블록 크기 증가 (30→33)
    private static final int BX   = 10;   // 보드 왼쪽 여백
    private static final int BY   = 10;   // 보드 위쪽 여백
    private static final int SX   = BX + Board.COLS * CELL + 20;  // 사이드패널 X

    // ── 게임 객체 ──────────────────────────────────
    private Board       board;
    private Tetromino   current;
    private Tetromino   next;
    private Tetromino   holdPiece;          // 홀드 칸
    private boolean     canHold;            // 이번 블록에서 홀드 가능 여부
    private boolean     lastActionWasRotation;  // T-스핀 판정용
    private String      tSpinMsg = "";      // 화면에 표시할 T-스핀 메시지
    private GameManager gm;
    private Timer       timer;
    private Runnable    backCallback;       // ESC 시 홈으로 돌아가기
    private final PieceBag bag = new PieceBag();   // 7-bag 무작위기
    private long lastDrop      = 0;          // 마지막 자동 낙하 시각
    private long lockStartTime = 0;          // 바닥 닿은 시각 (0=공중)
    private static final long LOCK_DELAY_MS = 500;

    // ── 초기화 ────────────────────────────────────
    public GamePanel() {
        setPreferredSize(new Dimension(SX + 160, BY * 2 + Board.ROWS * CELL));
        setBackground(new Color(28, 32, 48));
        setFocusable(true);
        addKeyListener(this);

        board = new Board();
        gm    = GameManager.getInstance();
        timer = new Timer(16, e -> tick());     // 60Hz로 호출, 내부에서 lastDrop으로 낙하 관리
    }

    public void startGame() {
        gm.reset();
        board.clear();
        holdPiece             = null;
        canHold               = true;
        lastActionWasRotation = false;
        tSpinMsg              = "";
        bag.reset();
        next = bag.nextPiece();
        lastDrop              = System.currentTimeMillis();
        lockStartTime         = 0;
        spawnPiece();
        timer.start();
        requestFocusInWindow();
    }

    private void spawnPiece() {
        current               = next;
        next                  = bag.nextPiece();
        canHold               = true;
        lastActionWasRotation = false;
        lockStartTime         = 0;
        lastDrop              = System.currentTimeMillis();
        // 스폰 위치가 겹쳐도 즉시 죽이지 않음 → land() 시점에 top-out 체크
    }

    // ── 게임 루프 ─────────────────────────────────
    private void tick() {
        if (gm.isGameOver() || gm.isPaused()) return;
        long now = System.currentTimeMillis();

        if (isOnGround()) {
            if (lockStartTime == 0) lockStartTime = now;
            if (now - lockStartTime >= LOCK_DELAY_MS) {
                land();
                lockStartTime = 0;
            }
        } else {
            lockStartTime = 0;
            if (now - lastDrop >= gm.getSpeed()) {
                lastDrop = now;
                current.moveDown();
                if (!board.isValidPosition(current)) {
                    current.setY(current.getY() - 1);
                    // 바닥 닿음 — 다음 tick에서 lock 시작
                }
            }
        }
        repaint();
    }

    private boolean isOnGround() {
        current.moveDown();
        boolean grounded = !board.isValidPosition(current);
        current.setY(current.getY() - 1);
        return grounded;
    }

    private void refreshLock() {
        if (isOnGround()) lockStartTime = System.currentTimeMillis();
        else              lockStartTime = 0;
    }

    private void land() {
        boolean tSpin = isTSpin();
        tSpinMsg = tSpin ? "T-SPIN!" : "";
        board.placePiece(current);
        int lines = board.clearLines();
        gm.addScore(lines, tSpin);
        // 라인 제거 후 다음 피스가 스폰 가능한지 체크 (top-out 판정)
        Tetromino testNext = Tetromino.create(next.getType());
        if (!board.isValidPosition(testNext)) {
            gm.setGameOver(true);
            timer.stop();
        } else {
            spawnPiece();
        }
    }

    // ── T-스핀 판정 ───────────────────────────────
    /**
     * T 피스가 마지막 회전 후 착지 시,
     * 3x3 박스의 4 코너 중 3개 이상 막혀 있으면 T-스핀.
     */
    private boolean isTSpin() {
        return current.getType() == Tetromino.Type.T
            && lastActionWasRotation
            && countTCorners() >= 3;
    }

    private int countTCorners() {
        int px = current.getX();
        int py = current.getY();
        // T 피스 3x3 박스의 네 모서리 (row 오프셋, col 오프셋)
        int[][] corners = { {0,0}, {0,2}, {2,0}, {2,2} };
        int count = 0;
        for (int[] c : corners) {
            int row = py + c[0];
            int col = px + c[1];
            // 보드 밖(벽) 또는 블록이 있으면 '막힘'으로 카운트
            if (col < 0 || col >= Board.COLS || row < 0 || row >= Board.ROWS
                    || board.getCell(row, col) != null) {
                count++;
            }
        }
        return count;
    }

    // ── 조작 ─────────────────────────────────────
    private void moveLeft() {
        current.moveLeft();
        if (!board.isValidPosition(current)) current.moveRight();
        lastActionWasRotation = false;  // 이동 시 T-스핀 판정 리셋
        refreshLock();
        repaint();
    }

    private void moveRight() {
        current.moveRight();
        if (!board.isValidPosition(current)) current.moveLeft();
        lastActionWasRotation = false;
        refreshLock();
        repaint();
    }

    private void rotate() {
        int[] kick = board.tryRotate(current);  // SRS 벽 차기 포함 회전
        if (kick != null) {
            lastActionWasRotation = true;
            refreshLock();
        }
        repaint();
    }

    private void softDrop() {
        current.moveDown();
        if (!board.isValidPosition(current)) {
            current.setY(current.getY() - 1);
            refreshLock();          // 바닥 → lock 시작 (즉시 land 안 함)
        } else {
            lockStartTime = 0;
            lastDrop = System.currentTimeMillis();
        }
        repaint();
    }

    private void hardDrop() {
        while (board.isValidPosition(current)) current.moveDown();
        current.setY(current.getY() - 1);
        land();
        lockStartTime = 0;
        repaint();
    }

    /** H 키: 현재 블록을 홀드 칸에 저장/교체 */
    private void hold() {
        if (!canHold) return;
        canHold               = false;
        lastActionWasRotation = false;
        if (holdPiece == null) {
            holdPiece = Tetromino.create(current.getType());
            spawnPiece();
        } else {
            Tetromino swap = Tetromino.create(holdPiece.getType());
            holdPiece = Tetromino.create(current.getType());
            current   = swap;
            // 홀드 교체 후 겹쳐도 즉시 죽이지 않음 → land 시 체크
        }
        repaint();
    }

    // ── 렌더링 ────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBoard(g2);
        if (current != null) {
            drawGhost(g2);
            drawPiece(g2, current, current.getColor());
        }
        drawSidePanel(g2);
        drawTSpinMsg(g2);

        if (gm.isGameOver()) drawOverlay(g2, "GAME OVER", new Color(255, 70, 70), "R 키를 눌러 재시작");
        if (gm.isPaused())   drawOverlay(g2, "PAUSED",    Color.YELLOW,           "P 키를 눌러 계속");
    }

    private void drawBoard(Graphics2D g) {
        // 테두리
        g.setColor(new Color(85, 90, 120));
        g.fillRect(BX - 2, BY - 2, Board.COLS * CELL + 4, Board.ROWS * CELL + 4);
        // 배경
        g.setColor(new Color(18, 22, 38));
        g.fillRect(BX, BY, Board.COLS * CELL, Board.ROWS * CELL);
        // 격자선 (약간 밝게)
        g.setColor(new Color(40, 45, 65));
        for (int r = 0; r <= Board.ROWS; r++)
            g.drawLine(BX, BY + r * CELL, BX + Board.COLS * CELL, BY + r * CELL);
        for (int c = 0; c <= Board.COLS; c++)
            g.drawLine(BX + c * CELL, BY, BX + c * CELL, BY + Board.ROWS * CELL);
        // 고정된 블록
        for (int r = 0; r < Board.ROWS; r++)
            for (int c = 0; c < Board.COLS; c++) {
                Color col = board.getCell(r, c);
                if (col != null) drawCell(g, BX + c * CELL, BY + r * CELL, col);
            }
    }

    private void drawPiece(Graphics2D g, Tetromino piece, Color color) {
        int[][] shape = piece.getShape();
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                if (shape[r][c] == 1 && piece.getY() + r >= 0)
                    drawCell(g, BX + (piece.getX() + c) * CELL, BY + (piece.getY() + r) * CELL, color);
    }

    private void drawGhost(Graphics2D g) {
        Tetromino ghost = current.cloneAt(current.getX(), current.getY());
        while (board.isValidPosition(ghost)) ghost.moveDown();
        ghost.setY(ghost.getY() - 1);
        if (ghost.getY() == current.getY()) return;

        Color base   = current.getColor();
        Color fill   = new Color(base.getRed(), base.getGreen(), base.getBlue(), 55);
        Color border = new Color(base.getRed(), base.getGreen(), base.getBlue(), 140);
        int[][] shape = ghost.getShape();
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                if (shape[r][c] == 1 && ghost.getY() + r >= 0) {
                    int px = BX + (ghost.getX() + c) * CELL;
                    int py = BY + (ghost.getY() + r) * CELL;
                    g.setColor(fill);   g.fillRect(px + 1, py + 1, CELL - 2, CELL - 2);
                    g.setColor(border); g.drawRect(px + 1, py + 1, CELL - 3, CELL - 3);
                }
    }

    private void drawCell(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.fillRect(x + 1, y + 1, CELL - 2, CELL - 2);
        g.setColor(color.brighter());
        g.drawLine(x + 1,      y + 1,       x + CELL - 2, y + 1);
        g.drawLine(x + 1,      y + 1,       x + 1,        y + CELL - 2);
        g.setColor(color.darker());
        g.drawLine(x + CELL - 2, y + 1,       x + CELL - 2, y + CELL - 2);
        g.drawLine(x + 1,        y + CELL - 2, x + CELL - 2, y + CELL - 2);
    }

    // ── 사이드 패널 ───────────────────────────────
    private void drawSidePanel(Graphics2D g) {
        Font labelF = new Font("맑은 고딕", Font.BOLD, 12);
        Font valueF = new Font("맑은 고딕", Font.BOLD, 20);
        int sy = BY;

        // HOLD 박스
        drawMiniBox(g, SX, sy,       "HOLD", holdPiece, !canHold, labelF);
        // NEXT 박스
        drawMiniBox(g, SX, sy + 112, "NEXT", next,      false,    labelF);

        // 점수/레벨/줄
        int iy = sy + 228;
        drawInfoBox(g, SX, iy,       "SCORE", String.valueOf(gm.getScore()),      labelF, valueF);
        drawInfoBox(g, SX, iy + 68,  "LEVEL", String.valueOf(gm.getLevel()),      labelF, valueF);
        drawInfoBox(g, SX, iy + 136, "LINES", String.valueOf(gm.getTotalLines()), labelF, valueF);

        // 조작법
        int cy = iy + 212;
        g.setColor(new Color(60, 66, 95));
        g.fillRoundRect(SX, cy, 150, 155, 10, 10);
        g.setColor(new Color(185, 195, 225));
        g.setFont(labelF);
        g.drawString("CONTROLS", SX + 35, cy + 17);
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(210, 215, 235));
        String[] keys = { "← → : 이동", "↑    : 회전", "↓    : 천천히",
                           "SPACE: 즉시낙하", "H    : 홀드", "P    : 일시정지", "R    : 재시작" };
        for (int i = 0; i < keys.length; i++)
            g.drawString(keys[i], SX + 12, cy + 34 + i * 18);
    }

    /** HOLD / NEXT 미리보기 박스 */
    private void drawMiniBox(Graphics2D g, int x, int y, String label,
                              Tetromino piece, boolean grayed, Font labelF) {
        g.setColor(new Color(60, 66, 95));
        g.fillRoundRect(x, y, 150, 105, 10, 10);
        g.setColor(new Color(185, 195, 225));
        g.setFont(labelF);
        g.drawString(label, x + 60, y + 16);

        if (piece != null) {
            Color color = grayed ? grayOut(piece.getColor()) : piece.getColor();
            int[][] shape = piece.getShape();
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    if (shape[r][c] == 1)
                        drawCell(g, x + 14 + c * 22, y + 22 + r * 22, color);
        }
    }

    /** 홀드 잠금 시 블록을 회색조로 */
    private Color grayOut(Color c) {
        int avg = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        int r = (avg + 100) / 2;
        int gv = (avg + 100) / 2;
        int b = (avg + 120) / 2;
        return new Color(r, gv, b);
    }

    private void drawInfoBox(Graphics2D g, int x, int y, String label, String value,
                              Font lf, Font vf) {
        g.setColor(new Color(60, 66, 95));
        g.fillRoundRect(x, y, 150, 58, 10, 10);
        g.setColor(new Color(185, 195, 225));
        g.setFont(lf);
        g.drawString(label, x + 10, y + 17);
        g.setColor(Color.WHITE);
        g.setFont(vf);
        g.drawString(value, x + 10, y + 45);
    }

    /** T-스핀 발생 시 화면 중앙에 메시지 표시 */
    private void drawTSpinMsg(Graphics2D g) {
        if (tSpinMsg.isEmpty()) return;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 20));
        g.setColor(new Color(255, 220, 50));
        FontMetrics fm = g.getFontMetrics();
        int bw = Board.COLS * CELL;
        g.drawString(tSpinMsg, BX + (bw - fm.stringWidth(tSpinMsg)) / 2,
                               BY + Board.ROWS * CELL / 2 - 20);
    }

    private void drawOverlay(Graphics2D g, String title, Color titleColor, String sub) {
        int bw = Board.COLS * CELL;
        int bh = Board.ROWS * CELL;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(BX, BY, bw, bh);
        g.setFont(new Font("맑은 고딕", Font.BOLD, 26));
        g.setColor(titleColor);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, BX + (bw - fm.stringWidth(title)) / 2, BY + bh / 2 - 10);
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
        g.setColor(new Color(210, 210, 210));
        fm = g.getFontMetrics();
        g.drawString(sub, BX + (bw - fm.stringWidth(sub)) / 2, BY + bh / 2 + 22);
    }

    // ── 키 이벤트 ─────────────────────────────────
    @Override
    public void keyPressed(KeyEvent e) {
        if (gm.isGameOver()) {
            if (e.getKeyCode() == KeyEvent.VK_R) startGame();
            return;
        }
        boolean playing = !gm.isPaused();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT:  if (playing) moveLeft();   break;
            case KeyEvent.VK_RIGHT: if (playing) moveRight();  break;
            case KeyEvent.VK_UP:    if (playing) rotate();     break;
            case KeyEvent.VK_DOWN:  if (playing) softDrop();   break;
            case KeyEvent.VK_SPACE: if (playing) hardDrop();   break;
            case KeyEvent.VK_H:     if (playing) hold();       break;
            case KeyEvent.VK_P:      gm.togglePause(); repaint(); break;
            case KeyEvent.VK_R:      startGame(); break;
            case KeyEvent.VK_ESCAPE:
                timer.stop();
                if (backCallback != null) SwingUtilities.invokeLater(backCallback);
                break;
        }
    }

    public void setBackCallback(Runnable r) { this.backCallback = r; }

    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}
}
