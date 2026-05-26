package TETRIS;

import java.awt.Color;

public class Board {
    public static final int COLS = 10;
    public static final int ROWS = 20;

    private Color[][] grid;

    // ── SRS 벽 차기 테이블 (화면 좌표 y↓, [회전전상태][kick번호] = {dcol, drow}) ──
    // JLSTZ 공통
    private static final int[][][] KICKS_JLSTZ = {
        { {0,0}, {-1,0}, {-1,-1}, {0, 2}, {-1, 2} },  // 0 → 1
        { {0,0}, { 1,0}, { 1, 1}, {0,-2}, { 1,-2} },  // 1 → 2
        { {0,0}, { 1,0}, { 1,-1}, {0, 2}, { 1, 2} },  // 2 → 3
        { {0,0}, {-1,0}, {-1, 1}, {0,-2}, {-1,-2} }   // 3 → 0
    };
    // I 피스 전용
    private static final int[][][] KICKS_I = {
        { {0,0}, {-2,0}, { 1,0}, {-2,-1}, { 1, 2} },  // 0 → 1
        { {0,0}, {-1,0}, { 2,0}, {-1, 2}, { 2,-1} },  // 1 → 2
        { {0,0}, { 2,0}, {-1,0}, { 2, 1}, {-1,-2} },  // 2 → 3
        { {0,0}, { 1,0}, {-2,0}, { 1,-2}, {-2, 1} }   // 3 → 0
    };

    public Board() {
        grid = new Color[ROWS][COLS];
    }

    /** 유효 위치 검사 */
    public boolean isValidPosition(Tetromino piece) {
        int[][] shape = piece.getShape();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (shape[r][c] == 1) {
                    int br = piece.getY() + r;
                    int bc = piece.getX() + c;
                    if (br >= ROWS) return false;
                    if (bc < 0 || bc >= COLS) return false;
                    if (br >= 0 && grid[br][bc] != null) return false;
                }
            }
        }
        return true;
    }

    /**
     * SRS 벽 차기를 적용해 시계방향 회전 시도.
     * 성공하면 적용된 킥 오프셋 {dcol, drow} 반환, 실패 시 null.
     */
    public int[] tryRotate(Tetromino piece) {
        int origX   = piece.getX();
        int origY   = piece.getY();
        int origRot = piece.getRotation();

        piece.rotateRight();  // 먼저 회전 적용

        int[][] kicks = getKickTable(piece.getType(), origRot);
        for (int[] kick : kicks) {
            piece.setX(origX + kick[0]);
            piece.setY(origY + kick[1]);
            if (isValidPosition(piece)) {
                return kick;  // 성공 → 킥 오프셋 반환
            }
        }

        // 모든 킥 실패 → 원상 복귀
        piece.setX(origX);
        piece.setY(origY);
        piece.setRotation(origRot);
        return null;
    }

    private int[][] getKickTable(Tetromino.Type type, int fromRot) {
        if (type == Tetromino.Type.I) return KICKS_I[fromRot];
        if (type == Tetromino.Type.O) return new int[][]{{0, 0}};  // O는 킥 없음
        return KICKS_JLSTZ[fromRot];
    }

    /** 블록 고정 */
    public void placePiece(Tetromino piece) {
        int[][] shape = piece.getShape();
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (shape[r][c] == 1) {
                    int br = piece.getY() + r;
                    int bc = piece.getX() + c;
                    if (br >= 0 && br < ROWS && bc >= 0 && bc < COLS)
                        grid[br][bc] = piece.getColor();
                }
            }
        }
    }

    /** 완성 줄 삭제 → 삭제 수 반환 */
    public int clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            if (isLineFull(r)) {
                removeLine(r);
                cleared++;
                r++;
            }
        }
        return cleared;
    }

    private boolean isLineFull(int row) {
        for (int c = 0; c < COLS; c++) if (grid[row][c] == null) return false;
        return true;
    }

    private void removeLine(int row) {
        for (int r = row; r > 0; r--) grid[r] = grid[r - 1].clone();
        grid[0] = new Color[COLS];
    }

    /**
     * 보드 맨 아래에 쓰레기 줄 삽입.
     * 전체 줄이 위로 밀리고, 맨 아래는 holeCol 한 칸만 뚫린 회색 줄로 채워진다.
     */
    public void addGarbageLine(int holeCol) {
        for (int r = 0; r < ROWS - 1; r++)
            grid[r] = grid[r + 1].clone();
        Color gray = new Color(110, 115, 130);
        grid[ROWS - 1] = new Color[COLS];
        for (int c = 0; c < COLS; c++)
            if (c != holeCol) grid[ROWS - 1][c] = gray;
    }

    public Color getCell(int row, int col) { return grid[row][col]; }
    public void  clear()                   { grid = new Color[ROWS][COLS]; }

    /** 완성된(꽉 찬) 행 인덱스 목록 반환. clearLines() 호출 전에 사용하면 이펙트에 활용 가능. */
    public int[] getFullRowIndices() {
        java.util.List<Integer> rows = new java.util.ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            if (isLineFull(r)) rows.add(r);
        }
        return rows.stream().mapToInt(Integer::intValue).toArray();
    }
}
