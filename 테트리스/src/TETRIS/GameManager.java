package TETRIS;

public class GameManager {

    // ── Singleton ──────────────────────────────────
    private static GameManager instance;
    private GameManager() { reset(); }
    public static GameManager getInstance() {
        if (instance == null) instance = new GameManager();
        return instance;
    }

    // ── 상태 ──────────────────────────────────────
    private int     score;
    private int     level;
    private int     totalLines;
    private boolean gameOver;
    private boolean paused;

    // 일반 줄 점수 (1~4줄)
    private static final int[] LINE_SCORES  = { 0, 100, 300, 500, 800 };
    // T-스핀 점수 (0~3줄) — 동일 줄 수 대비 약 2배
    private static final int[] TSPIN_SCORES = { 400, 800, 1200, 1600 };

    // ── 점수 계산 ──────────────────────────────────
    /**
     * @param lines  삭제된 줄 수
     * @param tSpin  T-스핀 여부
     */
    public void addScore(int lines, boolean tSpin) {
        totalLines += lines;
        int points;
        if (tSpin) {
            points = TSPIN_SCORES[Math.min(lines, 3)] * level;
        } else {
            if (lines <= 0) return;
            points = LINE_SCORES[Math.min(lines, 4)] * level;
        }
        score += points;
        level = totalLines / 10 + 1;  // 10줄마다 레벨업
    }

    public void reset() {
        score = 0; level = 1; totalLines = 0; gameOver = false; paused = false;
    }

    // ── Getter / Setter ───────────────────────────
    public int     getSpeed()      { return Math.max(100, 1000 - (level - 1) * 90); }
    public int     getScore()      { return score; }
    public int     getLevel()      { return level; }
    public int     getTotalLines() { return totalLines; }
    public boolean isGameOver()    { return gameOver; }
    public boolean isPaused()      { return paused; }
    public void    setGameOver(boolean v) { gameOver = v; }
    public void    togglePause()   { paused = !paused; }
}
