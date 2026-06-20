package de.derfakegamer.sentinel.discord;
import java.util.List;
public record EmbedData(String title, int color, List<Field> fields) {
    public record Field(String name, String value) {}
}
