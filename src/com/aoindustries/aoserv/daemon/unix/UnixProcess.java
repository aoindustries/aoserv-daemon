/*
 * Copyright 2000-2009, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import java.io.IOException;
import java.util.logging.Level;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
abstract public class UnixProcess {

	protected int pid;

	/**
	 * Constructs a Unix process given its process ID.
	 */
	public UnixProcess(int pid) {
		this.pid=pid;
	}

	/**
	 * Determines the group ID of a process.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public int getGid() throws IOException;

	/**
	 * Determines the user ID of a process.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public int getUid() throws IOException;

	/**
	 * Determines if the process is currently running.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public boolean isRunning() throws IOException;

	/**
	 * Kills this process.  Sends a term signal once, waits two seconds,
	 * then sends a kill signal.  The signals are sent to the execution of the
	 * <code>/bin/kill</code> executable.
	 */
	public void killProc() throws IOException {
		String pidS=String.valueOf(pid);
		if(isRunning()) {
			AOServDaemon.exec("/bin/kill", "-SIGTERM", pidS);
		}
		if(isRunning()) {
			try {
				Thread.sleep(2000);
			} catch(InterruptedException err) {
				LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
				// Restore the interrupted status
				Thread.currentThread().interrupt();
			}
		}
		if(isRunning()) {
			AOServDaemon.exec("/bin/kill", "-SIGKILL", pidS);
		}
	}

	/**
	 * Sends a signal to this process.  The signals are sent to the execution of the
	 * <code>/bin/kill</code> executable.
	 */
	public void signal(String signalName) throws IOException {
		AOServDaemon.exec(
			"/bin/kill",
			"-"+signalName,
			Integer.toString(pid)
		);
	}
}
