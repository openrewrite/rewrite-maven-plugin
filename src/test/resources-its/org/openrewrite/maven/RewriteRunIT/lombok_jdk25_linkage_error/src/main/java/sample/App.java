package sample;

import lombok.Builder;
import lombok.Data;
import lombok.With;

@Data
@Builder
public class App {
    @With
    private String name;
    private int count;
    private boolean active;
}
