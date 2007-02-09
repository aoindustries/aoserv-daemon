package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
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
import java.util.*;

/**
 * Builds the .procmailrc configurations.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcmailManager extends BuilderThread {

    /** Disable email attachment type blocks here. */
    public static final boolean EMAIL_ATTACHMENT_TYPES_ENABLED = false;
    
    /** The file that stores the procmail configuration */
    public static final String PROCMAILRC=".procmailrc";

    /** The old first 6 lines of an auto-generated procmailrc file */
    public static final String OLD_AUTO_PROCMAILRC="#\n"
                                             + "# This .procmailrc file was automatically generated by AutoresponderManager. As\n"
                                             + "# long as this comment remains in this file, any changes will be overwritten.\n"
                                             + "# Removing this comment will allow manual changes to this file, but all\n"
                                             + "# automated functionality will be disabled.\n"
                                             + "#\n";

    /** The first 6 lines of an auto-generated procmailrc file */
    public static final String AUTO_PROCMAILRC="#\n"
                                             + "# This .procmailrc file was automatically generated by ProcmailManager. As\n"
                                             + "# long as this comment remains in this file, any changes will be overwritten.\n"
                                             + "# Removing this comment will allow manual changes to this file, but all\n"
                                             + "# automated functionality will be disabled.\n"
                                             + "#\n";

    private static ProcmailManager procmailManager;

    private ProcmailManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ProcmailManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ProcmailManager.class, "doRebuild()", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            String primaryIP = aoServer.getPrimaryIPAddress().getIPAddress();
            AOServConnector conn=AOServDaemon.getConnector();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                List<LinuxServerAccount> lsas=aoServer.getLinuxServerAccounts();
                for(LinuxServerAccount lsa : lsas) {
                    if(lsa.getLinuxAccount().getType().isEmail()) {
                        String home=lsa.getHome();
                        UnixFile procmailrc=new UnixFile(home, PROCMAILRC);

                        if(home.startsWith("/home/") && !isManual(lsa)) {
                            // Stat for use below
                            Stat procmailrcStat = procmailrc.getStat();
                            boolean isAutoresponderEnabled=lsa.isAutoresponderEnabled();
                            boolean useInbox=lsa.useInbox();
                            List<EmailAttachmentBlock> eabs;
                            if(EMAIL_ATTACHMENT_TYPES_ENABLED) eabs = lsa.getEmailAttachmentBlocks();
                            else eabs = Collections.emptyList();
                            if(isAutoresponderEnabled || !useInbox || !eabs.isEmpty()) {
                                // Build the file in RAM, first
                                ByteArrayOutputStream bout=new ByteArrayOutputStream(4096);
                                ChainWriter out=new ChainWriter(bout);
				try {
				    out.print(AUTO_PROCMAILRC
					      + "\n"
					      + "# Setup the environment\n"
					      + "SHELL=/bin/bash\n"
                                              + "LINEBUF=16384\n");

                                    LinuxAccount la=lsa.getLinuxAccount();
                                    String username=la.getUsername().getUsername();
                                    LinuxAccAddress laa=lsa.getAutoresponderFrom();
                                    List<LinuxAccAddress> addresses=lsa.getLinuxAccAddresses();

                                    // The same X-Loop is used for attachment filters and autoresponders
                                    String xloopAddress=username+'@'+lsa.getAOServer().getServer().getHostname();

                                    // The default from address
                                    String defaultFromAddress;
                                    if(laa!=null) defaultFromAddress=laa.getEmailAddress().toString();
                                    else {
                                        if(addresses.size()>=1) defaultFromAddress=addresses.get(0).getEmailAddress().toString();
                                        else defaultFromAddress=xloopAddress;
                                    }

                                    // Any X-Loop address is sent to the bit-bucket
                                    out.print("\n"
                                            + "# Discard any looped emails\n"
                                            + ":0\n"
                                            + "* ^X-Loop: ").print(xloopAddress).print("\n"
                                            + "/dev/null\n");

                                    String spamAssassinMode = lsa.getEmailSpamAssassinIntegrationMode().getName();
                                    if(!spamAssassinMode.equals(EmailSpamAssassinIntegrationMode.NONE)) {
                                        out.print("\n"
                                                + "# Filter through spamassassin\n"
                                                + ":0fw: spamassassin.lock\n"
                                                + "* < 256000\n"
                                                + "| /usr/bin/spamc -d ").print(primaryIP).print('\n');
                                    }
                                    // First figure out if this message will be rejected due to attachment
                                    if(!eabs.isEmpty()) {
                                        out.print("\n"
                                                + "# Determine if the message contains ANY blocked attachments\n"
                                                + ":0\n"
                                                + "*^Content-Type: (multipart/.*|application/octet-stream)\n"
                                                + "* HB ?? ^Content-(Type|Disposition): .*;.*($.*)?name=(\")?.*\\.");
                                        if(eabs.size()>=1) out.print('(');
                                        for(int d=0;d<eabs.size();d++) {
                                            if(d>0) out.print('|');
                                            out.print(eabs.get(d).getEmailAttachmentType().getExtension());
                                        }
                                        if(eabs.size()>=1) out.print(')');
                                        out.print("(\")?$\n"
                                                + "{\n"
                                        // Second, figure out the To address that was used, to be used as the From address
                                                + "  # Figure out the from address\n"
                                                + "  FROM=`formail -xTo:`\n"
                                                + "\n"
                                                + "  # Figure out the subject\n"
                                                + "  SUBJECT=`formail -xSubject:`\n");

                                        // Third, figure out the specific attachment that was rejected
                                        if(eabs.size()==1) {
                                            EmailAttachmentType eat=eabs.get(0).getEmailAttachmentType();
                                            out.print("\n"
                                                    + "  # Only one extension, use these values\n"
                                                    + "  EXTENSIONS=\" ").print(eat.getExtension()).print("\"\n");
                                        } else {
                                            out.print("\n"
                                                    + "  # Build the list of disallowed extensions\n");
                                            for(int d=0;d<eabs.size();d++) {
                                                String extension=eabs.get(d).getEmailAttachmentType().getExtension();
                                                out.print("  :0\n"
                                                        + "  *^Content-Type: (multipart/.*|application/octet-stream)\n"
                                                        + "  * HB ?? ^Content-(Type|Disposition): .*;.*($.*)?name=(\")?.*\\.").print(extension).print("(\")?$\n"
                                                        + "  {\n"
                                                        + "    EXTENSIONS=\"$EXTENSIONS ").print(extension).print("\"\n"
                                                        + "  }\n");
                                            }
                                        }
                                        // Fourth, send the response
                                        // TODO: Should we send these??? Should we put them in an IMAP folder?  Should we set the subject differently?
                                        // Can we block these attachment types within sendmail at message acceptance time?
                                        // MILTER!!!
                                        out.print("\n"
                                                + "  # Send the response message:\n"
                                                + "  :0 h\n"
                                                + "  | ( \\\n"
                                                + "    formail \\\n"
                                                + "      -r \\\n"
                                                + "      -i\"Subject: BLOCKED:$SUBJECT\" \\\n"
                                                + "      -i\"From: $FROM\" \\\n"
                                                + "      -A\"X-Loop: ").print(xloopAddress).print("\" ; \\\n"
                                                + "      -i\"Precedence: junk\" ; \\\n"
                                                + "      echo \"Your message has not been delivered because it contains at least one restricted\" ; \\\n"
                                                + "      echo \"attachment.\" ; \\\n"
                                                + "      echo \"\" ; \\\n"
                                                + "      echo \"Recipient...........:$FROM\" ; \\\n"
                                                + "      echo \"Subject.............:$SUBJECT\" ; \\\n"
                                                + "      echo \"Detected Extensions.:$EXTENSIONS\" ; \\\n"
                                                + "      echo \"\" ; \\\n"
                                                + "      echo \"If you did not send this message, the most likely cause is a virus on another\" ; \\\n"
                                                + "      echo \"computer.  The virus has used your email address as the from address.  As a\" ; \\\n"
                                                + "      echo \"result, this automated response has been sent to you.\" ; \\\n"
                                                + "      echo \"\" ; \\\n"
                                                + "      echo \"Please scan your computer for viruses using the latest virus definitions.  If\" ; \\\n"
                                                + "      echo \"you are unable to find any viruses then you were probably not the source of\" ; \\\n"
                                                + "      echo \"this email and you may disregard this message.\" ; \\\n"
                                                + "      echo \"\" ; \\\n"
                                                + "      echo \"Blocked Attachment Types Include:\" ; \\\n"
                                                + "      echo \"\" ; \\\n"
                                                + "      echo \"    Extension   Description\" ; \\\n");
                                        for(int d=0;d<eabs.size();d++) {
                                            EmailAttachmentType eat=eabs.get(d).getEmailAttachmentType();
                                            String extension=eat.getExtension();
                                            out.print("      echo \"    ").print(extension);
                                            for(int e=extension.length();e<11;e++) out.print(' ');
                                            out.print(' ').print(eat.getDescription());
                                            out.print("\" ");
                                            if(d<(eabs.size()-1)) out.print("; ");
                                            out.print("\\\n");
                                        }
                                        out.print("  ) | $SENDMAIL -oi -t\n"
                                                + "}\n");
                                    }

                                    // Write the autoresponder if configured
				    if(isAutoresponderEnabled) {
                                        // Figure out the autoresponder details
					String path=lsa.getAutoresponderPath();
					String subject=lsa.getAutoresponderSubject();
					out.print("\n"
						  + "# Configure the autoresponder\n"
						  + ":0 h c\n"
						  + "* !^FROM_DAEMON\n"
						  + "* !From: .*MAILER-DAEMON.*\n"
						  + "* !X-Loop: ").print(xloopAddress).print("\n"
                                                  + "| (/usr/bin/formail -r \\\n"
                                                  + "    -I\"Precedence: junk\" \\\n"
                                                  + "    -i\"From: ").print(defaultFromAddress).print("\" \\\n");
					if(subject!=null) out.print("    -i\"Subject: ").print(subject).print("\" \\\n");
					out.print("    -A\"X-Loop: ").print(xloopAddress).print("\" ");
					if(path==null) out.print("\\\n");
					else {
					    out.print("; \\\n"
                                                    + "    /bin/cat ").print(path).print(" \\\n");
					}
					out.print(") | /usr/sbin/sendmail -oi -t\n");
				    }
				    
                                    if(lsa.useInbox()) {
                                        // Only move to Junk folder when the message could be stored to the inbox
                                        if(spamAssassinMode.equals(EmailSpamAssassinIntegrationMode.IMAP)) {
                                            out.print("\n"
                                                    + "# Place any flagged spam in the Junk folder\n"
                                                    + ":0:\n"
                                                    + "* ^X-Spam-Status: Yes\n"
                                                    + "Mail/Junk\n");
                                        }
                                    } else {
                                        // Discard the email if configured to not use the inbox
                                        out.print("\n"
                                                  + "# Discard the message so it is not stored in the inbox\n"
                                                  + ":0\n"
                                                  + "/dev/null\n");
                                    }

				} finally {
                                    // Close the file and move it into place
				    out.flush();
				    out.close();
				}
                                
                                // Write to disk if different than the copy on disk
                                byte[] newBytes=bout.toByteArray();
                                if(!procmailrcStat.exists() || !procmailrc.contentEquals(newBytes)) {
                                    // Create the new autoresponder config
                                    UnixFile tempUF=new UnixFile(home+"/.procmailrc.");
                                    FileOutputStream fout=tempUF.getSecureOutputStream(
                                        lsa.getUID().getID(),
                                        lsa.getPrimaryLinuxServerGroup().getGID().getID(),
                                        0600,
                                        false
                                    );
				    try {
					fout.write(newBytes);
				    } finally {
					fout.flush();
					fout.close();
				    }
                                    tempUF.renameTo(procmailrc);
                                }
                            } else {
                                // Delete the old procmailrc file because it is not needed
                                if(procmailrcStat.exists()) procmailrc.delete();
                            }
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    /**
     * Determines if an existing procmail file is manually maintained.
     *
     * @exception  SQLException if the account is not an email type
     */
    public static boolean isManual(LinuxServerAccount lsa) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ProcmailManager.class, "isManual(LinuxServerAccount)", null);
        try {
            // Must be an email type
            if(!lsa.getLinuxAccount().getType().isEmail()) throw new SQLException("Not an email inbox: "+lsa.toString());

            String home=lsa.getHome();
            // If the home directory is any of these, it is manually maintained (not maintained)
            if(
                home.equals("/")
                || home.equals("/etc/X11")
                || home.equals("/etc/X11/fs")
                || home.equals("/etc/httpd")
                || home.equals("/opt/interbase")
                || home.equals("/usr/games")
                || home.equals("/var/empty")
                || home.equals("/var/ftp")
                || home.equals("/var/lib/pgsql")
                || home.equals("/var/qmail")
                || home.equals("/var/qmail/alias")
                || home.equals("/var/spool/news")
                || home.equals("/var/spool/uucp")
            ) return true;

            UnixFile procmailrc=new UnixFile(home, PROCMAILRC);
            Stat procmailrcStat = procmailrc.getStat();

            boolean isManual;
            if(
                procmailrcStat.exists()
                && procmailrcStat.isRegularFile()
            ) {
                int len1=AUTO_PROCMAILRC.length();
                StringBuilder SB=new StringBuilder(len1);
                int len2=OLD_AUTO_PROCMAILRC.length();
                StringBuilder oldSB=new StringBuilder(len2);
                int longest=len1>=len2?len1:len2;
                BufferedInputStream in=new BufferedInputStream(new FileInputStream(procmailrc.getFile()));
                try {
                    int count=0;
                    int ch;
                    while(count<longest && (ch=in.read())!=-1) {
                        if(count<len1) SB.append((char)ch);
                        if(count<len2) oldSB.append((char)ch);
                        count++;
                    }
                } finally {
                    in.close();
                }
                isManual=!(SB.toString().equals(AUTO_PROCMAILRC) || oldSB.toString().equals(OLD_AUTO_PROCMAILRC));
            } else isManual=false;
            return isManual;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ProcmailManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(ProcmailManager.class) && procmailManager==null) {
                synchronized(System.out) {
                    if(procmailManager==null) {
                        System.out.print("Starting ProcmailManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        procmailManager=new ProcmailManager();
                        if(EMAIL_ATTACHMENT_TYPES_ENABLED) connector.emailAttachmentBlocks.addTableListener(procmailManager, 0);
                        connector.ipAddresses.addTableListener(procmailManager, 0);
                        connector.linuxServerAccounts.addTableListener(procmailManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ProcmailManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild .procmailrc files";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}
