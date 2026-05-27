package TETRIS;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.awt.Window;

public class HomePanel extends JPanel {

    // ── 배경 애니메이션용 떨어지는 블럭 ─────────────────────
    private static class FallingBlock {
        float x, y, speed;
        int size;
        Color color;
        int[][] cells; // 상대 좌표 목록

        FallingBlock(float x, float y, float speed, int size, Color color, int[][] cells) {
            this.x = x; this.y = y; this.speed = speed;
            this.size = size; this.color = color; this.cells = cells;
        }
    }

    // 장식용 블럭 형태 정의
    private static final int[][][] SHAPES = {
        {{0,0},{1,0},{2,0},{3,0}},          // I
        {{0,0},{0,1},{1,0},{1,1}},          // O
        {{0,1},{1,0},{1,1},{1,2}},          // T
        {{0,0},{0,1},{1,1},{1,2}},          // S
        {{0,1},{0,2},{1,0},{1,1}},          // Z
        {{0,0},{1,0},{1,1},{1,2}},          // J
        {{0,2},{1,0},{1,1},{1,2}},          // L
    };
    private static final Color[] BLOCK_COLORS = {
        new Color(0,   210, 235, 90),
        new Color(235, 230,  0,  85),
        new Color(170,  0, 235, 90),
        new Color( 0,  215,  0,  85),
        new Color(235,  0,   0,  90),
        new Color( 40, 40, 235, 90),
        new Color(235, 155,  0,  90),
    };

    private final List<FallingBlock> fallingBlocks = new ArrayList<>();
    private Timer animTimer;

    // ── 버튼 hover 상태 (애니메이션용) ──────────────────────
    private float[] btnHover = new float[4];   // 0.0 ~ 1.0  (인덱스 3 = 키 설정 버튼)
    private Timer   hoverTimer;

    public HomePanel(Runnable on1P, Runnable on2P, Runnable onOnline) {
        setPreferredSize(new Dimension(520, 510));
        setBackground(new Color(14, 16, 30));
        setLayout(null);

        initFallingBlocks();

        // 애니메이션 타이머 (30fps)
        animTimer = new Timer(33, e -> {
            for (FallingBlock fb : fallingBlocks) {
                fb.y += fb.speed;
                if (fb.y > 570) {
                    fb.y = -60;
                    fb.x = new Random().nextInt(500);
                }
            }
            repaint();
        });
        animTimer.start();

        // hover 부드럽게 처리
        hoverTimer = new Timer(16, e -> repaint());
        hoverTimer.start();

        // 버튼 생성
        JButton btn1P     = makeButton("1 PLAYER",     0, new Color(40, 100, 200), new Color(70, 140, 255));
        JButton btn2P     = makeButton("2 PLAYER",     1, new Color(120, 35, 160), new Color(165, 65, 215));
        JButton btnOnline = makeButton("ONLINE MULTI", 2, new Color(30, 140, 100), new Color(50, 190, 135));
        JButton btnKey    = makeSmallButton("⚙  키 설정", 3, new Color(55, 62, 95), new Color(80, 90, 140));

        btn1P.setBounds(135, 268, 250, 54);
        btn2P.setBounds(135, 338, 250, 54);
        btnOnline.setBounds(135, 408, 250, 54);
        btnKey.setBounds(195, 470, 130, 30);

        btn1P.addActionListener(e -> on1P.run());
        btn2P.addActionListener(e -> on2P.run());
        btnOnline.addActionListener(e -> { if (onOnline != null) onOnline.run(); });
        btnKey.addActionListener(e -> {
            Window w = SwingUtilities.getWindowAncestor(HomePanel.this);
            Frame frame = (w instanceof Frame) ? (Frame) w : null;
            new KeyConfigDialog(frame).setVisible(true);
        });

        add(btn1P);
        add(btn2P);
        add(btnOnline);
        add(btnKey);
    }

    private void initFallingBlocks() {
        Random rng = new Random(42);
        for (int i = 0; i < 14; i++) {
            int shapeIdx = rng.nextInt(SHAPES.length);
            int cellSize = 10 + rng.nextInt(10);
            Color base = BLOCK_COLORS[shapeIdx % BLOCK_COLORS.length];
            float speed = 0.30f + rng.nextFloat() * 0.55f;
            float x = rng.nextInt(500);
            float y = rng.nextInt(520) - 60f;
            fallingBlocks.add(new FallingBlock(x, y, speed, cellSize, base, SHAPES[shapeIdx]));
        }
    }

    private JButton makeButton(String text, int index, Color base, Color hover) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean ro = getModel().isRollover();
                // hover 부드럽게 갱신
                if (ro) btnHover[index] = Math.min(1f, btnHover[index] + 0.08f);
                else    btnHover[index] = Math.max(0f, btnHover[index] - 0.06f);
                float h = btnHover[index];

                // 배경 그라데이션
                Color c1 = interpolate(base, hover, h);
                Color c2 = c1.darker();
                GradientPaint gp = new GradientPaint(0, 0, c1, 0, getHeight(), c2);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);

                // 상단 광택
                g2.setColor(new Color(255, 255, 255, (int)(35 + h * 30)));
                g2.fillRoundRect(2, 2, getWidth()-4, getHeight()/2 - 2, 14, 14);

                // 테두리
                g2.setColor(new Color(255, 255, 255, (int)(50 + h * 80)));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);

                // 아이콘 삼각형
                int ty = getHeight()/2 - 7;
                g2.setColor(new Color(255, 255, 255, (int)(180 + h * 75)));
                int[] tx = {14, 14, 24};
                int[] ty2 = {ty, ty+14, ty+7};
                g2.fillPolygon(tx, ty2, 3);

                // 텍스트
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 17));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(Color.WHITE);
                int tx2 = 32 + (getWidth()-32 - fm.stringWidth(getText())) / 2;
                int ty3 = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), tx2, ty3);
            }
        };
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** 작은 보조 버튼 (키 설정 등) */
    private JButton makeSmallButton(String text, int index, Color base, Color hover) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean ro = getModel().isRollover();
                if (ro) btnHover[index] = Math.min(1f, btnHover[index] + 0.10f);
                else    btnHover[index] = Math.max(0f, btnHover[index] - 0.07f);
                float h = btnHover[index];

                Color c1 = interpolate(base, hover, h);
                GradientPaint gp = new GradientPaint(0, 0, c1.brighter(), 0, getHeight(), c1);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);

                // 상단 광택
                g2.setColor(new Color(255, 255, 255, (int)(25 + h * 25)));
                g2.fillRoundRect(2, 2, getWidth()-4, getHeight()/2 - 2, 8, 8);

                // 테두리
                g2.setColor(new Color(255, 255, 255, (int)(40 + h * 60)));
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);

                // 텍스트
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 12));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(new Color(200, 215, 255));
                int tx = (getWidth() - fm.stringWidth(getText())) / 2;
                int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), tx, ty);
            }
        };
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private Color interpolate(Color a, Color b, float t) {
        int r = (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl= (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
        return new Color(r, g, bl);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBackground(g2);
        drawFallingBlocks(g2);
        drawTitle(g2);
        drawSubtitle(g2);
        drawDivider(g2);
        drawHint(g2);
    }

    // ── 배경 그라데이션 ───────────────────────────────────
    private void drawBackground(Graphics2D g) {
        // 살짝 밝아진 미드나잇 블루 그라데이션
        GradientPaint gp = new GradientPaint(0, 0, new Color(24, 28, 58),
                                              0, 510, new Color(38, 46, 84));
        g.setPaint(gp);
        g.fillRect(0, 0, 520, 510);

        // 오른쪽 위 보라 빛 (더 선명하게)
        RadialGradientPaint rg = new RadialGradientPaint(430, 75, 230,
                new float[]{0f, 1f},
                new Color[]{new Color(120, 55, 215, 72), new Color(0, 0, 0, 0)});
        g.setPaint(rg);
        g.fillRect(0, 0, 520, 510);

        // 왼쪽 아래 청록빛 (더 선명하게)
        RadialGradientPaint rg2 = new RadialGradientPaint(55, 435, 185,
                new float[]{0f, 1f},
                new Color[]{new Color(15, 170, 220, 60), new Color(0, 0, 0, 0)});
        g.setPaint(rg2);
        g.fillRect(0, 0, 520, 510);

        // 중앙 하단 핑크 포인트
        RadialGradientPaint rg3 = new RadialGradientPaint(260, 420, 150,
                new float[]{0f, 1f},
                new Color[]{new Color(210, 55, 175, 30), new Color(0, 0, 0, 0)});
        g.setPaint(rg3);
        g.fillRect(0, 0, 520, 510);
    }

    // ── 떨어지는 블럭들 ───────────────────────────────────
    private void drawFallingBlocks(Graphics2D g) {
        for (FallingBlock fb : fallingBlocks) {
            for (int[] cell : fb.cells) {
                int cx = (int) fb.x + cell[1] * (fb.size + 1);
                int cy = (int) fb.y + cell[0] * (fb.size + 1);
                g.setColor(fb.color);
                g.fillRoundRect(cx, cy, fb.size - 1, fb.size - 1, 3, 3);
                g.setColor(new Color(255, 255, 255, 30));
                g.fillRect(cx + 1, cy + 1, (fb.size - 3) / 2, 2);
            }
        }
    }

    // ── 타이틀 TETRIS ─────────────────────────────────────
    private void drawTitle(Graphics2D g) {
        Font titleFont = new Font("맑은 고딕", Font.BOLD, 88);
        g.setFont(titleFont);
        FontMetrics fm = g.getFontMetrics();
        int tx = (520 - fm.stringWidth("TETRIS")) / 2;
        int ty = 178;

        // 글로우 레이어 (여러 겹 흐리게, 좀 더 선명)
        for (int i = 8; i >= 1; i--) {
            int alpha = (int)(14 + (8 - i) * 5);
            g.setColor(new Color(100, 218, 255, alpha));
            g.drawString("TETRIS", tx - i, ty - i);
            g.drawString("TETRIS", tx + i, ty + i);
        }

        // 그림자
        g.setColor(new Color(0, 0, 0, 100));
        g.drawString("TETRIS", tx + 4, ty + 5);

        // 메인 그라데이션 텍스트
        GradientPaint gp = new GradientPaint(tx, ty - 80, new Color(85, 242, 255),
                                              tx + 400, ty, new Color(205, 75, 255));
        g.setPaint(gp);
        g.drawString("TETRIS", tx, ty);
        g.setPaint(null);
    }

    // ── 구분선 ────────────────────────────────────────────
    private void drawDivider(Graphics2D g) {
        g.setColor(new Color(255, 255, 255, 28));
        g.drawLine(100, 252, 420, 252);
    }

    // ── 서브타이틀 ────────────────────────────────────────
    private void drawSubtitle(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 14));
        g.setColor(new Color(165, 188, 235));
        String s = "모드를 선택하세요";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (520 - fm.stringWidth(s)) / 2, 226);
    }

    // ── 하단 힌트 ─────────────────────────────────────────
    private void drawHint(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(95, 112, 165));
        String s1 = "게임 중 ESC → 홈으로   |   ⚙ 키 설정으로 키 변경 가능";
        String s2 = "ONLINE: 같은 와이파이의 두 PC에서 접속";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s1, (520 - fm.stringWidth(s1)) / 2, 508);
        g.drawString(s2, (520 - fm.stringWidth(s2)) / 2, 522);
    }
}
