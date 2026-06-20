package de.derfakegamer.sentinel.discord;
public final class StatusFormatter {
    private StatusFormatter() {}
    public static String format(String template, int online, int max) {
        return template.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max));
    }
}
