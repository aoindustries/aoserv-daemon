/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2014, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoapps.lang.EmptyArrays;

/**
 * Encapsulates the output of the /proc/swaps file
 *
 * @author  AO Industries, Inc.
 */
final public class ProcSwaps {

	final public int[]
		device_majors,
		device_minors,
		totals,
		useds
	;

	public ProcSwaps() {
		device_majors=device_minors=totals=useds = EmptyArrays.EMPTY_INT_ARRAY;
	}
}
