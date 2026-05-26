package TETRIS;

import TETRIS.network.NetMessage;
import TETRIS.network.NetworkManager;

import javax.swing.Timer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * 온라인 멀티 게임 패널 — 최대 4인 배틀로얄.
 *
 * <h3>레이아웃</h3>
 * <pre>
 *  [내 큰 보드 + 사이드] [중앙 안내] [상대 미니뷰들 (가로 나열)]
 *
 *  미니뷰 셀 크기:
 *    2인 → 28px (1:1과 동일한 크기)
 *    3인 → 20px
 *    4인 → 14px
 * </pre>
 *
 * <h3>네트워크 흐름</h3>
 * <ul>
 *   <li>STATE: 자기 보드 상태(20Hz) → 호스트가 다른 클라이언트에게 broadcastExcept</li>
 *   <li>GARBAGE(target=N): 누구를 공격할지 — 호스트가 라우팅</li>
 *   <li>GAMEOVER: 자기 패배 알림 → 호스트가 ELIMINATED broadcast (rank 부여)</li>
 *   <li>ELIMINATED: 모든 클라이언트에게 누가 몇 등으로 탈락했는지 알림</li>
 *   <li>RANKING: 게임 종료 시 최종 순위(1~4등 ID 나열)</li>
 * </ul>
 */
public class NetworkPanel extends JPanel implements KeyListener {

    // ── 내 보드 크기 (항상 큰 보드) ─────────────────────────
    static final int CELL    = 28;
    static final int BOARD_W = CELL * Board.COLS;   // 280
    static final int BOARD_H = CELL * Board.ROWS;   // 560
    static final int SIDE_W  = 108;
    static final int MINI_C  = 20;                  // 내 사이드의 HOLD/NEXT 크기
    static final int BY      = 36;

    static final int P1_BX   = 10;
    static final int P1_SX   = P1_BX + BOARD_W + 8;     // 298
    static final int CTR_X   = P1_SX + SIDE_W;          // 406
    static final int CTR_W   = 180;
    static final int MINI_START_X = CTR_X + CTR_W;      // 586

    // ── 색상 팔레트 ────────────────────────────────────────
    private static final Color GARBAGE_COLOR = new Color(110, 115, 130);
    private static final Color[] PALETTE = {
            null,                          // 0
            new Color(0,   240, 240),      // 1 I
            new Color(240, 240, 0),        // 2 O
            new Color(160, 0,   240),      // 3 T
            new Color(0,   240, 0),        // 4 S
            new Color(240, 0,   0),        // 5 Z
            new Color(0,   0,   240),      // 6 J
            new Color(240, 160, 0),        // 7 L
            GARBAGE_COLOR                  // 8 가비지
    };

    // ── 네트워크/세션 ──────────────────────────────────────
    private NetworkManager network;
    private boolean        isHost;
    private int            myId       = 1;
    private int            maxPlayers = 4;

    // ── 내 상태 ────────────────────────────────────────────
    private final Board     board = new Board();
    private Tetromino       current, next, hold;
    private boolean         canHold = true, lastRot = false, alive = true;
    private int             score = 0, level = 1, lines = 0;
    private String          tMsg  = "";
    private int             delayedGarbage = 0;
    private long            garbageTime    = 0;
    private final PieceBag  bag = new PieceBag();

    // ── 상대(타 플레이어) 상태 ─────────────────────────────
    /** id → OpponentState. 호스트일 때는 자기 자신도 여기에 넣어 두면 좋지만 분리. */
    private final Map<Integer, OpponentState> opps = new LinkedHashMap<>();
    /** id → 이름 (방에서 받아옴). 호스트 본인 + 모든 상대 포함. */
    private final Map<Integer, String>        names = new LinkedHashMap<>();

    private static class OpponentState {
        int     id;
        int[][] board   = new int[Board.ROWS][Board.COLS];
        Tetromino.Type curType, nextType, holdType;
        int     curX, curY, curRot;
        int     score = 0, level = 1, lines = 0;
        int     delayedGarbage = 0;
        boolean alive = true;
    }

    // ── 게임 타이밍 ────────────────────────────────────────
    private Timer       timer;
    private long        lastDrop    = 0;
    private long        lastNetSend = 0;
    private long        lockStartTime = 0;
    private static final long NET_INTERVAL  = 50;
    private static final long LOCK_DELAY_MS = 500;

    // ── 이펙트 ──────────────────────────────────────────
    private int[]       clearFlashRows = new int[0];
    private long        clearFlashTime = 0;
    private static final long CLEAR_FLASH_MS = 140;
    private List<int[]> dropTrail      = new ArrayList<>();
    private Color       dropTrailColor = null;
    private long        dropTrailTime  = 0;
    private static final long DROP_TRAIL_MS = 150;
    private List<int[]> lockFlash      = new ArrayList<>();
    private long        lockFlashTime  = 0;
    private static final long LOCK_FLASH_MS = 100;

    // ── 게임 종료/랭킹 ─────────────────────────────────────
    private boolean gameOver = false;
    /** 탈락 순서대로 등록되는 ID 리스트 (먼저 죽은 사람이 앞). */
    private final List<Integer> eliminationOrder = new ArrayList<>();
    /** 최종 랭킹 (1등 → 꼴등). 게임 끝나면 채워짐. */
    private List<Integer> ranking = new ArrayList<>();

    // ── 카운트다운 ────────────────────────────────────────
    private static final long COUNTDOWN_MS = 3000;
    private boolean inCountdown  = false;
    private long    countdownEnd = 0;
    private Timer   countdownTimer;

    // ── 콜백 ──────────────────────────────────────────────
    private Runnable backCallback;
    private Runnable toRoomCallback;

    // ── 초기화 ────────────────────────────────────────────
    public NetworkPanel() {
        setBackground(new Color(22, 26, 42));
        setFocusable(true);
        addKeyListener(this);
        timer = new Timer(16, e -> gameTick());
        // 초기 사이즈는 4인 기준 (가장 큰)
        setPreferredSize(new Dimension(calcPanelWidth(4), BY + BOARD_H + 40));
    }

    public void setBackCallback(Runnable r)   { this.backCallback = r; }
    public void setToRoomCallback(Runnable r) { this.toRoomCallback = r; }

    /**
     * RoomPanel에서 호출. 방의 플레이어 정보를 이어받아 게임 초기 상태를 구성한다.
     */
    public void setupConnection(NetworkManager nm, boolean isHost,
                                 int myId, int maxPlayers,
                                 Map<Integer, RoomPanel.PlayerInfo> roomPlayers) {
        this.network    = nm;
        this.isHost     = isHost;
        this.myId       = myId;
        this.maxPlayers = maxPlayers;

        // 이름/상대 상태 구성
        names.clear();
        opps.clear();
        for (RoomPanel.PlayerInfo p : roomPlayers.values()) {
            names.put(p.id, p.name);
            if (p.id != myId) {
                OpponentState os = new OpponentState();
                os.id = p.id;
                opps.put(p.id, os);
            }
        }

        // 패널 크기 조정
        int n = roomPlayers.size();
        setPreferredSize(new Dimension(calcPanelWidth(n), BY + BOARD_H + 40));
        revalidate();

        network.setOnMessage(msg -> SwingUtilities.invokeLater(() -> handleMessage(msg)));
        network.setOnDisconnect(() -> SwingUtilities.invokeLater(this::onDisconnect));
        if (isHost) {
            network.setOnClientDisconnect(id -> SwingUtilities.invokeLater(() -> onClientLeft(id)));
        }
    }

    // ── 화면 너비 계산 ────────────────────────────────────
    private int calcPanelWidth(int totalPlayers) {
        int n = Math.max(2, Math.min(4, totalPlayers));
        int miniCell = miniCellSize(n);
        int miniBoardW = miniCell * Board.COLS;
        int miniCount = n - 1;
        int miniArea = miniCount * miniBoardW + Math.max(0, miniCount - 1) * 14 + 30;
        return MINI_START_X + miniArea + 10;
    }

    /** 인원수에 따른 미니뷰 셀 크기 */
    private static int miniCellSize(int totalPlayers) {
        switch (totalPlayers) {
            case 2: return 28;
            case 3: return 20;
            case 4: return 14;
            default: return 14;
        }
    }

    // ── 게임 시작 ─────────────────────────────────────────
    public void startGame() {
        gameOver = false;
        ranking.clear();
        eliminationOrder.clear();

        // 내 상태 초기화
        board.clear();
        canHold = true; lastRot = false; alive = true;
        score = 0; level = 1; lines = 0; tMsg = "";
        delayedGarbage = 0; garbageTime = 0;
        hold = null;
        bag.reset();
        next = bag.nextPiece();
        spawnMe();

        // 상대 상태 초기화
        for (OpponentState os : opps.values()) {
            for (int[] row : os.board) Arrays.fill(row, 0);
            os.curType = os.nextType = os.holdType = null;
            os.score = 0; os.level = 1; os.lines = 0;
            os.delayedGarbage = 0;
            os.alive = true;
        }

        lastDrop    = System.currentTimeMillis();
        lastNetSend = 0;
        timer.stop();
        startCountdown();
        requestFocusInWindow();
    }

    private void spawnMe() {
        current = next;
        next = bag.nextPiece();
        canHold = true; lastRot = false;
        lockStartTime = 0;
        lastDrop = System.currentTimeMillis();
        // 스폰 위치 겹침 → 즉시 죽이지 않음 (land 시 top-out 체크)
    }

    // ── 카운트다운 ────────────────────────────────────────
    private void startCountdown() {
        inCountdown = true;
        countdownEnd = System.currentTimeMillis() + COUNTDOWN_MS;
        if (countdownTimer != null && countdownTimer.isRunning()) countdownTimer.stop();
        countdownTimer = new Timer(50, e -> {
            if (System.currentTimeMillis() >= countdownEnd) {
                inCountdown = false;
                ((Timer) e.getSource()).stop();
                lastDrop    = System.currentTimeMillis();
                lastNetSend = 0;
                timer.restart();
            }
            repaint();
        });
        countdownTimer.start();
    }

    // ── 게임 루프 ──────────────────────────────────────────
    private void gameTick() {
        if (gameOver) { repaint(); return; }
        long now = System.currentTimeMillis();

        // 내 가비지 2초 후 적용
        if (alive && delayedGarbage > 0 && now - garbageTime >= 2000) {
            for (int i = 0; i < delayedGarbage; i++)
                board.addGarbageLine((int)(Math.random() * Board.COLS));
            delayedGarbage = 0;
            // 가비지 추가 후 즉시 죽이지 않음 → 다음 land 시 top-out 체크
        }

        // 내 자동 낙하 + lock delay
        if (alive) {
            if (isOnGround()) {
                if (lockStartTime == 0) lockStartTime = now;
                if (now - lockStartTime >= LOCK_DELAY_MS) {
                    land();
                    lockStartTime = 0;
                }
            } else {
                lockStartTime = 0;
                if (now - lastDrop >= getSpeed()) {
                    lastDrop = now;
                    current.moveDown();
                    if (!board.isValidPosition(current)) {
                        current.setY(current.getY() - 1);
                        // 바닥에 닿음 → 다음 tick에서 lock 시작 (즉시 land 하지 않음)
                    }
                }
            }
        }

        // 주기적 상태 송신
        if (alive && now - lastNetSend >= NET_INTERVAL) {
            lastNetSend = now;
            sendMyState();
        }
        repaint();
    }

    /** 현재 피스가 바닥에 닿아 있는지(한 칸 더 못 내려가는지) */
    private boolean isOnGround() {
        if (current == null) return false;
        current.moveDown();
        boolean grounded = !board.isValidPosition(current);
        current.setY(current.getY() - 1);
        return grounded;
    }

    /** 이동/회전 후 호출. 공중이면 lock 해제, 바닥이면 lock 타이머 리셋(=유예 갱신). */
    private void refreshLock() {
        if (current == null) return;
        if (isOnGround()) {
            lockStartTime = System.currentTimeMillis();
        } else {
            lockStartTime = 0;
        }
    }

    private void land() {
        boolean ts = isTSpin();
        tMsg = ts ? "T-SPIN!" : "";

        // [이펙트] 고정 플래시
        lockFlash.clear();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (current.getShape()[r][c] == 1 && current.getY()+r >= 0)
                lockFlash.add(new int[]{current.getY()+r, current.getX()+c});
        }
        lockFlashTime = System.currentTimeMillis();

        board.placePiece(current);

        // [이펙트] 라인 플래시
        int[] fullRows = board.getFullRowIndices();
        if (fullRows.length > 0) { clearFlashRows = fullRows; clearFlashTime = System.currentTimeMillis(); }

        int cl = board.clearLines();
        addScore(cl, ts);
        int gb = calcGarbage(cl, ts);
        if (gb > 0) sendGarbageRandom(gb);

        Tetromino testNext = Tetromino.create(next.getType());
        if (!board.isValidPosition(testNext)) { handleMyDeath(); }
        else { spawnMe(); }
    }

    private long getSpeed() { return Math.max(100, 1000 - (level - 1) * 90); }

    private void addScore(int cl, boolean ts) {
        lines += cl;
        int[] ls = {0, 100, 300, 500, 800};
        int[] tv = {400, 800, 1200, 1600};
        if (ts) score += tv[Math.min(cl, 3)] * level;
        else if (cl > 0) score += ls[Math.min(cl, 4)] * level;
        level = lines / 10 + 1;
    }

    private int calcGarbage(int cl, boolean ts) {
        if (cl == 1) return 0;
        int base = cl - 1;
        if (ts) base *= 2;
        return Math.min(base, 4);
    }

    // ── T-스핀 ────────────────────────────────────────────
    private boolean isTSpin() {
        return current.getType() == Tetromino.Type.T && lastRot && corners() >= 3;
    }
    private int corners() {
        int px = current.getX(), py = current.getY(), n = 0;
        for (int[] c : new int[][]{{0,0},{0,2},{2,0},{2,2}}) {
            int r = py + c[0], col = px + c[1];
            if (col < 0 || col >= Board.COLS || r < 0 || r >= Board.ROWS
                    || board.getCell(r, col) != null) n++;
        }
        return n;
    }

    // ── 키 입력 ───────────────────────────────────────────
    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (gameOver) {
            if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_R
                    || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) {
                returnToRoom();
            }
            return;
        }
        if (k == KeyEvent.VK_ESCAPE) {
            if (!alive && !gameOver) {
                // 죽었지만 아직 랭킹 대기 중 → ESC 무시 (랭킹 화면 기다리기)
                return;
            }
            // 항복 또는 게임 중 나가기
            if (alive) handleMyDeath();
            timer.stop();
            if (countdownTimer != null) countdownTimer.stop();
            inCountdown = false;
            returnToRoom();
            return;
        }
        if (inCountdown) return;
        if (!alive)      return;
        switch (k) {
            case KeyEvent.VK_A:     ml();    repaint(); break;
            case KeyEvent.VK_D:     mr();    repaint(); break;
            case KeyEvent.VK_W:     rot();   repaint(); break;
            case KeyEvent.VK_S:     sd();    repaint(); break;
            case KeyEvent.VK_SPACE: hd();    repaint(); break;
            case KeyEvent.VK_Q:     doHold();repaint(); break;
        }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    private void ml()  { current.moveLeft();  if (!board.isValidPosition(current)) current.moveRight(); lastRot = false; refreshLock(); }
    private void mr()  { current.moveRight(); if (!board.isValidPosition(current)) current.moveLeft();  lastRot = false; refreshLock(); }
    private void rot() { if (board.tryRotate(current) != null) { lastRot = true; refreshLock(); } }
    private void sd()  {
        // 소프트 드롭: 한 칸 내려가되 바닥이면 즉시 고정하지 않고 lock delay 적용
        current.moveDown();
        if (!board.isValidPosition(current)) {
            current.setY(current.getY() - 1);
            refreshLock();         // 바닥 → lock 시작
        } else {
            lockStartTime = 0;      // 한 칸 내려갔으면 공중이므로 lock 해제
            lastDrop = System.currentTimeMillis();
        }
    }
    private void hd() {
        // [이펙트] 잔상
        dropTrail.clear();
        dropTrailColor = current.getColor();
        int startY = current.getY();
        int[][] shape = current.getShape();
        Tetromino ghost = current.cloneAt(current.getX(), current.getY());
        while (board.isValidPosition(ghost)) ghost.moveDown();
        ghost.setY(ghost.getY() - 1);
        int endY = ghost.getY();
        for (int dy = startY; dy < endY; dy++)
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                if (shape[r][c] == 1 && dy+r >= 0)
                    dropTrail.add(new int[]{dy+r, current.getX()+c});
        dropTrailTime = System.currentTimeMillis();
        while (board.isValidPosition(current)) current.moveDown();
        current.setY(current.getY()-1); land(); lockStartTime = 0;
    }
    private void doHold() {
        if (!canHold) return;
        canHold = false; lastRot = false;
        if (hold == null) {
            hold = Tetromino.create(current.getType());
            spawnMe();
        } else {
            Tetromino tmp = Tetromino.create(hold.getType());
            hold    = Tetromino.create(current.getType());
            current = tmp;
            // 홀드 교체 후 겹침 허용 → land 시 top-out 체크
        }
    }

    // ── 죽음 처리 ─────────────────────────────────────────
    private void handleMyDeath() {
        if (!alive) return;
        alive = false;
        // 호스트면 즉시 처리, 게스트면 호스트에게 알림
        if (isHost) {
            registerElimination(myId);
        } else {
            network.send(new NetMessage(NetMessage.Type.GAMEOVER).put("from", myId));
        }
    }

    /** 호스트 전용: 한 명이 죽었음을 등록하고 ELIMINATED 브로드캐스트. */
    private void registerElimination(int deadId) {
        if (eliminationOrder.contains(deadId)) return;
        eliminationOrder.add(deadId);
        // 등수 = 총 인원 - (탈락한 사람들 수 - 1)
        // 4명 중 1번째 탈락 = 4등, 2번째 탈락 = 3등 ...
        int totalPlayers = names.size();
        int rank = totalPlayers - eliminationOrder.size() + 1;
        // 모두에게 알림
        NetMessage m = new NetMessage(NetMessage.Type.ELIMINATED)
                .put("id", deadId).put("rank", rank);
        if (network != null) network.broadcast(m);
        // 호스트 자기 사본도 갱신
        applyElimination(deadId);
        // 게임 끝 체크
        checkGameEnd();
    }

    private void applyElimination(int id) {
        if (id == myId) { alive = false; return; }
        OpponentState os = opps.get(id);
        if (os != null) os.alive = false;
    }

    /** 호스트 전용: 살아있는 사람 1명만 남으면 RANKING broadcast + finishGame */
    private void checkGameEnd() {
        if (!isHost) return;
        if (gameOver) return;  // 중복 실행 방지
        int totalPlayers = names.size();
        // 살아있는 사람 수
        int aliveCount = 0;
        int aliveId    = 0;
        if (alive) { aliveCount++; aliveId = myId; }
        for (OpponentState os : opps.values()) {
            if (os.alive) { aliveCount++; aliveId = os.id; }
        }
        if (aliveCount <= 1) {
            // 마지막 남은 사람이 1등, 그 다음은 탈락 순서의 역순
            List<Integer> finalRank = new ArrayList<>();
            if (aliveCount == 1) finalRank.add(aliveId);
            // 탈락 순서를 뒤집어서 (먼저 죽은 사람이 꼴등이므로)
            for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
                finalRank.add(eliminationOrder.get(i));
            }
            // RANKING 송신
            NetMessage m = new NetMessage(NetMessage.Type.RANKING);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < finalRank.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(finalRank.get(i));
            }
            m.put("ids", sb.toString());
            network.broadcast(m);
            applyRanking(finalRank);
        }
    }

    private void applyRanking(List<Integer> rank) {
        if (gameOver) return;  // 이미 랭킹이 확정됐으면 덮어쓰지 않음
        this.ranking = new ArrayList<>(rank);
        gameOver = true;
        timer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        inCountdown = false;
    }

    // ── 네트워크 메시지 처리 ─────────────────────────────
    private void handleMessage(NetMessage msg) {
        switch (msg.type) {
            case STATE:      handleState(msg); break;
            case GARBAGE:    handleGarbage(msg); break;
            case GAMEOVER:   handleGameOver(msg); break;
            case ELIMINATED: handleEliminated(msg); break;
            case RANKING:    handleRanking(msg); break;
            default: break;
        }
        // 호스트는 게스트로부터 받은 STATE/GARBAGE를 다른 사람에게 중계해야 함
        if (isHost) hostRelay(msg);
    }

    /** 호스트: 게스트가 보낸 메시지를 다른 게스트에게 중계 */
    private void hostRelay(NetMessage msg) {
        int from = msg.getInt("from", 0);
        if (from == 0 || from == myId) return;   // from 없거나 호스트 자기 자신이 보낸 거(로컬)
        switch (msg.type) {
            case STATE:
                network.broadcastExcept(from, msg);
                break;
            case GARBAGE: {
                int target = msg.getInt("target", 0);
                if (target == myId) {
                    // 호스트 자신이 맞는 공격은 이미 handleGarbage에서 처리됨 (위 switch)
                } else if (target > 0) {
                    network.sendTo(target, msg);
                }
                break;
            }
            case GAMEOVER:
                // 이미 위 switch에서 처리됨 (호스트가 ELIMINATED broadcast)
                break;
            default:
                break;
        }
    }

    private void handleState(NetMessage msg) {
        int from = msg.getInt("from", 0);
        if (from == myId) return;
        OpponentState os = opps.get(from);
        if (os == null) return;

        String b = msg.get("board", "");
        if (b.length() == Board.ROWS * Board.COLS) {
            for (int r = 0; r < Board.ROWS; r++) {
                for (int c = 0; c < Board.COLS; c++) {
                    char ch = b.charAt(r * Board.COLS + c);
                    int code = (ch >= '0' && ch <= '9') ? ch - '0' : 0;
                    os.board[r][c] = Math.min(code, PALETTE.length - 1);
                }
            }
        }
        os.curType  = parseType(msg.get("cur"));
        os.curX     = msg.getInt("cx", 0);
        os.curY     = msg.getInt("cy", 0);
        os.curRot   = msg.getInt("cr", 0);
        os.nextType = parseType(msg.get("next"));
        os.holdType = parseType(msg.get("hold"));
        os.score    = msg.getInt("sc", 0);
        os.level    = msg.getInt("lv", 1);
        os.lines    = msg.getInt("ln", 0);
        os.delayedGarbage = msg.getInt("dg", 0);
        os.alive    = msg.getInt("alive", 1) == 1;
    }

    private void handleGarbage(NetMessage msg) {
        int target = msg.getInt("target", 0);
        if (target != myId) return;       // 나에게 온 게 아님
        if (!alive) return;
        int lines = msg.getInt("lines", 0);
        if (lines <= 0) return;
        delayedGarbage += lines;
        garbageTime = System.currentTimeMillis();
    }

    private void handleGameOver(NetMessage msg) {
        if (!isHost) return;             // 호스트만 처리
        if (gameOver) return;            // 이미 게임 끝났으면 늦게 온 메시지 무시
        int from = msg.getInt("from", 0);
        if (from <= 0) return;
        registerElimination(from);
    }

    private void handleEliminated(NetMessage msg) {
        int id = msg.getInt("id", 0);
        if (id <= 0) return;
        if (!eliminationOrder.contains(id)) eliminationOrder.add(id);
        applyElimination(id);
    }

    private void handleRanking(NetMessage msg) {
        String s = msg.get("ids", "");
        List<Integer> rank = new ArrayList<>();
        for (String tok : s.split(",")) {
            try { rank.add(Integer.parseInt(tok.trim())); } catch (Exception ignore) {}
        }
        applyRanking(rank);
    }

    private Tetromino.Type parseType(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return null;
        try { return Tetromino.Type.valueOf(s); } catch (Exception e) { return null; }
    }

    private void onDisconnect() {
        if (gameOver) return;
        // 연결 끊김 → 강제 종료
        gameOver = true;
        ranking = new ArrayList<>();
        timer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        inCountdown = false;
    }

    private void onClientLeft(int id) {
        // 호스트: 게임 중에 클라이언트가 나가면 그 사람을 탈락 처리
        if (gameOver) return;
        OpponentState os = opps.get(id);
        if (os != null && os.alive) {
            registerElimination(id);
        }
    }

    // ── 네트워크 송신 ──────────────────────────────────────
    private void sendMyState() {
        if (network == null) return;
        StringBuilder b = new StringBuilder(200);
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                Color col = board.getCell(r, c);
                b.append((char) ('0' + colorToCode(col)));
            }
        }
        NetMessage msg = new NetMessage(NetMessage.Type.STATE)
                .put("from",  myId)
                .put("board", b.toString())
                .put("cur",   current != null ? current.getType().name() : "-")
                .put("cx",    current != null ? current.getX() : 0)
                .put("cy",    current != null ? current.getY() : 0)
                .put("cr",    current != null ? current.getRotation() : 0)
                .put("next",  next != null ? next.getType().name() : "-")
                .put("hold",  hold != null ? hold.getType().name() : "-")
                .put("sc",    score)
                .put("lv",    level)
                .put("ln",    lines)
                .put("dg",    delayedGarbage)
                .put("alive", alive ? 1 : 0);
        if (isHost) {
            // 호스트는 모든 게스트에게 직접 broadcast
            network.broadcast(msg);
        } else {
            // 게스트는 호스트에게 → 호스트가 hostRelay로 중계
            network.send(msg);
        }
    }

    /** 공격 발생 시 살아있는 다른 플레이어 중 무작위 선택. */
    private void sendGarbageRandom(int lines) {
        if (network == null || lines <= 0) return;
        List<Integer> candidates = new ArrayList<>();
        for (OpponentState os : opps.values()) if (os.alive) candidates.add(os.id);
        if (candidates.isEmpty()) return;
        int target = candidates.get(new Random().nextInt(candidates.size()));
        NetMessage m = new NetMessage(NetMessage.Type.GARBAGE)
                .put("from", myId).put("target", target).put("lines", lines);
        if (isHost) {
            // 호스트 → target에게 직접 전달
            if (target == myId) {
                // 자기 자신을 공격할 일은 없지만 안전 처리
                delayedGarbage += lines;
                garbageTime = System.currentTimeMillis();
            } else {
                network.sendTo(target, m);
            }
        } else {
            // 게스트 → 호스트가 라우팅
            network.send(m);
        }
    }

    // ── 종료/복귀 ─────────────────────────────────────────
    private void returnToRoom() {
        timer.stop();
        if (countdownTimer != null) countdownTimer.stop();
        inCountdown = false;
        if (toRoomCallback != null) SwingUtilities.invokeLater(toRoomCallback);
        else if (backCallback != null) SwingUtilities.invokeLater(backCallback);
    }

    private int colorToCode(Color c) {
        if (c == null) return 0;
        for (int i = 1; i < PALETTE.length; i++) {
            if (PALETTE[i] != null && PALETTE[i].getRGB() == c.getRGB()) return i;
        }
        return 8;
    }

    // ─────────────────────────────────────────────────────
    // 렌더링
    // ─────────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 내 큰 보드
        drawPlayerLabel(g2, "YOU" + (isHost ? " (HOST)" : ""), P1_BX, new Color(80, 160, 255));
        drawMyBoard(g2);
        drawMySide(g2);

        // 중앙 안내
        drawCenter(g2);

        // 상대 미니뷰들
        drawOpponents(g2);

        drawBottomHint(g2);

        if (inCountdown) drawCountdown(g2);
        if (gameOver)    drawRankingOverlay(g2);
    }

    private void drawPlayerLabel(Graphics2D g, String label, int bx, Color color) {
        g.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, bx + (BOARD_W - fm.stringWidth(label)) / 2, BY - 6);
    }

    // ── 내 보드 ────────────────────────────────────────────
    private void drawMyBoard(Graphics2D g) {
        int bx = P1_BX;
        drawBoardBg(g, bx, BOARD_W, BOARD_H, CELL);
        // 고정 블록
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                Color col = board.getCell(r, c);
                if (col != null) drawCell(g, bx + c*CELL, BY + r*CELL, col, CELL);
            }
        }
        // 고스트
        if (current != null && alive) {
            Tetromino ghost = current.cloneAt(current.getX(), current.getY());
            while (board.isValidPosition(ghost)) ghost.moveDown();
            ghost.setY(ghost.getY() - 1);
            if (ghost.getY() != current.getY()) {
                Color base = current.getColor();
                Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 50);
                Color brd  = new Color(base.getRed(), base.getGreen(), base.getBlue(), 130);
                for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                    if (ghost.getShape()[r][c] == 1 && ghost.getY() + r >= 0) {
                        int px = bx + (ghost.getX()+c)*CELL, py = BY + (ghost.getY()+r)*CELL;
                        g.setColor(fill); g.fillRect(px+1, py+1, CELL-2, CELL-2);
                        g.setColor(brd);  g.drawRect(px+1, py+1, CELL-3, CELL-3);
                    }
            }
        }
        // 현재 블록
        if (current != null && alive) {
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                if (current.getShape()[r][c] == 1 && current.getY() + r >= 0)
                    drawCell(g, bx + (current.getX()+c)*CELL, BY + (current.getY()+r)*CELL,
                             current.getColor(), CELL);
        }
        if (!tMsg.isEmpty()) {
            g.setFont(new Font("맑은 고딕", Font.BOLD, 16));
            g.setColor(new Color(255,220,50));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(tMsg, bx + (BOARD_W - fm.stringWidth(tMsg))/2, BY + BOARD_H/2 - 10);
        }

        // ── 이펙트 오버레이 ──
        if (alive) {
            long now = System.currentTimeMillis();
            if (dropTrailColor != null && !dropTrail.isEmpty()) {
                long el = now - dropTrailTime;
                if (el < DROP_TRAIL_MS) {
                    float a = 0.5f * (1f - (float)el/DROP_TRAIL_MS);
                    Color tc = new Color(dropTrailColor.getRed(), dropTrailColor.getGreen(),
                            dropTrailColor.getBlue(), (int)(a*255));
                    g.setColor(tc);
                    for (int[] cell : dropTrail)
                        g.fillRect(bx+cell[1]*CELL+1, BY+cell[0]*CELL+1, CELL-2, CELL-2);
                } else { dropTrail.clear(); }
            }
            if (!lockFlash.isEmpty()) {
                long el = now - lockFlashTime;
                if (el < LOCK_FLASH_MS) {
                    float a = 0.65f * (1f - (float)el/LOCK_FLASH_MS);
                    g.setColor(new Color(1f,1f,1f,a));
                    for (int[] cell : lockFlash)
                        g.fillRect(bx+cell[1]*CELL, BY+cell[0]*CELL, CELL, CELL);
                } else { lockFlash.clear(); }
            }
            if (clearFlashRows.length > 0) {
                long el = now - clearFlashTime;
                if (el < CLEAR_FLASH_MS) {
                    float a = 0.88f * (1f - (float)el/CLEAR_FLASH_MS);
                    g.setColor(new Color(1f,1f,1f,a));
                    for (int row : clearFlashRows)
                        g.fillRect(bx, BY+row*CELL, BOARD_W, CELL);
                } else { clearFlashRows = new int[0]; }
            }
        }

        // 내가 죽었으면 어둡게
        if (!alive) {
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRect(bx, BY, BOARD_W, BOARD_H);
            FontMetrics fm;
            g.setFont(new Font("맑은 고딕", Font.BOLD, 32));
            g.setColor(new Color(220, 70, 70));
            fm = g.getFontMetrics();
            String s = "DEAD";
            g.drawString(s, bx + (BOARD_W - fm.stringWidth(s))/2, BY + BOARD_H/2 - 20);
            // 랭킹 대기 중 안내
            if (!gameOver) {
                g.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
                g.setColor(new Color(200, 200, 200));
                fm = g.getFontMetrics();
                String wait = "랭킹 대기 중...";
                g.drawString(wait, bx + (BOARD_W - fm.stringWidth(wait))/2, BY + BOARD_H/2 + 20);
            }
        }
    }

    private void drawBoardBg(Graphics2D g, int bx, int boardW, int boardH, int cell) {
        g.setColor(new Color(80, 85, 115));
        g.fillRect(bx-2, BY-2, boardW+4, boardH+4);
        g.setColor(new Color(16, 20, 36));
        g.fillRect(bx, BY, boardW, boardH);
        g.setColor(new Color(35, 40, 60));
        for (int r = 0; r <= Board.ROWS; r++) g.drawLine(bx, BY+r*cell, bx+boardW, BY+r*cell);
        for (int c = 0; c <= Board.COLS; c++) g.drawLine(bx+c*cell, BY, bx+c*cell, BY+boardH);
    }

    private void drawMySide(Graphics2D g) {
        int sx = P1_SX;
        Font lf = new Font("맑은 고딕", Font.BOLD, 11);
        Font vf = new Font("맑은 고딕", Font.BOLD, 17);
        int y = BY;
        drawMiniBox(g, sx, y,     "HOLD",  hold, !canHold, lf);
        drawMiniBox(g, sx, y+98,  "NEXT",  next, false,    lf);
        drawInfoBox(g, sx, y+204, "SCORE", String.valueOf(score), lf, vf);
        drawInfoBox(g, sx, y+258, "LEVEL", String.valueOf(level), lf, vf);
        drawInfoBox(g, sx, y+312, "LINES", String.valueOf(lines), lf, vf);
        if (delayedGarbage > 0 && alive) {
            g.setColor(new Color(55, 42, 22));
            g.fillRoundRect(sx, y+374, SIDE_W, 36, 8, 8);
            g.setColor(new Color(255, 150, 30));
            g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
            int remain = (int) Math.max(0, (2000 - (System.currentTimeMillis() - garbageTime))) / 1000;
            g.drawString("공격 대기 " + delayedGarbage, sx+5, y+386);
            g.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            g.drawString(remain + "초 후 적용", sx+15, y+398);
        }
    }

    private void drawMiniBox(Graphics2D g, int x, int y, String label, Tetromino piece, boolean grayed, Font lf) {
        g.setColor(new Color(55, 60, 88));
        g.fillRoundRect(x, y, SIDE_W, 90, 8, 8);
        g.setColor(new Color(170, 185, 220));
        g.setFont(lf);
        g.drawString(label, x+42, y+14);
        if (piece == null) return;
        Color col = grayed ? grayOut(piece.getColor()) : piece.getColor();
        int[][] sh = piece.getShape();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
            if (sh[r][c] == 1) drawCell(g, x+8+c*MINI_C, y+18+r*MINI_C, col, MINI_C);
    }
    private Color grayOut(Color c) {
        int avg = (c.getRed()+c.getGreen()+c.getBlue())/3;
        return new Color((avg+100)/2, (avg+100)/2, (avg+115)/2);
    }
    private void drawInfoBox(Graphics2D g, int x, int y, String label, String value, Font lf, Font vf) {
        g.setColor(new Color(55, 60, 88));
        g.fillRoundRect(x, y, SIDE_W, 48, 8, 8);
        g.setColor(new Color(170, 185, 220));
        g.setFont(lf);
        g.drawString(label, x+6, y+14);
        g.setColor(Color.WHITE);
        g.setFont(vf);
        g.drawString(value, x+6, y+38);
    }

    // ── 중앙 안내 ──────────────────────────────────────────
    private void drawCenter(Graphics2D g) {
        int cx = CTR_X + CTR_W / 2;
        g.setColor(new Color(28, 33, 52));
        g.fillRect(CTR_X, BY, CTR_W, BOARD_H);
        g.setColor(new Color(60, 65, 100));
        g.drawRect(CTR_X, BY, CTR_W, BOARD_H);

        Font kf = new Font("맑은 고딕", Font.BOLD, 11);
        Font df = new Font("맑은 고딕", Font.PLAIN, 11);
        int y = BY + 30;

        g.setFont(new Font("맑은 고딕", Font.BOLD, 24));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(200, 200, 255));
        String title = (1 + opps.size()) + "P BATTLE";
        g.drawString(title, cx - fm.stringWidth(title)/2, y);

        y += 10;
        drawDivider(g, y);

        y += 18;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        fm = g.getFontMetrics();
        g.setColor(new Color(100, 165, 255));
        String yc = "YOUR CONTROLS";
        g.drawString(yc, cx - fm.stringWidth(yc)/2, y);

        y += 14;
        drawKeyRow(g, CTR_X + 10, y, "A / D",  "이동",        kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "W",      "회전",        kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "S",      "소프트 드롭", kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Space",  "하드 드롭",   kf, df); y += 15;
        drawKeyRow(g, CTR_X + 10, y, "Q",      "홀드",        kf, df);

        y += 14;
        drawDivider(g, y);

        y += 16;
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
        g.setColor(new Color(150, 165, 200));
        String[] tips = {
                "2줄+ 클리어 시 공격",
                "공격은 랜덤 상대에게",
                "2초 후 적용",
                "마지막 1인이 승리"
        };
        for (String tip : tips) {
            fm = g.getFontMetrics();
            g.drawString(tip, cx - fm.stringWidth(tip)/2, y);
            y += 13;
        }
    }

    private void drawDivider(Graphics2D g, int y) {
        g.setColor(new Color(60, 66, 105));
        g.drawLine(CTR_X + 10, y, CTR_X + CTR_W - 10, y);
    }
    private void drawKeyRow(Graphics2D g, int x, int y, String key, String desc, Font kf, Font df) {
        g.setFont(kf);
        g.setColor(new Color(160, 200, 255));
        g.drawString(key, x, y);
        g.setFont(df);
        g.setColor(new Color(140, 155, 195));
        g.drawString(desc, x + 54, y);
    }

    // ── 상대 미니뷰들 ──────────────────────────────────────
    private void drawOpponents(Graphics2D g) {
        int totalPlayers = names.size();
        int miniCell = miniCellSize(totalPlayers);
        int miniBoardW = miniCell * Board.COLS;
        int miniBoardH = miniCell * Board.ROWS;

        // 상대 정렬 (ID 오름차순)
        List<OpponentState> list = new ArrayList<>(opps.values());
        list.sort((a, b) -> Integer.compare(a.id, b.id));

        int x = MINI_START_X + 10;
        for (OpponentState os : list) {
            drawOpponentMini(g, x, BY, os, miniBoardW, miniBoardH, miniCell);
            x += miniBoardW + 14;
        }
    }

    private void drawOpponentMini(Graphics2D g, int x, int y, OpponentState os,
                                   int boardW, int boardH, int cell) {
        // 이름
        String name = names.getOrDefault(os.id, "P" + os.id);
        g.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        g.setColor(os.alive ? new Color(200, 80, 255) : new Color(140, 140, 150));
        FontMetrics fm = g.getFontMetrics();
        String label = name + "  (ID " + os.id + ")";
        g.drawString(label, x + (boardW - fm.stringWidth(label)) / 2, y - 4);

        // 보드 배경
        g.setColor(new Color(80, 85, 115));
        g.fillRect(x-2, y-2, boardW+4, boardH+4);
        g.setColor(new Color(16, 20, 36));
        g.fillRect(x, y, boardW, boardH);
        g.setColor(new Color(35, 40, 60));
        // 격자는 셀이 작을 때 생략
        if (cell >= 18) {
            for (int r = 0; r <= Board.ROWS; r++) g.drawLine(x, y+r*cell, x+boardW, y+r*cell);
            for (int c = 0; c <= Board.COLS; c++) g.drawLine(x+c*cell, y, x+c*cell, y+boardH);
        }

        // 고정 블록
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                int code = os.board[r][c];
                if (code > 0 && code < PALETTE.length && PALETTE[code] != null) {
                    Color col = os.alive ? PALETTE[code] : grayOut(PALETTE[code]);
                    drawCell(g, x + c*cell, y + r*cell, col, cell);
                }
            }
        }
        // 현재 블록
        if (os.alive && os.curType != null) {
            Tetromino piece = Tetromino.create(os.curType);
            piece.setX(os.curX); piece.setY(os.curY); piece.setRotation(os.curRot & 3);
            Color col = piece.getColor();
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++)
                if (piece.getShape()[r][c] == 1 && os.curY + r >= 0)
                    drawCell(g, x + (os.curX+c)*cell, y + (os.curY+r)*cell, col, cell);
        }
        // 죽었으면 어둡게 + DEAD 표시
        if (!os.alive) {
            g.setColor(new Color(0, 0, 0, 130));
            g.fillRect(x, y, boardW, boardH);
            g.setFont(new Font("맑은 고딕", Font.BOLD, Math.max(14, cell)));
            g.setColor(new Color(220, 70, 70));
            fm = g.getFontMetrics();
            String s = "DEAD";
            g.drawString(s, x + (boardW - fm.stringWidth(s))/2, y + boardH/2);
        }

        // 점수/라인 (보드 아래)
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(170, 185, 220));
        String info = "SCORE " + os.score + "   LV " + os.level;
        fm = g.getFontMetrics();
        g.drawString(info, x + (boardW - fm.stringWidth(info)) / 2, y + boardH + 16);

        // 대기 가비지 작게
        if (os.delayedGarbage > 0 && os.alive) {
            g.setColor(new Color(255, 150, 30));
            g.setFont(new Font("맑은 고딕", Font.BOLD, 11));
            String gs = "공격 " + os.delayedGarbage + " 대기";
            fm = g.getFontMetrics();
            g.drawString(gs, x + (boardW - fm.stringWidth(gs)) / 2, y + boardH + 30);
        }
    }

    // ── 하단 힌트 ──────────────────────────────────────────
    private void drawBottomHint(Graphics2D g) {
        g.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        g.setColor(new Color(100, 115, 155));
        String hint = "ESC: 항복 후 방으로";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, (getWidth() - fm.stringWidth(hint))/2, BY + BOARD_H + 22);
    }

    // ── 카운트다운 ────────────────────────────────────────
    private void drawCountdown(Graphics2D g) {
        long remain = countdownEnd - System.currentTimeMillis();
        if (remain < 0) remain = 0;
        int W = getWidth(), H = getHeight();

        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, W, H);

        g.setFont(new Font("맑은 고딕", Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(200, 220, 255, 220));
        String ready = "READY";
        g.drawString(ready, (W - fm.stringWidth(ready))/2, H/2 - 70);

        int sec = (int) Math.ceil(remain / 1000.0);
        if (sec < 1) sec = 1;
        if (sec > 3) sec = 3;
        long inSec = remain % 1000;
        double progress = 1.0 - (inSec / 1000.0);
        int size = (int) (140 + 30 * (1.0 - Math.abs(0.5 - progress) * 2));
        g.setFont(new Font("맑은 고딕", Font.BOLD, size));
        fm = g.getFontMetrics();
        String numStr = String.valueOf(sec);
        g.setColor(new Color(0, 0, 0, 180));
        g.drawString(numStr, (W - fm.stringWidth(numStr))/2 + 4, H/2 + 40 + 4);
        Color c = (sec == 3) ? new Color(120, 220, 255)
                : (sec == 2) ? new Color(255, 220, 100)
                             : new Color(255, 130, 100);
        g.setColor(c);
        g.drawString(numStr, (W - fm.stringWidth(numStr))/2, H/2 + 40);
    }

    // ── 랭킹 화면 ──────────────────────────────────────────
    private void drawRankingOverlay(Graphics2D g) {
        int W = getWidth(), H = getHeight();
        // 검은 오버레이
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, W, H);

        // 박스
        int boxW = 420, boxH = 70 + Math.max(1, ranking.size()) * 44 + 50;
        int boxX = (W - boxW) / 2;
        int boxY = (H - boxH) / 2;
        g.setColor(new Color(35, 42, 70));
        g.fillRoundRect(boxX, boxY, boxW, boxH, 16, 16);
        g.setColor(new Color(120, 160, 230));
        g.drawRoundRect(boxX, boxY, boxW, boxH, 16, 16);

        // 타이틀
        g.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 220, 80));
        String title = ranking.isEmpty() ? "GAME ENDED" : "🏆  RANKING";
        g.drawString(title, boxX + (boxW - fm.stringWidth(title))/2, boxY + 36);

        // 순위 리스트
        int yy = boxY + 70;
        g.setFont(new Font("맑은 고딕", Font.BOLD, 16));
        for (int i = 0; i < ranking.size(); i++) {
            int id = ranking.get(i);
            String name = names.getOrDefault(id, "P" + id);
            boolean me = (id == myId);
            Color clr;
            String medal;
            switch (i) {
                case 0:  clr = new Color(255, 215, 60);   medal = "1st"; break;
                case 1:  clr = new Color(190, 190, 210);  medal = "2nd"; break;
                case 2:  clr = new Color(205, 130, 70);   medal = "3rd"; break;
                default: clr = new Color(150, 165, 200);  medal = (i+1) + "th";
            }
            g.setColor(clr);
            g.drawString(medal, boxX + 30, yy);
            g.setColor(me ? new Color(120, 230, 120) : Color.WHITE);
            g.drawString(name + (me ? "  (나)" : ""), boxX + 110, yy);
            g.setColor(new Color(140, 155, 195));
            g.setFont(new Font("맑은 고딕", Font.PLAIN, 14));
            g.drawString("ID " + id, boxX + boxW - 70, yy);
            g.setFont(new Font("맑은 고딕", Font.BOLD, 16));
            yy += 44;
        }

        g.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        g.setColor(new Color(180, 185, 210));
        String hint = "아무 키 → 방으로 돌아가기";
        fm = g.getFontMetrics();
        g.drawString(hint, boxX + (boxW - fm.stringWidth(hint))/2, boxY + boxH - 16);
    }

    // ── 셀 그리기 ──────────────────────────────────────────
    private void drawCell(Graphics2D g, int x, int y, Color color, int size) {
        g.setColor(color);
        g.fillRect(x+1, y+1, size-2, size-2);
        if (size >= 14) {
            // 상단 광택
            g.setColor(new Color(255, 255, 255, 50));
            g.fillRect(x+2, y+2, size-4, (size-2)/2);
            g.setColor(color.brighter());
            g.drawLine(x+1, y+1, x+size-2, y+1);
            g.drawLine(x+1, y+1, x+1, y+size-2);
            g.setColor(color.darker().darker());
            g.drawLine(x+size-2, y+1, x+size-2, y+size-2);
            g.drawLine(x+1, y+size-2, x+size-2, y+size-2);
        }
    }
}
