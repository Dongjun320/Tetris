package TETRIS;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class TwoPlayerPanel extends JPanel implements KeyListener {

    // ── 레이아웃 상수 ──────────────────────────────
    static final int CELL    = 28;
    static final int BOARD_W = CELL * Board.COLS;  // 260
    static final int BOARD_H = CELL * Board.ROWS;  // 520
    static final int SIDE_W  = 108;
    static final int MINI_C  = 20;
    static final int BY      = 36;                 // 보드 상단 y (레이블 공간)

    static final int P1_BX   = 10;
    static final int P1_SX   = P1_BX + BOARD_W + 8;        // 278
    static final int CTR_X   = P1_SX + SIDE_W;              // 386
    static final int CTR_W   = 180;
    static final int P2_SX   = CTR_X + CTR_W;               // 432
    static final int P2_BX   = P2_SX + SIDE_W + 8;         // 548

    static final int PANEL_W = P2_BX + BOARD_W + 10;       // 818
    static final int PANEL_H = BY + BOARD_H + 40;          // 텍스트 공간 추가

    // ── 플레이어 상태 (내부 클래스) ────────────────
    private class PlayerState {
        Board     board = new Board();
        Tetromino current, next, hold;
        boolean   canHold = true, lastRot = false, alive = true;
        int       score = 0, level = 1, lines = 0, pending = 0;
        String    tMsg = "";
        int       delayedGarbage = 0;        // 지연 중인 쓰레기
        long      garbageTime = 0;           // 쓰레기 받은 시간
        long      lockStartTime = 0;         // 바닥 닿은 시각 (0=공중)
        final PieceBag bag = new PieceBag(); // 7-bag (플레이어마다 독립)

        void reset() {
            board.clear();
            canHold = true; lastRot = false; alive = true;
            score = 0; level = 1; lines = 0; pending = 0; tMsg = "";
            delayedGarbage = 0; garbageTime = 0;
            lockStartTime = 0;
            hold = null;
            bag.reset();
            next = bag.nextPiece();
        }

        /** 쓰레기 적용 후 다음 큐에서 스폰 */
        void spawn() {
            for (int i = 0; i < pending; i++)
                board.addGarbageLine((int)(Math.random() * Board.COLS));
            pending = 0;
            current = next;
            next = bag.nextPiece();
            canHold = true; lastRot = false;
            lockStartTime = 0;
            if (!board.isValidPosition(current)) alive = false;
        }

        /** 홀드 교체 (쓰레기는 적용 안 함) */
        void swapHold() {
            if (!canHold) return;
            canHold = false; lastRot = false;
            if (hold == null) {
                hold = Tetromino.create(current.getType());
                spawn();
            } else {
                Tetromino tmp = Tetromino.create(hold.getType());
                hold    = Tetromino.create(current.getType());
                current = tmp;
                if (!board.isValidPosition(current)) alive = false;
            }
        }

        long getSpeed() { return Math.max(100, 1000 - (level - 1) * 90); }

        void addScore(int cl, boolean ts) {
            lines += cl;
            int[] ls = {0, 100, 300, 500, 800};
            int[] tv = {400, 800, 1200, 1600};
            if (ts)      score += tv[Math.min(cl, 3)] * level;
            else if (cl > 0) score += ls[Math.min(cl, 4)] * level;
            level = lines / 10 + 1;
        }

        int calcGarbage(int cl, boolean ts) {
            // 1줄: 공격 없음, 2줄부터: (줄 - 1) = 공격 (최대 4줄)
            // 2줄=1, 3줄=2, 4줄=3, 5줄+=4... T-스핀은 2배 (최대 4줄)
            if (cl == 1) return 0;
            int base = cl - 1;
            if (ts) base *= 2;
            return Math.min(base, 4);  // 최대 4줄까지
        }
    }

    // ── 필드 ──────────────────────────────────────
    private final PlayerState p1 = new PlayerState();
    private final PlayerState p2 = new PlayerState();
    private Timer   timer;
    private long    t1 = 0, t2 = 0;
    private boolean gameOver = false;
    private int     winner   = 0;
    private Runnable backCallback;

    // ── 초기화 ────────────────────────────────────
    public TwoPlayerPanel() {
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(22, 26, 42));
        setFocusable(true);
        addKeyListener(this);
        timer = new Timer(16, e -> gameTick());
    }

    public void setBackCallback(Runnable r) { backCallback = r; }

    public void startGame() {
        gameOver = false; winner = 0;
        p1.reset(); p2.reset();
        t1 = t2 = System.currentTimeMillis();
        p1.spawn(); p2.spawn();
        timer.restart();
        requestFocusInWindow();
    }

    // ── 게임 루프 ─────────────────────────────────
    private static final long LOCK_DELAY_MS = 500;     // 바닥 닿고 고정까지 유예

    private void gameTick() {
        if (gameOver) { repaint(); return; }
        long now = System.currentTimeMillis();

        // 지연된 쓰레기 2초 후 적용
        if (p1.delayedGarbage > 0 && now - p1.garbageTime >= 2000) {
            for (int i = 0; i < p1.delayedGarbage; i++)
                p1.board.addGarbageLine((int)(Math.random() * Board.COLS));
            p1.delayedGarbage = 0;
        }
        if (p2.delayedGarbage > 0 && now - p2.garbageTime >= 2000) {
            for (int i = 0; i < p2.delayedGarbage; i++)
                p2.board.addGarbageLine((int)(Math.random() * Board.COLS));
            p2.delayedGarbage = 0;
        }

        if (p1.alive) tickPlayer(p1, p2, now);
        if (p2.alive) tickPlayer(p2, p1, now);
        repaint();
    }

    /** 플레이어 1명 진행: lock delay + 자동 낙하 */
    private void tickPlayer(PlayerState p, PlayerState opp, long now) {
        if (isOnGround(p)) {
            if (p.lockStartTime == 0) p.lockStartTime = now;
            if (now - p.lockStartTime >= LOCK_DELAY_MS) {
                land(p, opp);
                p.lockStartTime = 0;
            }
        } else {
            p.lockStartTime = 0;
            long lastDrop = (p == p1) ? t1 : t2;
            if (now - lastDrop >= p.getSpeed()) {
                if (p == p1) t1 = now; else t2 = now;
                p.current.moveDown();
                if (!p.board.isValidPosition(p.current)) {
                    p.current.setY(p.current.getY() - 1);
                    // 바닥 닿음 — 다음 tick에서 lock 시작
                }
            }
        }
    }

    private boolean isOnGround(PlayerState p) {
        p.current.moveDown();
        boolean grounded = !p.board.isValidPosition(p.current);
        p.current.setY(p.current.getY() - 1);
        return grounded;
    }

    private void refreshLock(PlayerState p) {
        if (isOnGround(p)) p.lockStartTime = System.currentTimeMillis();
        else                p.lockStartTime = 0;
    }

    private void land(PlayerState p, PlayerState opp) {
        boolean ts = isTSpin(p);
        p.tMsg = ts ? "T-SPIN!" : "";
        p.board.placePiece(p.current);
        int cl = p.board.clearLines();
        p.addScore(cl, ts);
        int gb = p.calcGarbage(cl, ts);
        // 상대에게 3초 지연 후 쓰레기 추가
        if (gb > 0) {
            opp.delayedGarbage = gb;
            opp.garbageTime = System.currentTimeMillis();
        }
        p.spawn();
        if (!p.alive) { gameOver = true; winner = (p == p1) ? 2 : 1; timer.stop(); }
    }

    // ── T-스핀 ────────────────────────────────────
    private boolean isTSpin(PlayerState p) {
        return p.current.getType() == Tetromino.Type.T && p.lastRot && corners(p) >= 3;
    }
    private int corners(PlayerState p) {
        int px = p.current.getX(), py = p.current.getY(), n = 0;
        for (int[] c : new int[][]{{0,0},{0,2},{2,0},{2,2}}) {
            int r = py+c[0], col = px+c[1];
            if (col<0||col>=Board.COLS||r<0||r>=Board.ROWS||p.board.getCell(r,col)!=null) n++;
        }
        return n;
    }

    // ── 개별 조작 ─────────────────────────────────
    private void ml(PlayerState p)          { p.current.moveLeft();  if (!p.board.isValidPosition(p.current)) p.current.moveRight(); p.lastRot=false; refreshLock(p); }
    private void mr(PlayerState p)          { p.current.moveRight(); if (!p.board.isValidPosition(p.current)) p.current.moveLeft();  p.lastRot=false; refreshLock(p); }
    private void rot(PlayerState p)         { if (p.board.tryRotate(p.current)!=null) { p.lastRot=true; refreshLock(p); } }
    private void sd(PlayerState p, PlayerState o) {
        p.current.moveDown();
        if (!p.board.isValidPosition(p.current)) {
            p.current.setY(p.current.getY()-1);
            refreshLock(p);          // 바닥 → lock 시작 (즉시 land 안 함)
        } else {
            p.lockStartTime = 0;
            if (p == p1) t1 = System.currentTimeMillis(); else t2 = System.currentTimeMillis();
        }
    }
    private void hd(PlayerState p, PlayerState o) { while (p.board.isValidPosition(p.current)) p.current.moveDown(); p.current.setY(p.current.getY()-1); land(p,o); p.lockStartTime = 0; }
    private void hold(PlayerState p) {
        p.swapHold();
        if (!p.alive) { gameOver=true; winner=(p==p1)?2:1; timer.stop(); }
    }

    // ── 키 이벤트 ─────────────────────────────────
    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (gameOver) {
            if (k == KeyEvent.VK_R) startGame();
            if (k == KeyEvent.VK_ESCAPE && backCallback != null) { timer.stop(); SwingUtilities.invokeLater(backCallback); }
            return;
        }
        // P1: WASD + Space(하드드롭) + Q(홀드)
        if (p1.alive) switch (k) {
            case KeyEvent.VK_A:     ml(p1);      repaint(); break;
            case KeyEvent.VK_D:     mr(p1);      repaint(); break;
            case KeyEvent.VK_W:     rot(p1);     repaint(); break;
            case KeyEvent.VK_S:     sd(p1,p2);   repaint(); break;
            case KeyEvent.VK_SPACE: hd(p1,p2);   repaint(); break;
            case KeyEvent.VK_Q:     hold(p1);    repaint(); break;
        }
        // P2: 넘패드 4·6·8·2·0·7
        if (p2.alive) switch (k) {
            case KeyEvent.VK_NUMPAD4: ml(p2);    repaint(); break;
            case KeyEvent.VK_NUMPAD6: mr(p2);    repaint(); break;
            case KeyEvent.VK_NUMPAD8: rot(p2);   repaint(); break;
            case KeyEvent.VK_NUMPAD2: sd(p2,p1); repaint(); break;
            case KeyEvent.VK_NUMPAD0: hd(p2,p1); repaint(); break;
            case KeyEvent.VK_NUMPAD7: hold(p2);  repaint(); break;
        }
        if (k == KeyEvent.VK_ESCAPE && backCallback != null) { timer.stop(); SwingUtilities.invokeLater(backCallback); }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    // ── 렌더링 ────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawPlayerLabel(g2, "PLAYER 1", P1_BX, new Color(80, 160, 255));
        drawPlayerLabel(g2, "PLAYER 2", P2_BX, new Color(200, 80, 255));

        drawBoard(g2, p1, P1_BX);
        drawBoard(g2, p2, P2_BX);

        drawSidePanel(g2, p1, P1_SX);
        drawSidePanel(g2, p2, P2_SX);

        drawCenter(g2);
        drawControls(g2);

        if (gameOver) drawGameOver(g2);
    }

    private void drawPlayerLabel(Graphics2D g, String label, int bx, Color color) {
        g.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, bx + (BOARD_W - fm.stringWidth(label)) / 2, BY - 6);
    }

    private void drawBoard(Graphics2D g, PlayerState p, int bx) {
        // 테두리
        g.setColor(new Color(80, 85, 115));
        g.fillRect(bx-2, BY-2, BOARD_W+4, BOARD_H+4);
        // 배경
        g.setColor(new Color(16, 20, 36));
        g.fillRect(bx, BY, BOARD_W, BOARD_H);
        // 격자
        g.setColor(new Color(35, 40, 60));
        for (int r=0;r<=Board.ROWS;r++) g.drawLine(bx, BY+r*CELL, bx+BOARD_W, BY+r*CELL);
        for (int c=0;c<=Board.COLS;c++) g.drawLine(bx+c*CELL, BY, bx+c*CELL, BY+BOARD_H);
        // 고정 블록
        for (int r=0;r<Board.ROWS;r++)
            for (int c=0;c<Board.COLS;c++) {
                Color col = p.board.getCell(r, c);
                if (col != null) drawCell(g, bx+c*CELL, BY+r*CELL, col, CELL);
            }
        // 고스트
        if (p.current != null) {
            Tetromino ghost = p.current.cloneAt(p.current.getX(), p.current.getY());
            while (p.board.isValidPosition(ghost)) ghost.moveDown();
            ghost.setY(ghost.getY()-1);
            if (ghost.getY() != p.current.getY()) {
                Color base = p.current.getColor();
                Color fill = new Color(base.getRed(),base.getGreen(),base.getBlue(),50);
                Color brd  = new Color(base.getRed(),base.getGreen(),base.getBlue(),130);
                for (int r=0;r<4;r++) for (int c=0;c<4;c++)
                    if (ghost.getShape()[r][c]==1 && ghost.getY()+r>=0) {
                        int px=bx+(ghost.getX()+c)*CELL, py=BY+(ghost.getY()+r)*CELL;
                        g.setColor(fill);  g.fillRect(px+1,py+1,CELL-2,CELL-2);
                        g.setColor(brd);   g.drawRect(px+1,py+1,CELL-3,CELL-3);
                    }
            }
        }
        // 현재 블록
        if (p.current != null) {
            for (int r=0;r<4;r++) for (int c=0;c<4;c++)
                if (p.current.getShape()[r][c]==1 && p.current.getY()+r>=0)
                    drawCell(g, bx+(p.current.getX()+c)*CELL, BY+(p.current.getY()+r)*CELL,
                             p.current.getColor(), CELL);
        }
        // T-스핀 메시지
        if (!p.tMsg.isEmpty()) {
            g.setFont(new Font("맑은 고딕", Font.BOLD, 16));
            g.setColor(new Color(255,220,50));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(p.tMsg, bx+(BOARD_W-fm.stringWidth(p.tMsg))/2, BY+BOARD_H/2-10);
        }
    }

    private void drawSidePanel(Graphics2D g, PlayerState p, int sx) {
        Font lf = new Font("맑은 고딕", Font.BOLD, 11);
        Font vf = new Font("맑은 고딕", Font.BOLD, 17);
        int y = BY;
        // HOLD
        drawMiniBox(g, sx, y,     "HOLD", p.hold,    !p.canHold, lf);
        // NEXT
        drawMiniBox(g, sx, y+98,  "NEXT", p.next,    false,      lf);
        // 점수/레벨/줄
        drawInfoBox(g, sx, y+204, "SCORE", String.valueOf(p.score), lf, vf);
        drawInfoBox(g, sx, y+258, "LEVEL", String.valueOf(p.level), lf, vf);
        drawInfoBox(g, sx, y+312, "LINES", String.valueOf(p.lines), lf, vf);
        // 지연 중인 가비지 표시
        if (p.delayedGarbage > 0) {
            g.setColor(new Color(55, 42, 22));
            g.fillRoundRect(sx, y+374, SIDE_W, 36, 8, 8);
            g.setColor(new Color(255, 150, 30));
            g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
            long elapsed = System.currentTimeMillis() - p.garbageTime;
            int remaining = (int)(3000 - elapsed) / 1000;
            g.drawString("공격 대기 " + p.delayedGarbage, sx+5, y+386);
            g.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            g.drawString(remaining + "초 후 적용", sx+15, y+398);
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
        for (int r=0;r<4;r++) for (int c=0;c<4;c++)
            if (sh[r][c]==1) drawCell(g, x+8+c*MINI_C, y+18+r*MINI_C, col, MINI_C);
    }

    private Color grayOut(Color c) {
        int avg=(c.getRed()+c.getGreen()+c.getBlue())/3;
        return new Color((avg+100)/2,(avg+100)/2,(avg+115)/2);
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

    /** ESC 안내만 하단에 표시 */
    private void drawControls(Graphics2D g) {
        String hint = "ESC: 홈으로";
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(100, 115, 155));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, (PANEL_W - fm.stringWidth(hint)) / 2, BY + BOARD_H + 22);
    }

    /** 중앙 패널: VS + 조작 설명 + 가비지 인디케이터 */
    private void drawCenter(Graphics2D g) {
        int cx = CTR_X + CTR_W / 2;

        // 중앙 배경
        g.setColor(new Color(28, 33, 52));
        g.fillRect(CTR_X, BY, CTR_W, BOARD_H);
        g.setColor(new Color(60, 65, 100));
        g.drawRect(CTR_X, BY, CTR_W, BOARD_H);

        Font kf = new Font("맑은 고딕", Font.BOLD, 11);
        Font df = new Font("맑은 고딕", Font.PLAIN, 11);
        FontMetrics fm;
        int y = BY + 30;

        // ── VS 타이틀 ──
        g.setFont(new Font("맑은 고딕", Font.BOLD, 26));
        fm = g.getFontMetrics();
        g.setColor(new Color(200, 200, 255));
        g.drawString("VS", cx - fm.stringWidth("VS") / 2, y);

        // 구분선
        y += 10;
        drawDivider(g, y);

        // ── PLAYER 1 제목 ──
        y += 16;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        fm = g.getFontMetrics();
        g.setColor(new Color(100, 165, 255));
        g.drawString("PLAYER 1", cx - fm.stringWidth("PLAYER 1") / 2, y);

        y += 14;
        drawKeyRow(g, CTR_X + 10, y, "A / D",  "이동",        kf, df, new Color(160, 200, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "W",      "회전",        kf, df, new Color(160, 200, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "S",      "소프트 드롭", kf, df, new Color(160, 200, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Space",  "하드 드롭",   kf, df, new Color(160, 200, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Q",      "홀드",        kf, df, new Color(160, 200, 255));

        // 구분선
        y += 12;
        drawDivider(g, y);

        // ── PLAYER 2 제목 ──
        y += 16;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        fm = g.getFontMetrics();
        g.setColor(new Color(200, 100, 255));
        g.drawString("PLAYER 2", cx - fm.stringWidth("PLAYER 2") / 2, y);

        y += 14;
        drawKeyRow(g, CTR_X + 10, y, "4 / 6", "이동",        kf, df, new Color(220, 160, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "8",     "회전",        kf, df, new Color(220, 160, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "2",     "소프트 드롭", kf, df, new Color(220, 160, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "0",     "하드 드롭",   kf, df, new Color(220, 160, 255)); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "7",     "홀드",        kf, df, new Color(220, 160, 255));

        // 구분선
        y += 12;
        drawDivider(g, y);

        // ── 가비지 인디케이터 ──
        y += 14;
        if (p1.pending > 0) { drawGarbageIndicator(g, CTR_X + 10, y, p1.pending, false); y += 24; }
        if (p2.pending > 0) { drawGarbageIndicator(g, CTR_X + 10, y, p2.pending, true); }
    }

    private void drawDivider(Graphics2D g, int y) {
        g.setColor(new Color(60, 66, 105));
        g.drawLine(CTR_X + 10, y, CTR_X + CTR_W - 10, y);
    }

    private void drawKeyRow(Graphics2D g, int x, int y, String key, String desc,
                             Font keyFont, Font descFont, Color keyColor) {
        // 키 (왼쪽, 고정 폭 52px)
        g.setFont(keyFont);
        g.setColor(keyColor);
        g.drawString(key, x, y);
        // 설명 (오른쪽)
        g.setFont(descFont);
        g.setColor(new Color(140, 155, 195));
        g.drawString(desc, x + 54, y);
    }

    private void drawGarbageIndicator(Graphics2D g, int x, int y, int count, boolean toRight) {
        int sqSize = 10;
        int boxW = CTR_W - 20;
        g.setColor(new Color(50, 35, 15));
        g.fillRoundRect(x - 2, y - 2, boxW, sqSize + 8, 6, 6);
        for (int i = 0; i < Math.min(count, 6); i++) {
            g.setColor(new Color(255, 130, 30));
            g.fillRect(x + i * (sqSize + 2), y + 2, sqSize, sqSize);
        }
        if (count > 6) {
            g.setFont(new Font("맑은 고딕", Font.BOLD, 9));
            g.setColor(new Color(255, 180, 80));
            g.drawString("+" + (count - 6), x + 6 * (sqSize + 2) + 2, y + 10);
        }
        g.setFont(new Font("맑은 고딕", Font.BOLD, 10));
        g.setColor(new Color(255, 180, 80));
        g.drawString(toRight ? "→ P2" : "← P1", x + boxW - 34, y + 10);
    }

    /** 승패 오버레이 */
    private void drawGameOver(Graphics2D g) {
        // P1 보드 오버레이
        drawWinLoseOverlay(g, P1_BX, winner==1 ? "WIN!" : "LOSE", winner==1);
        // P2 보드 오버레이
        drawWinLoseOverlay(g, P2_BX, winner==2 ? "WIN!" : "LOSE", winner==2);

        // 하단 재시작 안내
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        g.setColor(new Color(180, 185, 210));
        String hint = "R: 다시 하기   ESC: 홈으로";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, (PANEL_W - fm.stringWidth(hint))/2, BY+BOARD_H+26);
    }

    private void drawWinLoseOverlay(Graphics2D g, int bx, String msg, boolean win) {
        g.setColor(new Color(0,0,0, win ? 100 : 150));
        g.fillRect(bx, BY, BOARD_W, BOARD_H);
        g.setFont(new Font("맑은 고딕", Font.BOLD, 36));
        g.setColor(win ? new Color(80,220,80) : new Color(220,70,70));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, bx+(BOARD_W-fm.stringWidth(msg))/2, BY+BOARD_H/2);
    }

    private void drawCell(Graphics2D g, int x, int y, Color color, int size) {
        g.setColor(color);
        g.fillRect(x+1,y+1,size-2,size-2);
        g.setColor(color.brighter());
        g.drawLine(x+1,y+1,x+size-2,y+1);
        g.drawLine(x+1,y+1,x+1,y+size-2);
        g.setColor(color.darker());
        g.drawLine(x+size-2,y+1,x+size-2,y+size-2);
        g.drawLine(x+1,y+size-2,x+size-2,y+size-2);
    }
}
