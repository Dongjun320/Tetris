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
        // ── 연결/방 메시지 ───────────────────────────
        HELLO,       // 연결 직후 인사 (name 전달)
        WELCOME,     // (호환용) 호스트 응답 — 현재는 JOIN_OK 사용
        JOIN_OK,     // 호스트→게스트: 입장 승인 + playerId 부여 (id, maxPlayers)
        PLAYER_LIST, // 호스트→모두: 인원/이름/ready 상태 일괄 갱신 (n, p0=id:name:ready, p1=...)
        READY,       // 게스트↔호스트: 준비 상태 토글 (from, ready=0/1)
        START,       // 호스트→모두: 게임 시작 신호

        // ── 인게임 메시지 ────────────────────────────
        STATE,       // 보드 상태 주기적 송신 (from=playerId, board, cur, ...)
        GARBAGE,     // 공격 (from=공격자, target=피공격자, lines=라인 수)
        GAMEOVER,    // 자기 패배 알림 (from=playerId, 호스트가 받아 ELIMINATED 브로드캐스트)
        ELIMINATED,  // 호스트→모두: 특정 플레이어 탈락 (id, rank=현재 등수)
        RANKING,     // 호스트→모두: 최종 순위 (ids=2,1,3,4 → 1등→꼴등 순)

        // ── 시스템 ───────────────────────────────────
        RESTART,     // (호환용) 1:1 모드 재시작 신호 — 현재는 방 시스템으로 대체
        PING,
        PONG,
        DISCONNECT
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
