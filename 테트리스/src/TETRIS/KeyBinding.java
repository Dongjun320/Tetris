package TETRIS;

import java.awt.event.KeyEvent;
import java.io.*;
import java.util.Properties;

/**
 * 키 바인딩 설정.
 *
 * <ul>
 *   <li>싱글플레이용  : {@link #getSingle()}</li>
 *   <li>2P Player1용  : {@link #getP1()}</li>
 *   <li>2P Player2용  : {@link #getP2()}</li>
 *   <li>온라인멀티용  : {@link #getOnline()}</li>
 * </ul>
 *
 * 설정은 {@code ~/tetris/keys.properties} 에 저장/복원된다.
 */
public class KeyBinding {

    // ── 6가지 액션 ─────────────────────────────────────
    public int left;
    public int right;
    public int rotate;
    public int softDrop;
    public int hardDrop;
    public int hold;

    // ── 기본값 팩토리 ──────────────────────────────────
    public static KeyBinding defaultSingle() {
        KeyBinding kb = new KeyBinding();
        kb.left     = KeyEvent.VK_LEFT;
        kb.right    = KeyEvent.VK_RIGHT;
        kb.rotate   = KeyEvent.VK_UP;
        kb.softDrop = KeyEvent.VK_DOWN;
        kb.hardDrop = KeyEvent.VK_SPACE;
        kb.hold     = KeyEvent.VK_H;
        return kb;
    }

    public static KeyBinding defaultP1() {
        KeyBinding kb = new KeyBinding();
        kb.left     = KeyEvent.VK_A;
        kb.right    = KeyEvent.VK_D;
        kb.rotate   = KeyEvent.VK_W;
        kb.softDrop = KeyEvent.VK_S;
        kb.hardDrop = KeyEvent.VK_SPACE;
        kb.hold     = KeyEvent.VK_Q;
        return kb;
    }

    public static KeyBinding defaultP2() {
        KeyBinding kb = new KeyBinding();
        kb.left     = KeyEvent.VK_NUMPAD4;
        kb.right    = KeyEvent.VK_NUMPAD6;
        kb.rotate   = KeyEvent.VK_NUMPAD8;
        kb.softDrop = KeyEvent.VK_NUMPAD2;
        kb.hardDrop = KeyEvent.VK_NUMPAD0;
        kb.hold     = KeyEvent.VK_NUMPAD7;
        return kb;
    }

    public static KeyBinding defaultOnline() {
        return defaultP1(); // 온라인도 WASD 기본
    }

    // ── 싱글톤 인스턴스 ────────────────────────────────
    private static KeyBinding single  = null;
    private static KeyBinding p1      = null;
    private static KeyBinding p2      = null;
    private static KeyBinding online  = null;

    public static KeyBinding getSingle() { if (single  == null) loadAll(); return single; }
    public static KeyBinding getP1()     { if (p1      == null) loadAll(); return p1; }
    public static KeyBinding getP2()     { if (p2      == null) loadAll(); return p2; }
    public static KeyBinding getOnline() { if (online  == null) loadAll(); return online; }

    // ── 파일 저장 / 로드 ───────────────────────────────
    private static File getFile() {
        File dir = new File(System.getProperty("user.home"), "tetris");
        dir.mkdirs();
        return new File(dir, "keys.properties");
    }

    public static void saveAll() {
        if (single == null) single = defaultSingle();
        if (p1     == null) p1     = defaultP1();
        if (p2     == null) p2     = defaultP2();
        if (online == null) online = defaultOnline();

        Properties props = new Properties();
        write(props, "single", single);
        write(props, "p1",     p1);
        write(props, "p2",     p2);
        write(props, "online", online);

        try (OutputStream out = new FileOutputStream(getFile())) {
            props.store(out, "Tetris Key Bindings");
        } catch (IOException e) {
            System.err.println("[KeyBinding] save failed: " + e.getMessage());
        }
    }

    public static void loadAll() {
        single = defaultSingle();
        p1     = defaultP1();
        p2     = defaultP2();
        online = defaultOnline();

        File f = getFile();
        if (!f.exists()) return;

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        read(props, "single", single);
        read(props, "p1",     p1);
        read(props, "p2",     p2);
        read(props, "online", online);
    }

    private static void write(Properties p, String prefix, KeyBinding kb) {
        p.setProperty(prefix + ".left",     String.valueOf(kb.left));
        p.setProperty(prefix + ".right",    String.valueOf(kb.right));
        p.setProperty(prefix + ".rotate",   String.valueOf(kb.rotate));
        p.setProperty(prefix + ".softDrop", String.valueOf(kb.softDrop));
        p.setProperty(prefix + ".hardDrop", String.valueOf(kb.hardDrop));
        p.setProperty(prefix + ".hold",     String.valueOf(kb.hold));
    }

    private static void read(Properties p, String prefix, KeyBinding kb) {
        kb.left     = getInt(p, prefix + ".left",     kb.left);
        kb.right    = getInt(p, prefix + ".right",    kb.right);
        kb.rotate   = getInt(p, prefix + ".rotate",   kb.rotate);
        kb.softDrop = getInt(p, prefix + ".softDrop", kb.softDrop);
        kb.hardDrop = getInt(p, prefix + ".hardDrop", kb.hardDrop);
        kb.hold     = getInt(p, prefix + ".hold",     kb.hold);
    }

    private static int getInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    // ── 유틸 ──────────────────────────────────────────
    /** KeyCode → 짧은 이름 문자열 */
    public static String keyName(int kc) {
        switch (kc) {
            case KeyEvent.VK_SPACE:       return "SPACE";
            case KeyEvent.VK_UP:          return "↑";
            case KeyEvent.VK_DOWN:        return "↓";
            case KeyEvent.VK_LEFT:        return "←";
            case KeyEvent.VK_RIGHT:       return "→";
            case KeyEvent.VK_SHIFT:       return "SHIFT";
            case KeyEvent.VK_CONTROL:     return "CTRL";
            case KeyEvent.VK_ALT:         return "ALT";
            case KeyEvent.VK_ENTER:       return "ENTER";
            case KeyEvent.VK_ESCAPE:      return "ESC";
            case KeyEvent.VK_BACK_SPACE:  return "BKSP";
            case KeyEvent.VK_DELETE:      return "DEL";
            case KeyEvent.VK_TAB:         return "TAB";
            case KeyEvent.VK_HOME:        return "HOME";
            case KeyEvent.VK_END:         return "END";
            case KeyEvent.VK_INSERT:      return "INS";
            case KeyEvent.VK_PAGE_UP:     return "PgUp";
            case KeyEvent.VK_PAGE_DOWN:   return "PgDn";
            case KeyEvent.VK_NUMPAD0:     return "NUM0";
            case KeyEvent.VK_NUMPAD1:     return "NUM1";
            case KeyEvent.VK_NUMPAD2:     return "NUM2";
            case KeyEvent.VK_NUMPAD3:     return "NUM3";
            case KeyEvent.VK_NUMPAD4:     return "NUM4";
            case KeyEvent.VK_NUMPAD5:     return "NUM5";
            case KeyEvent.VK_NUMPAD6:     return "NUM6";
            case KeyEvent.VK_NUMPAD7:     return "NUM7";
            case KeyEvent.VK_NUMPAD8:     return "NUM8";
            case KeyEvent.VK_NUMPAD9:     return "NUM9";
            default:
                String s = KeyEvent.getKeyText(kc);
                return s.length() <= 7 ? s.toUpperCase() : s.substring(0, 7).toUpperCase();
        }
    }

    /** 같은 키가 여러 액션에 중복 할당됐는지 검사 */
    public boolean hasDuplicate() {
        int[] keys = {left, right, rotate, softDrop, hardDrop, hold};
        for (int i = 0; i < keys.length; i++)
            for (int j = i + 1; j < keys.length; j++)
                if (keys[i] == keys[j]) return true;
        return false;
    }

    /** 이 바인딩의 복사본 반환 */
    public KeyBinding copy() {
        KeyBinding kb = new KeyBinding();
        kb.copyFrom(this);
        return kb;
    }

    /** 다른 바인딩에서 값 복사 */
    public void copyFrom(KeyBinding o) {
        left = o.left; right = o.right; rotate = o.rotate;
        softDrop = o.softDrop; hardDrop = o.hardDrop; hold = o.hold;
    }

    // ── 필드 접근 헬퍼 (이름 → 값) ────────────────────
    public int get(String field) {
        switch (field) {
            case "left":     return left;
            case "right":    return right;
            case "rotate":   return rotate;
            case "softDrop": return softDrop;
            case "hardDrop": return hardDrop;
            case "hold":     return hold;
            default: return 0;
        }
    }

    public void set(String field, int kc) {
        switch (field) {
            case "left":     left     = kc; break;
            case "right":    right    = kc; break;
            case "rotate":   rotate   = kc; break;
            case "softDrop": softDrop = kc; break;
            case "hardDrop": hardDrop = kc; break;
            case "hold":     hold     = kc; break;
        }
    }
}
