package TETRIS.network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 네트워크 메시지 (단순 텍스트 기반 프로토콜)
 *
 * 형식:  TYPE|key1=value1|key2=value2|...
 * 줄 단위(\n)로 구분되며, key/value 안의 특수 문자는 이스케이프된다.
 *
 * 외부 라이브러리 없이 동작하도록 직접 직렬화/역직렬화를 구현했다.
 */
public class NetMessage {

    public enum Type {
        HELLO,       // 연결 직후 인사
        WELCOME,     // 호스트의 응답
        READY,       // 양쪽 준비 완료
        START,       // 게임 시작
        STATE,       // 보드 상태(주기적 송신)
        GARBAGE,     // 상대에게 가비지 라인 공격
        GAMEOVER,    // 게임 종료(패배 알림)
        RESTART,     // 재시작 요청 (한쪽이 R 누르면 양쪽 동시 시작)
        PING,        // 연결 확인
        PONG,        // 핑 응답
        DISCONNECT   // 정상 종료 알림
    }

    public final Type type;
    public final Map<String, String> data = new LinkedHashMap<>();

    public NetMessage(Type type) {
        this.type = type;
    }

    // ── 빌더식 setter ─────────────────────────────────────
    public NetMessage put(String key, Object value) {
        data.put(key, String.valueOf(value));
        return this;
    }

    // ── getter ────────────────────────────────────────────
    public String get(String key)                       { return data.get(key); }
    public String get(String key, String defaultValue)  { return data.getOrDefault(key, defaultValue); }

    public int getInt(String key, int defaultValue) {
        String v = data.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (Exception e) { return defaultValue; }
    }

    public long getLong(String key, long defaultValue) {
        String v = data.get(key);
        if (v == null) return defaultValue;
        try { return Long.parseLong(v); } catch (Exception e) { return defaultValue; }
    }

    // ── 직렬화 ─────────────────────────────────────────────
    /** TYPE|key1=value1|key2=value2 형식의 문자열로 변환 */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        for (Map.Entry<String, String> e : data.entrySet()) {
            sb.append('|')
              .append(escape(e.getKey()))
              .append('=')
              .append(escape(e.getValue()));
        }
        return sb.toString();
    }

    /** 한 줄 문자열을 NetMessage로 파싱. 실패 시 null. */
    public static NetMessage deserialize(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0) return null;
        Type type;
        try {
            type = Type.valueOf(parts[0]);
        } catch (IllegalArgumentException ex) {
            return null;  // 알 수 없는 타입
        }
        NetMessage msg = new NetMessage(type);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            String key   = unescape(parts[i].substring(0, eq));
            String value = unescape(parts[i].substring(eq + 1));
            msg.data.put(key, value);
        }
        return msg;
    }

    // ── 이스케이프(파이프/등호/줄바꿈 보호) ─────────────────
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '|':  sb.append("\\p");  break;
                case '=':  sb.append("\\e");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '\\': sb.append('\\'); break;
                    case 'p':  sb.append('|');  break;
                    case 'e':  sb.append('=');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    default:   sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return serialize();
    }
}
