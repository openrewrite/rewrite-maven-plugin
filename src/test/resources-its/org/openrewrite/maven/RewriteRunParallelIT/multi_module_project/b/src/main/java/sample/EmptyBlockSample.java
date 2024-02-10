package sample;

import java.util.Random;

public class EmptyBlockSample {
    int n = sideEffect();

    static {
    }

    int sideEffect() {
        return new Random().nextInt();
    }
}
