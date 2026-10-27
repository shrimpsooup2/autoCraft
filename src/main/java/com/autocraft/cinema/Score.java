package com.autocraft.cinema;

/**
 * The film is cut to the song: ParagonX9 — "Chaoz Fantazy" (Newgrounds, CC BY-SA), 162.34 BPM, first beat at
 * 0.031 s, 2:12 long. Every shot starts and ends on a bar, and the moments that matter land on the music: the logo
 * on the first drop, the creeper crit and the water bucket clutch on hits, the ancient city over the quiet
 * breakdown, wool going down as the music comes back, the team build over the final section, the logoff on the
 * last hit.
 *
 * <pre>
 *  bars  0– 8  intro            → Open: dawn, the bot walks out of the trees
 *        8–16  build-up         → Open: the camera rises, the logo flies in
 *       16     DROP             → Open: the logo lands, the title
 *       18–32  main melody      → Pick
 *       32–40  variation        → Plan
 *       40–48  full again       → Find
 *       48–51  break            → Survive: a crit, a kill
 *       51–57  punchy hits      → Nether: drop, water bucket clutch, bucket portal, through it
 *       57–64  breakdown        → Loot: sneaking … 64 the music comes back: the wool, the chest
 *       68–73  build            → Team: hosting, friends join
 *       73–81  finale           → Team: the time-lapse, each player in turn, the flag
 *       81–88  finale           → End: diamonds, the logo, the pillar … 88 last hit: the bot logs off
 * </pre>
 */
final class Score {
	static final double BPM = 162.34;
	static final double BEAT = 60 / BPM;
	static final double BAR = 4 * BEAT;
	static final double FIRST_BEAT = 0.031;
	/** Where each shot starts in the song (seconds), in film order, and where the film ends (a few seconds after the last note). */
	static final double[] CUTS = {0, bar(18), bar(32), bar(40), bar(48), bar(51), bar(57), bar(68), bar(81), 136.0};
	/** For the credits. */
	static final String CREDIT = "Music: ParagonX9 — Chaoz Fantazy (CC BY-SA)";

	private Score() {}

	/** Song time (seconds) of bar b (fractions are beats: 16.5 = two beats into bar 16). */
	static double bar(double b) {
		return FIRST_BEAT + b * BAR;
	}

	/** How long the shot in this slot of the film lasts. */
	static double length(int slot) {
		return CUTS[slot + 1] - CUTS[slot];
	}

	static double start(int slot) {
		return CUTS[slot];
	}
}
