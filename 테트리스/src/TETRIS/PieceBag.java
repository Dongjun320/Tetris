package TETRIS;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 7-bag 무작위 피스 생성기.
 *
 * 7가지 테트로미노({@code I, O, T, S, Z, J, L})를 한 가방에 모두 넣고
 * 무작위 순서로 한 개씩 꺼낸다. 가방이 비면 자동으로 새 가방을 채운다.
 *
 * <p>장점:</p>
 * <ul>
 *   <li>같은 블록이 연속 3번 나오는 일이 사실상 없음 (최악도 2회)</li>
 *   <li>특정 블록이 12개 동안 안 나오는 "가뭄"도 발생하지 않음</li>
 *   <li>현대 테트리스 표준(SRS) 방식</li>
 * </ul>
 *
 * 각 플레이어가 자기 인스턴스를 가져야 한다 (공유하면 한쪽 피스를 가로채기 때문).
 */
public class PieceBag {

    private final Deque<Tetromino.Type> bag = new ArrayDeque<>();

    /** 다음 피스의 타입을 반환. 가방이 비어 있으면 새로 채운다. */
    public Tetromino.Type next() {
        if (bag.isEmpty()) refill();
        return bag.poll();
    }

    /** 다음 피스를 새 Tetromino 인스턴스로 반환. */
    public Tetromino nextPiece() {
        return Tetromino.create(next());
    }

    /** 가방을 비우고 다시 채움 (재시작 등에 사용). */
    public void reset() {
        bag.clear();
    }

    private void refill() {
        List<Tetromino.Type> list = new ArrayList<>();
        for (Tetromino.Type t : Tetromino.Type.values()) list.add(t);
        Collections.shuffle(list);
        bag.addAll(list);
    }
}
