package dev.leon.claudemcp.hud;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.core.TaskManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small overlay showing what Claude is doing; also runs "hud" hooks. */
public final class ClaudeHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (ClaudeMcp.hooks() != null && ClaudeMcp.hooks().has("hud")) {
			Map<String, Object> d = new HashMap<>();
			d.put("g", g);
			d.put("font", mc.font);
			d.put("delta", delta);
			ClaudeMcp.hooks().fire("hud", d);
		}
		if (!ClaudeMcp.config().hud || mc.gui.hud.isHidden()) return;
		List<TaskManager.Task> running = ClaudeMcp.tasks().running();
		boolean recent = ClaudeMcp.host() != null && System.currentTimeMillis() - ClaudeMcp.host().lastRequestMillis() < 5000;
		if (running.isEmpty() && !recent && ClaudeMcp.startError() == null) return;
		int x = 4, y = 4;
		int color = 0xFFE8A86B;
		if (ClaudeMcp.startError() != null) {
			g.text(mc.font, "Claude MCP: server error, see log", x, y, 0xFFFF5555, true);
			return;
		}
		g.text(mc.font, "Claude" + (recent ? " ●" : "") + (running.isEmpty() ? "" : " - " + running.size() + " task(s)  [End = stop]"), x, y, color, true);
		y += 10;
		int shown = 0;
		for (TaskManager.Task t : running) {
			if (shown++ >= 5) break;
			Map<String, Object> m = t.toMap();
			String line = "#" + t.id + " " + t.description;
			Object p = m.get("progress");
			if (p != null) line += "  " + p;
			if (line.length() > 90) line = line.substring(0, 90) + "...";
			g.text(mc.font, line, x, y, 0xFFDDDDDD, true);
			y += 10;
		}
	}
}
