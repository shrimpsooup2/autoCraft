package com.autocraft.goal;

import org.jspecify.annotations.Nullable;

/**
 * Where a task ends once its own goal is met: at the base named {@code base} (by name, so a moved base still counts;
 * through a portal when it's in the other dimension), doing {@code deposit} there. No ending (null) = the task ends
 * wherever it is.
 */
public record Ending(String base, Deposit deposit) {
	/** What happens at the base. */
	public enum Deposit {
		/** Just go there. */
		NONE("just go there", "go"),
		/** Everything but the bot's own kit into the chest with the most room. */
		EMPTIEST("emptiest chest", "emptiest"),
		/** Each stack into a chest that already holds it, else one holding something similar, else the emptiest. */
		SIMILAR("chests with similar items", "similar");

		public final String label;
		/** The word used in commands ({@code then home similar}). */
		public final String word;

		Deposit(String label, String word) {
			this.label = label;
			this.word = word;
		}

		/** The mode for a command word ({@code go}, {@code emptiest}, {@code similar}), or null. */
		public static @Nullable Deposit ofWord(String word) {
			for (Deposit d : values()) {
				if (d.word.equalsIgnoreCase(word)) return d;
			}
			return null;
		}
	}

	/** "base "home" (deposit: chests with similar items)". */
	public String describe() {
		return "base \"" + base + "\"" + (deposit == Deposit.NONE ? "" : " (deposit: " + deposit.label + ")");
	}
}
