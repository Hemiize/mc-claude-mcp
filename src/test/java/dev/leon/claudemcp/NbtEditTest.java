package dev.leon.claudemcp;

import dev.leon.claudemcp.nbt.NbtEditor;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NbtEditTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void mergeSetRemoveReplace() throws Exception {
		CompoundTag base = NbtEditor.parseCompound("{CustomName:\"a\",Items:[{Slot:0b,id:\"minecraft:stone\",count:1}],Lock:{}}");
		CompoundTag merged = NbtEditor.apply(base, NbtEditor.Op.MERGE, null, "{CustomName:\"b\",New:5}");
		assertEquals("b", merged.getStringOr("CustomName", ""));
		assertEquals(5, merged.getIntOr("New", 0));
		assertEquals("a", base.getStringOr("CustomName", ""), "original stays untouched");

		CompoundTag set = NbtEditor.apply(base, NbtEditor.Op.SET_PATH, "Items[0].count", "42");
		assertTrue(set.toString().contains("count:42"));

		CompoundTag removed = NbtEditor.apply(base, NbtEditor.Op.REMOVE_PATH, "Lock", null);
		assertFalse(removed.contains("Lock"));

		CompoundTag replaced = NbtEditor.apply(base, NbtEditor.Op.REPLACE, null, "{x:1}");
		assertEquals(1, replaced.size());

		assertThrows(IllegalArgumentException.class, () -> NbtEditor.apply(base, NbtEditor.Op.REMOVE_PATH, "Nope", null));
		assertThrows(IllegalArgumentException.class, () -> NbtEditor.parseCompound("{broken"));
	}
}
