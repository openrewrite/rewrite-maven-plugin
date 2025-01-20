package sample;

public class SimplifyBooleanSample {
    boolean ifNoElse() {
        if (isOddMillis()) {
            return true;
        }
        return false;
    }

    static boolean isOddMillis() {
        boolean even = System.currentTimeMillis() % 2 == 0;
        if (even == true) {
                return false;
        }
        else {
            return true;
        }
    }
}
