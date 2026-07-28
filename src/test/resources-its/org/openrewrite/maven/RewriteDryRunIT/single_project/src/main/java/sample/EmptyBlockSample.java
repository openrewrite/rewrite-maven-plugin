package sample;

import java.nio.file.*;
import java.util.Random;

public class EmptyBlockSample {
    int n = sideEffect();

    int sideEffect() {
        return new Random().nextInt();
    }

    boolean boolSideEffect() {
        return sideEffect() == 0;
    }

    public void lotsOfIfs() {
        sideEffect();
        sideEffect();
        sideEffect();
        int n;
        n = sideEffect();
        n /= sideEffect();
        new EmptyBlockSample();
        boolSideEffect();
    }

    public void emptyTry() {
        try {
            Files.lines(Paths.get("somewhere"));
        } catch (Throwable t) {
        }
    }
}
