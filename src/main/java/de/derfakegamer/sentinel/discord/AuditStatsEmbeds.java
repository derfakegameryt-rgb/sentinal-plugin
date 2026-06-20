package de.derfakegamer.sentinel.discord;

import de.derfakegamer.sentinel.model.ActionCount;
import de.derfakegamer.sentinel.model.ActorCount;
import de.derfakegamer.sentinel.model.AuditEntry;
import java.util.ArrayList;
import java.util.List;

public final class AuditStatsEmbeds {
    private AuditStatsEmbeds() {}
    private static final int BLUE = 0x1E88E5;

    public static EmbedData stats(List<ActorCount> actors, List<ActionCount> actions) {
        List<EmbedData.Field> f = new ArrayList<>();
        StringBuilder top = new StringBuilder();
        for (ActorCount a : actors) top.append(a.actor()).append(": ").append(a.count()).append("\n");
        f.add(new EmbedData.Field("Top staff (30d)", top.length() == 0 ? "—" : top.toString().trim()));
        StringBuilder byAct = new StringBuilder();
        for (ActionCount c : actions) byAct.append(c.action()).append(": ").append(c.count()).append("\n");
        f.add(new EmbedData.Field("By action (30d)", byAct.length() == 0 ? "—" : byAct.toString().trim()));
        return new EmbedData("Moderation Stats", BLUE, f);
    }

    public static EmbedData audit(String target, List<AuditEntry> entries) {
        List<EmbedData.Field> f = new ArrayList<>();
        if (entries.isEmpty()) f.add(new EmbedData.Field("History", "—"));
        for (AuditEntry e : entries) f.add(new EmbedData.Field(
            e.action() + (e.target() == null ? "" : " · " + e.target()),
            e.action() + " by " + e.actor() + (e.details() == null || e.details().isBlank() ? "" : " — " + e.details())));
        return new EmbedData("Audit · " + target, BLUE, f);
    }
}
