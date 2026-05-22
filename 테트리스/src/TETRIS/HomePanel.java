package TETRIS;

import javax.swing.*;
import java.awt.*;

public class HomePanel extends JPanel {

    public HomePanel(Runnable on1P, Runnable on2P, Runnable onOnline) {
        setPreferredSize(new Dimension(520, 510));
        setBackground(new Color(20, 24, 40));
        setLayout(null);

        JButton btn1P     = makeButton("▶  1 PLAYER",       new Color(35, 85, 155),  new Color(55, 115, 200));
        JButton btn2P     = makeButton("▶▶ 2 PLAYER",       new Color(110, 35, 135), new Color(150, 55, 175));
        JButton btnOnline = makeButton("◉  ONLINE MULTI",   new Color(35, 130, 95),  new Color(55, 170, 125));

        btn1P.setBounds(160, 270, 200, 52);
        btn2P.setBounds(160, 338, 200, 52);
        btnOnline.setBounds(160, 406, 200, 52);

        btn1P.addActionListener(e -> on1P.run());
        btn2P.addActionListener(e -> on2P.run());
        btnOnline.addActionListener(e -> { if (onOnline != null) onOnline.run(); });

        add(btn1P);
        add(btn2P);
        add(btnOnline);
    }

    private JButton makeButton(String text, Color base, Color hover) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? hover : base);
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

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawDecoBlocks(g2);
        drawTitle(g2);
        drawSubtitle(g2);
        drawHint(g2);
    }

    private void drawTitle(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.BOLD, 90));
        // 그림자
        g.setColor(new Color(0, 0, 0, 130));
        g.drawString("TETRIS", 64, 182);
        // 그라디언트
        GradientPaint gp = new GradientPaint(60, 95, new Color(0, 225, 230),
                                              460, 185, new Color(155, 0, 235));
        g.setPaint(gp);
        g.drawString("TETRIS", 60, 178);
    }

    private void drawSubtitle(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 14));
        g.setColor(new Color(135, 150, 200));
        String s = "모드를 선택하세요";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (520 - fm.stringWidth(s)) / 2, 228);
    }

    private void drawHint(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(75, 85, 125));
        String s1 = "게임 중 ESC → 홈으로 돌아가기";
        String s2 = "2P: WASD(P1) · 넘패드(P2)    ONLINE: 같은 와이파이의 두 PC";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s1, (520 - fm.stringWidth(s1)) / 2, 475);
        g.drawString(s2, (520 - fm.stringWidth(s2)) / 2, 492);
    }

    /** 장식용 테트로미노 블록 */
    private void drawDecoBlocks(Graphics2D g) {
        // 왼쪽 L자
        drawMini(g, new int[][]{{0,0},{1,0},{2,0},{2,1}}, 22, 280, 13, new Color(0, 0, 220));
        // 오른쪽 J자
        drawMini(g, new int[][]{{0,1},{1,1},{2,0},{2,1}}, 472, 280, 13, new Color(220, 130, 0));
        // 왼쪽 상단 S
        drawMini(g, new int[][]{{0,1},{0,2},{1,0},{1,1}}, 30, 60, 10, new Color(0, 200, 0));
        // 오른쪽 상단 Z
        drawMini(g, new int[][]{{0,0},{0,1},{1,1},{1,2}}, 460, 60, 10, new Color(200, 0, 0));
    }

    private void drawMini(Graphics2D g, int[][] cells, int ox, int oy, int size, Color color) {
        for (int[] c : cells) {
            int x = ox + c[1] * size, y = oy + c[0] * size;
            g.setColor(color);
            g.fillRect(x + 1, y + 1, size - 2, size - 2);
            g.setColor(color.brighter());
            g.drawLine(x + 1, y + 1, x + size - 2, y + 1);
        }
    }
}
