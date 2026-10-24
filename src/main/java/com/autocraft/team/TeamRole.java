package com.autocraft.team;

/** What one bot does for a shared build. */
public enum TeamRole {
	/** Working alone: clear, gather and place. */
	SOLO("solo"),
	/** Places blocks (the leader); takes materials from the depot chest. */
	PLACE("placer"),
	/** Digs out the site (foundation and everything in the way), then gathers. */
	CLEAR("digger"),
	/** Gets the materials the placer still needs and delivers them to the depot chest. */
	GATHER("gatherer");

	public final String label;

	TeamRole(String label) {
		this.label = label;
	}
}
