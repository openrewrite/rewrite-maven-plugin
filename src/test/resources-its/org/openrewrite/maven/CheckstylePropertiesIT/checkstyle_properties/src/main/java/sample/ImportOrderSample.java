package sample;

import java.net.URL;
import java.util.ArrayList;

public class ImportOrderSample {
    public void useUtilPackage() {
        ArrayList<String> list = new ArrayList<>();
        list.add("Hello");
        list.add("World");
        for (String s : list) {
            System.out.println(s);
        }
    }

    public void useNetPackage() {
        try {
            URL url = new URL("https://github.com");
            url.openConnection();
        } catch (Exception e) {
            System.out.println("Failed to connect to github.com! " + e.getMessage());
        }
    }
}
