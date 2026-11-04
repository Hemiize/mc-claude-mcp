package dev.leon.claudemcp.control;

import com.mojang.blaze3d.platform.NativeImage;
import dev.leon.claudemcp.core.MainThread;
import dev.leon.claudemcp.mcp.Tool;
import dev.leon.claudemcp.script.TickClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Framebuffer screenshots, scaled and encoded for MCP image content. */
public final class Screenshots {
	private Screenshots() {}

	/** Must be called from a non-client thread. */
	public static Tool.Image capture(int width, boolean hideHud, String format) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		boolean prevHide = MainThread.call(() -> mc.gui.hud.isHidden());
		if (hideHud && !prevHide) {
			MainThread.run(() -> mc.gui.hud.toggle());
			// let at least two frames render without HUD
			long t0 = TickClock.now();
			long end = System.currentTimeMillis() + 500;
			while (TickClock.now() < t0 + 2 && System.currentTimeMillis() < end) Thread.sleep(10);
		}
		CompletableFuture<BufferedImage> f = new CompletableFuture<>();
		try {
			MainThread.run(() -> Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
				try {
					f.complete(toBuffered(img));
				} catch (Throwable t) {
					f.completeExceptionally(t);
				} finally {
					img.close();
				}
			}));
			BufferedImage full = f.get(5, TimeUnit.SECONDS);
			return encode(full, width, format);
		} finally {
			if (hideHud && !prevHide) MainThread.run(() -> {
				if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
		}
	}

	static BufferedImage toBuffered(NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] px = img.getPixels(); // ARGB
		bi.setRGB(0, 0, w, h, px, 0, w);
		return bi;
	}

	static Tool.Image encode(BufferedImage full, int width, String format) throws Exception {
		int w = Math.max(64, Math.min(width <= 0 ? full.getWidth() : width, full.getWidth()));
		int h = (int) Math.round(full.getHeight() * (w / (double) full.getWidth()));
		BufferedImage scaled = full;
		if (w != full.getWidth()) {
			scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(full, 0, 0, w, h, null);
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String mime;
		if ("png".equalsIgnoreCase(format)) {
			ImageIO.write(scaled, "png", out);
			mime = "image/png";
		} else {
			ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
			ImageWriteParam p = wr.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(0.82f);
			try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
				wr.setOutput(ios);
				wr.write(null, new IIOImage(scaled, null, null), p);
			}
			wr.dispose();
			mime = "image/jpeg";
		}
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("width", w);
		info.put("height", h);
		info.put("source", full.getWidth() + "x" + full.getHeight());
		info.put("bytes", out.size());
		return new Tool.Image(Base64.getEncoder().encodeToString(out.toByteArray()), mime, info);
	}
}
