package TETRIS;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
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
    private long lastDrop      = 0;
    private long lockStartTime = 0;
    private static final long LOCK_DELAY_MS = 500;

    // ── 이펙트 ────────────────────────────────────
    private int[]       clearFlashRows = new int[0];   // 라인 클리어 플래시
    private long        clearFlashTime = 0;
    private static final long CLEAR_FLASH_MS = 140;

    private List<int[]> dropTrail      = new ArrayList<>();  // 하드드롭 잔상
    private Color       dropTrailColor = null;
    private long        dropTrailTime  = 0;
    private static final long DROP_TRAIL_MS = 150;

    private List<int[]> lockFlash      = new ArrayList<>();  // 고정 플래시
    private long        lockFlashTime  = 0;
    private static final long LOCK_FLASH_MS = 100;

    // ── 귀여운 UI 팔레트 (중간 밝기) ─────────────
    private static final Color BG_TOP   = new Color(198, 194, 236);
    private static final Color BG_BOT   = new Color(180, 200, 234);
    private static final Color BOARD_BG = new Color(224, 226, 242);
    private static final Color BOARD_BD = new Color(128, 118, 185);
    private static final Color GRID_C   = new Color(190, 200, 228);
    private static final Color BOX_BG1  = new Color(208, 218, 248);
    private static final Color BOX_BG2  = new Color(194, 208, 238);
    private static final Color BOX_BD   = new Color(136, 152, 208);
    private static final Color LBL_C    = new Color(85, 102, 170);
    private static final Color VAL_C    = new Color(38, 52, 158);
    private static final Color CTL_C    = new Color(72, 88, 158);
    private static final int   CELL_ARC = 8;
    private static final int   BOX_ARC  = 12;

    // ── 초기화 ────────────────────────────────────
    public GamePanel() {
        setPreferredSize(new Dimension(SX + 160, BY * 2 + Board.ROWS * CELL));
        setBackground(new Color(194, 190, 230));
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

        // [이펙트] 고정 플래시: 현재 피스 셀 기록
        lockFlash.clear();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (current.getShape()[r][c] == 1 && current.getY()+r >= 0)
                lockFlash.add(new int[]{current.getY()+r, current.getX()+c});
        }
        lockFlashTime = System.currentTimeMillis();

        board.placePiece(current);

        // [이펙트] 라인 플래시: 지워질 행 기록 (clearLines 전에)
        int[] fullRows = board.getFullRowIndices();
        if (fullRows.length > 0) {
            clearFlashRows = fullRows;
            clearFlashTime = System.currentTimeMillis();
        }

        int lines = board.clearLines();
        gm.addScore(lines, tSpin);

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
        // [이펙트] 잔상: 시작 위치 ~ 최종 위치 사이 셀 기록
        dropTrail.clear();
        dropTrailColor = current.getColor();
        int startY = current.getY();
        int[][] shape = current.getShape();
        Tetromino ghost = current.cloneAt(current.getX(), current.getY());
        while (board.isValidPosition(ghost)) ghost.moveDown();
        ghost.setY(ghost.getY() - 1);
        int endY = ghost.getY();
        for (int dy = startY; dy < endY; dy++) {
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                if (shape[r][c] == 1 && dy+r >= 0)
                    dropTrail.add(new int[]{dy+r, current.getX()+c});
            }
        }
        dropTrailTime = System.currentTimeMillis();

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

        drawBg(g2);
        drawBoard(g2);
        if (current != null) {
            drawGhost(g2);
            drawPiece(g2, current, current.getColor());
        }
        drawEffects(g2);
        drawSidePanel(g2);
        drawTSpinMsg(g2);

        if (gm.isGameOver()) drawOverlay(g2, "GAME OVER", new Color(215, 45, 75),  "R 키를 눌러 재시작");
        if (gm.isPaused())   drawOverlay(g2, "PAUSED",    new Color(50, 95, 215),  "P 키를 눌러 계속");
    }

    private void drawBg(Graphics2D g) {
        GradientPaint gp = new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOT);
        g.setPaint(gp);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setPaint(null);
    }

    private void drawBoard(Graphics2D g) {
        int bw = Board.COLS * CELL, bh = Board.ROWS * CELL;
        // 그림자
        g.setColor(new Color(0, 0, 0, 22));
        g.fillRoundRect(BX + 4, BY + 4, bw + 4, bh + 4, BOX_ARC, BOX_ARC);
        // 테두리
        g.setColor(BOARD_BD);
        g.fillRoundRect(BX - 2, BY - 2, bw + 4, bh + 4, BOX_ARC, BOX_ARC);
        // 배경 (밝은 흰색 계열)
        g.setColor(BOARD_BG);
        g.fillRect(BX, BY, bw, bh);
        // 격자선 (연한 파스텔)
        g.setColor(GRID_C);
        for (int r = 0; r <= Board.ROWS; r++)
            g.drawLine(BX, BY + r * CELL, BX + bw, BY + r * CELL);
        for (int c = 0; c <= Board.COLS; c++)
            g.drawLine(BX + c * CELL, BY, BX + c * CELL, BY + bh);
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
        Color fill   = new Color(base.getRed(), base.getGreen(), base.getBlue(), 28);
        Color border = new Color(base.getRed(), base.getGreen(), base.getBlue(), 178);
        int[][] shape = ghost.getShape();
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                if (shape[r][c] == 1 && ghost.getY() + r >= 0) {
                    int px = BX + (ghost.getX() + c) * CELL;
                    int py = BY + (ghost.getY() + r) * CELL;
                    g.setColor(fill);
                    g.fillRoundRect(px + 1, py + 1, CELL - 2, CELL - 2, CELL_ARC, CELL_ARC);
                    g.setColor(border);
                    g.setStroke(new BasicStroke(1.8f));
                    g.drawRoundRect(px + 1, py + 1, CELL - 2, CELL - 2, CELL_ARC, CELL_ARC);
                    g.setStroke(new BasicStroke(1.0f));
                }
    }

    // ── 이펙트 렌더링 ─────────────────────────────
    private void drawEffects(Graphics2D g) {
        long now = System.currentTimeMillis();

        // 1. 하드드롭 잔상
        if (dropTrailColor != null && !dropTrail.isEmpty()) {
            long elapsed = now - dropTrailTime;
            if (elapsed < DROP_TRAIL_MS) {
                float alpha = 0.55f * (1.0f - (float) elapsed / DROP_TRAIL_MS);
                Color tc = new Color(dropTrailColor.getRed(), dropTrailColor.getGreen(),
                        dropTrailColor.getBlue(), (int)(alpha * 255));
                g.setColor(tc);
                for (int[] cell : dropTrail)
                    g.fillRoundRect(BX + cell[1]*CELL + 1, BY + cell[0]*CELL + 1, CELL-2, CELL-2, CELL_ARC, CELL_ARC);
            } else { dropTrail.clear(); }
        }

        // 2. 고정 플래시 (밝은 파란빛)
        if (!lockFlash.isEmpty()) {
            long elapsed = now - lockFlashTime;
            if (elapsed < LOCK_FLASH_MS) {
                float alpha = 0.62f * (1.0f - (float) elapsed / LOCK_FLASH_MS);
                g.setColor(new Color(155, 195, 255, (int)(alpha * 255)));
                for (int[] cell : lockFlash)
                    g.fillRoundRect(BX + cell[1]*CELL, BY + cell[0]*CELL, CELL, CELL, CELL_ARC, CELL_ARC);
            } else { lockFlash.clear(); }
        }

        // 3. 라인 클리어 플래시 (황금빛)
        if (clearFlashRows.length > 0) {
            long elapsed = now - clearFlashTime;
            if (elapsed < CLEAR_FLASH_MS) {
                float alpha = 0.88f * (1.0f - (float) elapsed / CLEAR_FLASH_MS);
                g.setColor(new Color(255, 210, 50, (int)(alpha * 255)));
                for (int row : clearFlashRows)
                    g.fillRect(BX, BY + row * CELL, Board.COLS * CELL, CELL);
            } else { clearFlashRows = new int[0]; }
        }
    }

    private void drawCell(Graphics2D g, int x, int y, Color color) {
        // 그라데이션: 위쪽 밝게, 아래쪽 원색
        GradientPaint gp = new GradientPaint(x, y + 1, lighter(color, 0.35f), x, y + CELL - 1, color);
        g.setPaint(gp);
        g.fillRoundRect(x + 1, y + 1, CELL - 2, CELL - 2, CELL_ARC, CELL_ARC);
        // 상단 광택 스트라이프
        g.setColor(new Color(255, 255, 255, 108));
        g.fillRoundRect(x + 2, y + 2, CELL - 4, (CELL - 2) / 3, CELL_ARC, CELL_ARC);
        // 미세 아웃라인
        g.setPaint(null);
        g.setColor(new Color(0, 0, 0, 28));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(x + 1, y + 1, CELL - 2, CELL - 2, CELL_ARC, CELL_ARC);
    }

    /** 색을 흰색 방향으로 t 비율만큼 밝힘 */
    private Color lighter(Color c, float t) {
        return new Color(
            Math.min(255, (int)(c.getRed()   + (255 - c.getRed())   * t)),
            Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * t)),
            Math.min(255, (int)(c.getBlue()  + (255 - c.getBlue())  * t))
        );
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
        g.setColor(new Color(0, 0, 0, 18));
        g.fillRoundRect(SX + 2, cy + 2, 150, 155, BOX_ARC, BOX_ARC);
        GradientPaint gpC = new GradientPaint(SX, cy, BOX_BG1, SX, cy + 155, BOX_BG2);
        g.setPaint(gpC);
        g.fillRoundRect(SX, cy, 150, 155, BOX_ARC, BOX_ARC);
        g.setPaint(null);
        g.setColor(BOX_BD);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(SX, cy, 150, 155, BOX_ARC, BOX_ARC);
        g.setColor(LBL_C);
        g.setFont(labelF);
        g.drawString("CONTROLS", SX + 30, cy + 17);
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(CTL_C);
        KeyBinding kb = KeyBinding.getSingle();
        String[] keys = {
            KeyBinding.keyName(kb.left) + "/" + KeyBinding.keyName(kb.right) + " : 이동",
            KeyBinding.keyName(kb.rotate)   + " : 회전",
            KeyBinding.keyName(kb.softDrop) + " : 천천히",
            KeyBinding.keyName(kb.hardDrop) + " : 즉시낙하",
            KeyBinding.keyName(kb.hold)     + " : 홀드",
            "P : 일시정지",
            "R : 재시작"
        };
        for (int i = 0; i < keys.length; i++)
            g.drawString(keys[i], SX + 12, cy + 34 + i * 18);
    }

    /** HOLD / NEXT 미리보기 박스 */
    private void drawMiniBox(Graphics2D g, int x, int y, String label,
                              Tetromino piece, boolean grayed, Font labelF) {
        // 그림자
        g.setColor(new Color(0, 0, 0, 18));
        g.fillRoundRect(x + 2, y + 2, 150, 105, BOX_ARC, BOX_ARC);
        // 배경
        GradientPaint gp = new GradientPaint(x, y, BOX_BG1, x, y + 105, BOX_BG2);
        g.setPaint(gp);
        g.fillRoundRect(x, y, 150, 105, BOX_ARC, BOX_ARC);
        // 테두리
        g.setPaint(null);
        g.setColor(BOX_BD);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(x, y, 150, 105, BOX_ARC, BOX_ARC);
        // 레이블
        g.setColor(LBL_C);
        g.setFont(labelF);
        FontMetrics fmM = g.getFontMetrics();
        g.drawString(label, x + (150 - fmM.stringWidth(label)) / 2, y + 16);

        if (piece != null) {
            Color color = grayed ? grayOut(piece.getColor()) : piece.getColor();
            int[][] shape = piece.getShape();
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    if (shape[r][c] == 1)
                        drawCell(g, x + 14 + c * 22, y + 22 + r * 22, color);
        }
    }

    /** 홀드 잠금 시 블록을 밝은 회색조로 */
    private Color grayOut(Color c) {
        int avg = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        int v = (avg + 165) / 2;
        return new Color(v, v, Math.min(255, v + 8));
    }

    private void drawInfoBox(Graphics2D g, int x, int y, String label, String value,
                              Font lf, Font vf) {
        // 그림자
        g.setColor(new Color(0, 0, 0, 18));
        g.fillRoundRect(x + 2, y + 2, 150, 58, BOX_ARC, BOX_ARC);
        // 배경
        GradientPaint gp = new GradientPaint(x, y, BOX_BG1, x, y + 58, BOX_BG2);
        g.setPaint(gp);
        g.fillRoundRect(x, y, 150, 58, BOX_ARC, BOX_ARC);
        // 테두리
        g.setPaint(null);
        g.setColor(BOX_BD);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(x, y, 150, 58, BOX_ARC, BOX_ARC);
        // 텍스트
        g.setColor(LBL_C);
        g.setFont(lf);
        g.drawString(label, x + 10, y + 17);
        g.setColor(VAL_C);
        g.setFont(vf);
        g.drawString(value, x + 10, y + 45);
    }

    /** T-스핀 발생 시 화면 중앙에 메시지 표시 */
    private void drawTSpinMsg(Graphics2D g) {
        if (tSpinMsg.isEmpty()) return;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        int bw = Board.COLS * CELL;
        FontMetrics fm = g.getFontMetrics();
        int tx = BX + (bw - fm.stringWidth(tSpinMsg)) / 2;
        int ty = BY + Board.ROWS * CELL / 2 - 20;
        g.setColor(new Color(0, 0, 0, 70));
        g.drawString(tSpinMsg, tx + 2, ty + 2);
        g.setColor(new Color(240, 148, 18));
        g.drawString(tSpinMsg, tx, ty);
    }

    private void drawOverlay(Graphics2D g, String title, Color titleColor, String sub) {
        int bw = Board.COLS * CELL;
        int bh = Board.ROWS * CELL;
        // 반투명 흰색 오버레이
        g.setColor(new Color(255, 255, 255, 195));
        g.fillRoundRect(BX, BY, bw, bh, BOX_ARC, BOX_ARC);
        // 타이틀 그림자
        g.setFont(new Font("맑은 고딕", Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        int tx = BX + (bw - fm.stringWidth(title)) / 2;
        int ty = BY + bh / 2 - 10;
        g.setColor(new Color(0, 0, 0, 50));
        g.drawString(title, tx + 2, ty + 2);
        // 타이틀
        g.setColor(titleColor);
        g.drawString(title, tx, ty);
        // 서브타이틀
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
        fm = g.getFontMetrics();
        g.setColor(new Color(62, 78, 158));
        g.drawString(sub, BX + (bw - fm.stringWidth(sub)) / 2, BY + bh / 2 + 26);
    }

    // ── 키 이벤트 ─────────────────────────────────
    @Override
    public void keyPressed(KeyEvent e) {
        if (gm.isGameOver()) {
            if (e.getKeyCode() == KeyEvent.VK_R) startGame();
            return;
        }
        boolean playing = !gm.isPaused();
        int k = e.getKeyCode();
        KeyBinding kb = KeyBinding.getSingle();
        if (playing) {
            if (k == kb.left)     { moveLeft();  return; }
            if (k == kb.right)    { moveRight(); return; }
            if (k == kb.rotate)   { rotate();    return; }
            if (k == kb.softDrop) { softDrop();  return; }
            if (k == kb.hardDrop) { hardDrop();  return; }
            if (k == kb.hold)     { hold();      return; }
        }
        if (k == KeyEvent.VK_P) { gm.togglePause(); repaint(); }
        if (k == KeyEvent.VK_R) { startGame(); }
        if (k == KeyEvent.VK_ESCAPE) {
            timer.stop();
            if (backCallback != null) SwingUtilities.invokeLater(backCallback);
        }
    }

    public void setBackCallback(Runnable r) { this.backCallback = r; }

    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}
}
