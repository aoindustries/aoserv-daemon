package com.aoindustries.aoserv.daemon.slocate;

/*
 * Copyright 2002-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.table.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.List;

/**
 * Controls the slocate file indexing
 *
 * @author  AO Industries, Inc.
 */
final public class SLocateManager extends BuilderThread {

    private static final String mandrakeConfigFile="/etc/updatedb.conf";
    private static final String newMandrakeConfigFile="/etc/updatedb.conf.new";

    private SLocateManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SLocateManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SLocateManager.class, "doRebuild()", null);
        try {
            AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            List<BackupPartition> bps=thisAOServer.getBackupPartitions();
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPKey();

            synchronized (rebuildLock) {
                if(
                    osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                ) {
                    UnixFile newUF=new UnixFile(newMandrakeConfigFile);
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            newUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, false)
                        )
                    );
                    try {
                        out.print("## Linux-Mandrake configuration.\n"
                                + "\n"
                                + "#\n"
                                + "# Originally written by Chmouel Boudjnah <chmouel@mandrakesoft.com>\n"
                                + "#\n"
                                + "# Modified 20010109 by Francis Galiegue <fg@mandrakesoft.com>\n"
                                + "#\n"
                                + "# Fixes by mlord@pobox.com, 20010328\n"
                                + "\n"
                                + "# Where to start.\n"
                                + "FROM=\"/\"\n"
                                + "\n"
                                + "# Which directories to exclude. /home and /root are excluded for privacy, but\n"
                                + "# YMMV\n"
                                + "# Generated by "+SLocateManager.class.getName()+"\n"
                                + "PRUNEPATHS=\"/proc,/tmp,/var/tmp,/usr/tmp,/net,/afs,/mnt,/var/failover");
                        for(int c=0;c<bps.size();c++) out.print(',').print(bps.get(c).getPath());
                        out.print("\"\n"
                                + "\n"
                                + "# Security level :\n"
                                + "#       0 turns security checks off. This will make searchs faster.\n"
                                + "#       1 turns security checks on. This is the default.\n"
                                + "SECURITY=\"1\"\n"
                                + "\n"
                                + "# Be verbose or no.\n"
                                + "VERBOSE=\"NO\"\n"
                                + "\n"
                                + "# Where the database is located.\n"
                                + "DATABASE=\"/var/lib/slocate/slocate.db\"\n"
                                + "\n"
                                + "\n"
                                + "# Which filesystems do we exclude from search?\n"
                                + "PRUNEFS=\"nfs,smbfs,ncpfs,proc,devpts,supermount,vfat,iso9660,udf,usbdevfs,devfs\"\n"
                        );
                    } finally {
                        out.flush();
                        out.close();
                    }

                    UnixFile mandrakeConfigFileUF=new UnixFile(mandrakeConfigFile);
                    if(!newUF.contentEquals(mandrakeConfigFileUF)) {
                        newUF.renameTo(mandrakeConfigFileUF);
                        mandrakeConfigFileUF.setMode(0644);
                    } else newUF.delete();
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static SLocateManager slocateManager;
    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, SLocateManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(SLocateManager.class) && slocateManager==null) {
                synchronized(System.out) {
                    if(slocateManager==null) {
                        System.out.print("Starting SLocateManager: ");
                        AOServConnector conn=AOServDaemon.getConnector();
                        slocateManager=new SLocateManager();
                        conn.backupPartitions.addTableListener(slocateManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SLocateManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild SLocate";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}