package TETRIS;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Key binding configuration dialog.
 *
 * Tabs: Single / 2P-P1 / 2P-P2 / Online
 * Click any action button -> press the new key -> saved immediately.
 * ESC cancels current remap. "Reset" restores defaults per tab.
 * Duplicate key check on Save.
 *
 * Uses KeyboardFocusManager so key capture works regardless of which
 * child component currently holds focus.
 */
public class KeyConfigDialog extends JDialog {

    private static final String[] ACTION_LABELS = {
        "Move Left", "Move Right", "Rotate", "Soft Drop", "Hard Drop", "Hold"
    };
    private static final String[] FIELDS = {
        "left", "right", "rotate", "softDrop", "hardDrop", "hold"
    };

    private final KeyBinding[] edits = new KeyBinding[4];
    private final JButton[][]  btns  = new JButton[4][6];

    private KeyBinding remapKb    = null;
    private String     remapField = null;
    private JButton    remapBtn   = null;

    private final KeyEventDispatcher keyDispatcher;

    // ── colours ──────────────────────────────────────────
    private static final Color BG_DARK   = new Color(18, 22, 38);
    private static final Color BG_TAB    = new Color(24, 29, 50);
    private static final Color ROW_EVEN  = new Color(30, 36, 60);
    private static final Color ROW_ODD   = new Color(24, 29, 50);
    private static final Color BTN_NORM  = new Color(40, 50, 82);
    private static final Color BTN_WAIT  = new Color(175, 110, 18);
    private static final Color FG_LABEL  = new Color(195, 210, 245);
    private static final Color FG_KEY    = new Color(210, 225, 255);
    private static final Color FG_HEAD   = new Color(110, 130, 185);
    private static final Color BORDER    = new Color(70, 85, 135);

    // ─────────────────────────────────────────────────────
    public KeyConfigDialog(Frame parent) {
        super(parent, "Key Settings", true);

        edits[0] = KeyBinding.getSingle().copy();
        edits[1] = KeyBinding.getP1().copy();
        edits[2] = KeyBinding.getP2().copy();
        edits[3] = KeyBinding.getOnline().copy();

        setResizable(false);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout());

        // ── tabs ──────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setBackground(BG_TAB);
        tabs.setForeground(new Color(180, 195, 230));
        tabs.setFont(new Font("Dialog", Font.BOLD, 12));
        tabs.setBorder(new EmptyBorder(4, 4, 0, 4));

        String[] tabNames = {"Single", "2P - P1", "2P - P2", "Online"};
        for (int t = 0; t < 4; t++) {
            tabs.addTab(tabNames[t], buildTab(t));
        }
        add(tabs, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        // ── global key dispatcher (works even when a button holds focus) ──
        keyDispatcher = ke -> {
            if (ke.getID() == KeyEvent.KEY_PRESSED && remapKb != null) {
                SwingUtilities.invokeLater(() -> onKeyCapture(ke.getKeyCode()));
                return true;          // consume: don't let the key do anything else
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(keyDispatcher);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removeKeyEventDispatcher(keyDispatcher);
            }
        });

        pack();
        setMinimumSize(new Dimension(480, 340));
        setLocationRelativeTo(parent);
    }

    // ── tab panel (GridLayout – no null layout) ──────────
    private JPanel buildTab(int t) {
        JPanel outer = new JPanel(new BorderLayout(0, 0));
        outer.setBackground(BG_TAB);
        outer.setBorder(new EmptyBorder(10, 14, 8, 14));

        // header row
        JPanel header = new JPanel(new GridLayout(1, 2, 8, 0));
        header.setBackground(BG_TAB);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 60, 95)));
        header.add(styledLabel("Action", FG_HEAD, Font.BOLD, 11));
        header.add(styledLabel("Key Binding", FG_HEAD, Font.BOLD, 11));
        outer.add(header, BorderLayout.NORTH);

        // key rows
        JPanel grid = new JPanel(new GridLayout(6, 2, 6, 4));
        grid.setBackground(BG_TAB);
        grid.setBorder(new EmptyBorder(6, 0, 6, 0));

        for (int i = 0; i < 6; i++) {
            Color bg = (i % 2 == 0) ? ROW_EVEN : ROW_ODD;

            JLabel lbl = styledLabel("  " + ACTION_LABELS[i], FG_LABEL, Font.PLAIN, 13);
            lbl.setOpaque(true);
            lbl.setBackground(bg);
            lbl.setPreferredSize(new Dimension(160, 34));
            grid.add(lbl);

            JButton btn = makeKeyBtn(KeyBinding.keyName(edits[t].get(FIELDS[i])), bg);
            final int fi = i;
            btn.addActionListener(e -> startRemap(t, fi, btn));
            grid.add(btn);
            btns[t][i] = btn;
        }
        outer.add(grid, BorderLayout.CENTER);

        // reset button
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        south.setBackground(BG_TAB);
        JButton reset = makeSysBtn("Reset Defaults");
        reset.addActionListener(e -> resetTab(t));
        south.add(reset);
        outer.add(south, BorderLayout.SOUTH);

        return outer;
    }

    // ── widget factories ──────────────────────────────────
    private JLabel styledLabel(String text, Color fg, int style, int size) {
        JLabel l = new JLabel(text);
        l.setForeground(fg);
        l.setFont(new Font("Dialog", style, size));
        return l;
    }

    private JButton makeKeyBtn(String text, Color rowBg) {
        JButton btn = new JButton(text);
        btn.setBackground(BTN_NORM);
        btn.setForeground(FG_KEY);
        btn.setFont(new Font("Consolas", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(130, 34));
        btn.setMargin(new Insets(0, 0, 0, 0));
        return btn;
    }

    private JButton makeSysBtn(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(new Color(75, 52, 20));
        btn.setForeground(new Color(230, 210, 175));
        btn.setFont(new Font("Dialog", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(125, 95, 45), 1),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ── bottom bar ────────────────────────────────────────
    private JPanel buildBottom() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 10));
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(45, 55, 88)));

        panel.add(makeRoundBtn("Save",   new Color(38, 95, 175), new Color(58, 125, 215)));
        panel.add(makeRoundBtn("Cancel", new Color(55, 62, 98),  new Color(78, 86, 128)));

        // wire actions after both exist
        ((JButton) panel.getComponent(0)).addActionListener(e -> onSave());
        ((JButton) panel.getComponent(1)).addActionListener(e -> dispose());
        return panel;
    }

    private JButton makeRoundBtn(String text, Color base, Color hov) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? hov : base);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                g2.setColor(new Color(255, 255, 255, 55));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                super.paintComponent(g);
            }
        };
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Dialog", Font.BOLD, 13));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(90, 34));
        return btn;
    }

    // ── remap logic ───────────────────────────────────────
    private void startRemap(int t, int actionIdx, JButton btn) {
        cancelRemap();
        remapKb    = edits[t];
        remapField = FIELDS[actionIdx];
        remapBtn   = btn;

        btn.setBackground(BTN_WAIT);
        btn.setForeground(Color.WHITE);
        btn.setText("Press key...");
    }

    private void cancelRemap() {
        if (remapBtn == null) return;
        remapBtn.setBackground(BTN_NORM);
        remapBtn.setForeground(FG_KEY);
        remapBtn.setText(KeyBinding.keyName(remapKb.get(remapField)));
        remapKb = null; remapField = null; remapBtn = null;
    }

    private void onKeyCapture(int kc) {
        if (remapKb == null) return;
        if (kc == KeyEvent.VK_ESCAPE) { cancelRemap(); return; }

        remapKb.set(remapField, kc);
        remapBtn.setBackground(BTN_NORM);
        remapBtn.setForeground(FG_KEY);
        remapBtn.setText(KeyBinding.keyName(kc));
        remapKb = null; remapField = null; remapBtn = null;
    }

    private void resetTab(int t) {
        KeyBinding def;
        switch (t) {
            case 0:  def = KeyBinding.defaultSingle();  break;
            case 1:  def = KeyBinding.defaultP1();      break;
            case 2:  def = KeyBinding.defaultP2();      break;
            default: def = KeyBinding.defaultOnline();  break;
        }
        edits[t].copyFrom(def);
        refreshTab(t);
    }

    private void refreshTab(int t) {
        for (int i = 0; i < 6; i++) {
            if (btns[t][i] != null)
                btns[t][i].setText(KeyBinding.keyName(edits[t].get(FIELDS[i])));
        }
    }

    // ── save ──────────────────────────────────────────────
    private void onSave() {
        String[] names = {"Single", "2P P1", "2P P2", "Online"};
        for (int t = 0; t < 4; t++) {
            if (edits[t].hasDuplicate()) {
                JOptionPane.showMessageDialog(this,
                    "[" + names[t] + "] has duplicate key bindings.\n"
                    + "Please fix them before saving.",
                    "Key Conflict", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        KeyBinding.getSingle().copyFrom(edits[0]);
        KeyBinding.getP1()    .copyFrom(edits[1]);
        KeyBinding.getP2()    .copyFrom(edits[2]);
        KeyBinding.getOnline().copyFrom(edits[3]);
        KeyBinding.saveAll();
        dispose();
    }
}
