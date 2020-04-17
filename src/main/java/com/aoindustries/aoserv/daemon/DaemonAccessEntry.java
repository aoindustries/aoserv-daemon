/*
 * Copyright 2000-2009, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

/**
 * @see  AOServDaemonServer#getDaemonAccessEntry
 *
 * @author  AO Industries, Inc.
 */
public class DaemonAccessEntry {

	public final long key;
	public final int command;
	public final String param1;
	public final String param2;
	public final String param3;
	public final String param4;
	public final long created;

	public DaemonAccessEntry(
		long key,
		int command,
		String param1,
		String param2,
		String param3,
		String param4
	) {
		this.key = key;
		this.command = command;
		this.param1 = param1;
		this.param2 = param2;
		this.param3 = param3;
		this.param4 = param4;
		this.created = System.currentTimeMillis();
	}
}