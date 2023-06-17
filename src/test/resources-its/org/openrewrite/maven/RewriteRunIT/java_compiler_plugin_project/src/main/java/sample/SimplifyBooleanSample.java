package sample;

public class SimplifyBooleanSample {
    boolean ifNoElse() {
        return isOddMillis();
    }

    static boolean isOddMillis() {
        boolean even = System.currentTimeMillis() % 2 == 0;
        return !even;
    }
}