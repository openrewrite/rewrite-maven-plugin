package sample;

public class AppTest {
    void testApp() {
        // GIVEN
        var name = "test";
        var count = 1;
        // WHEN
        var app = App.builder().name(name).count(count).active(true).build();
        // THEN
        assert app.getName().equals(name);
    }
}
