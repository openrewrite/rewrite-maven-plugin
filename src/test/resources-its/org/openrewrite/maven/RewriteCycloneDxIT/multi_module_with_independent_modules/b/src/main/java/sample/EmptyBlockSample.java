package sample;

import java.nio.file.*;
import java.util.Random;

public class EmptyBlockSample implements MyInterface {
    int n = sideEffect();

    static {
    }

    int sideEffect() {
        return new Random().nextInt();
    }

    boolean boolSideEffect() {
        return sideEffect() == 0;
    }

    public void lotsOfIfs() {
        if(sideEffect() == 1) {}
        if(sideEffect() == sideEffect()) {}
        int n;
        if((n = sideEffect()) == 1) {}
        if((n /= sideEffect()) == 1) {}
        if(new EmptyBlockSample().n == 1) {}
        if(!boolSideEffect()) {}
        if(1 == 2) {}
    }

    public void emptyTry() {
        try {
            Files.lines(Paths.get("somewhere"));
        } catch (Throwable t) {
        } finally {
        }
    }
}
