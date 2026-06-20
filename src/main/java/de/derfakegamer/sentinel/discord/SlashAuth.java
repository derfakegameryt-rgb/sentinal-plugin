package de.derfakegamer.sentinel.discord;
import java.util.List;
import java.util.Set;
public final class SlashAuth {
    private SlashAuth() {}
    public static boolean mayModerate(Set<String> memberRoleIds, List<String> staffRoleIds) {
        if (memberRoleIds == null || staffRoleIds == null) return false;
        for (String r : staffRoleIds) if (memberRoleIds.contains(r)) return true;
        return false;
    }
}
