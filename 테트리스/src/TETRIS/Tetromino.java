package TETRIS;

import java.awt.Color;
import java.util.Random;

public class Tetromino {

    public enum Type { I, O, T, S, Z, J, L }

    private static final Color[] COLORS = {
        new Color(0,   240, 240),  // I - 하늘
        new Color(240, 240, 0),    // O - 노랑
        new Color(160, 0,   240),  // T - 보라
        new Color(0,   240, 0),    // S - 초록
        new Color(240, 0,   0),    // Z - 빨강
        new Color(0,   0,   240),  // J - 파랑
        new Color(240, 160, 0)     // L - 주황
    };

    // [타입][회전상태][행][열] — 4x4 그리드
    private static final int[][][][] SHAPES = {
        // I
        {
            {{0,0,0,0},{1,1,1,1},{0,0,0,0},{0,0,0,0}},
            {{0,0,1,0},{0,0,1,0},{0,0,1,0},{0,0,1,0}},
            {{0,0,0,0},{0,0,0,0},{1,1,1,1},{0,0,0,0}},
            {{0,1,0,0},{0,1,0,0},{0,1,0,0},{0,1,0,0}}
        },
        // O
        {
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}}
        },
        // T
        {
            {{0,1,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,0,0},{0,1,1,0},{0,1,0,0},{0,0,0,0}},
            {{0,0,0,0},{1,1,1,0},{0,1,0,0},{0,0,0,0}},
            {{0,1,0,0},{1,1,0,0},{0,1,0,0},{0,0,0,0}}
        },
        // S
        {
            {{0,1,1,0},{1,1,0,0},{0,0,0,0},{0,0,0,0}},
            {{1,0,0,0},{1,1,0,0},{0,1,0,0},{0,0,0,0}},
            {{0,0,0,0},{0,1,1,0},{1,1,0,0},{0,0,0,0}},
            {{0,1,0,0},{1,1,0,0},{1,0,0,0},{0,0,0,0}}
        },
        // Z
        {
            {{1,1,0,0},{0,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,0,1,0},{0,1,1,0},{0,1,0,0},{0,0,0,0}},
            {{0,0,0,0},{1,1,0,0},{0,1,1,0},{0,0,0,0}},
            {{0,1,0,0},{1,1,0,0},{1,0,0,0},{0,0,0,0}}
        },
        // J
        {
            {{1,0,0,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,1,0},{0,1,0,0},{0,1,0,0},{0,0,0,0}},
            {{0,0,0,0},{1,1,1,0},{0,0,1,0},{0,0,0,0}},
            {{0,1,0,0},{0,1,0,0},{1,1,0,0},{0,0,0,0}}
        },
        // L
        {
            {{0,0,1,0},{1,1,1,0},{0,0,0,0},{0,0,0,0}},
            {{0,1,0,0},{0,1,0,0},{0,1,1,0},{0,0,0,0}},
            {{0,0,0,0},{1,1,1,0},{1,0,0,0},{0,0,0,0}},
            {{1,1,0,0},{0,1,0,0},{0,1,0,0},{0,0,0,0}}
        }
    };

    private Type type;
    private int rotation;
    private int x, y;

    private Tetromino(Type type) {
        this.type = type;
        this.rotation = 0;
        this.x = 3;
        this.y = 0;
    }

    // ── Factory 패턴 ──────────────────────────────
    public static Tetromino createRandom() {
        Type[] types = Type.values();
        return new Tetromino(types[new Random().nextInt(types.length)]);
    }

    public static Tetromino create(Type type) {
        return new Tetromino(type);
    }

    public Tetromino cloneAt(int x, int y) {
        Tetromino t = new Tetromino(this.type);
        t.x = x;
        t.y = y;
        t.rotation = this.rotation;
        return t;
    }

    // ── 동작 ─────────────────────────────────────
    public void rotateRight() { rotation = (rotation + 1) % 4; }
    public void rotateLeft()  { rotation = (rotation + 3) % 4; }
    public void moveLeft()    { x--; }
    public void moveRight()   { x++; }
    public void moveDown()    { y++; }

    // ── Getter / Setter ───────────────────────────
    public int[][]  getShape()    { return SHAPES[type.ordinal()][rotation]; }
    public Color    getColor()    { return COLORS[type.ordinal()]; }
    public Type     getType()     { return type; }
    public int      getRotation() { return rotation; }
    public void     setRotation(int r) { this.rotation = r; }
    public int      getX()        { return x; }
    public int      getY()        { return y; }
    public void     setX(int x)   { this.x = x; }
    public void     setY(int y)   { this.y = y; }
}
