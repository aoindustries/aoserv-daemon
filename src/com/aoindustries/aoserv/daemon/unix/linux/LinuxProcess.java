package com.aoindustries.aoserv.daemon.unix.linux;

/*
 * Copyright 2000-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.validator.LinuxID;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.unix.UnixProcess;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
public class LinuxProcess extends UnixProcess {

    /** The <code>/proc</code> file is cached after it is created */
    private static File proc;

    /** The <code>/proc/<i>pid</i> file is cached once created */
    private File processProc;

    /**
     * Constructs a Linux process given its process ID.
     */
    public LinuxProcess(int pid) {
        super(pid);
    }

    /**
     * Determines the group ID of the currently running process.
     * The GID is considered the group owner of the file in the
     * /proc directory.  If the process is not running, a
     * FileNotFoundException is thrown.
     */
    public LinuxID getGID() throws IOException, ValidationException {
        return LinuxID.valueOf(new UnixFile(getProc().getPath()).getStat().getGID());
    }

    /**
     * Gets the directory that contains the proc info.
     *
     * @return  the <code>File</code>
     * @exception  IOException if the proc is not mounted
     */
    private File getProc() throws IOException {
        synchronized(this) {
            if(processProc==null) {
                if(proc==null) {
                    proc=new File("/proc");
                    if(!proc.isDirectory()) throw new IOException("Unable to find "+proc.getPath()+" directory");
                }
                processProc=new File(proc, String.valueOf(pid));
            }
            return processProc;
        }
    }

    /**
     * Determines the user ID of the currently running process.
     * The UID is considered the owner of the file in the
     * /proc directory.  If the process is not running, a
     * FileNotFoundException is thrown.
     */
    public LinuxID getUID() throws IOException, ValidationException {
        return LinuxID.valueOf(new UnixFile(getProc().getPath()).getStat().getUID());
    }

    /**
     * Determines if the process is currently running.  The process
     * is considered running if a directory exists in /proc.
     */
    public boolean isRunning() throws IOException {
        return getProc().exists();
    }
}
