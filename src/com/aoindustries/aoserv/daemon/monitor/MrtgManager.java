package com.aoindustries.aoserv.daemon.monitor;

/*
 * Copyright 2006-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles the building of name server processes and files.
 *
 * @author  AO Industries, Inc.
 */
final public class MrtgManager extends BuilderThread {

    public static final int GRAPH_WIDTH=600;
    public static final int GRAPH_HEIGHT=150;

    private static final UnixFile mandrivaCfgFile=new UnixFile("/var/www/html/mrtg/mrtg.cfg");
    private static final UnixFile mandrivaStatsFile=new UnixFile("/var/www/html/mrtg/stats.html");

    private static final UnixFile redhatCfgFile=new UnixFile("/etc/mrtg/mrtg.cfg");
    private static final UnixFile redhatStatsFile=new UnixFile("/var/www/mrtg/stats.html");

    private static MrtgManager mrtgManager;

    private MrtgManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        Server thisServer = thisAOServer.getServer();

        int osv=thisServer.getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
            && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
        ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        final Stat tempStat = new Stat();

        AOServer failoverServer = thisAOServer.getFailoverServer();
        String daemonBin;
        UnixFile cfgFile;
        UnixFile statsFile;
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.MANDRAKE_10_1_I586) {
            daemonBin = "/usr/aoserv/daemon/bin";
            cfgFile = mandrivaCfgFile;
            statsFile = mandrivaStatsFile;
        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
            daemonBin = "/opt/aoserv-daemon/bin";
            cfgFile = redhatCfgFile;
            statsFile = redhatStatsFile;
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        synchronized(rebuildLock) {
            List<String> dfDevices = getDFDevices();
            List<String> dfSafeNames = getSafeNames(dfDevices);
            {
                /*
                 * Create the new config file in RAM first
                 */
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ChainWriter out=new ChainWriter(bout);
                try {
                    out.print("#\n"
                            + "# Automatically generated by ").print(MrtgManager.class.getName()).print("\n"
                            + "#\n");
                    if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("WorkDir: /var/www/html/mrtg\n");
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("HtmlDir: /var/www/mrtg\n"
                                + "ImageDir: /var/www/mrtg\n"
                                + "LogDir: /var/lib/mrtg\n"
                                + "ThreshDir: /var/lib/mrtg\n");
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    out.print("PageTop[^]: <font face=\"Verdana, Arial, Helvetica, sans-serif\">\n"
                            + "  <center>\n"
                            + "  <h1>\n"
                            + "  <img src=\"https://www.aoindustries.com/images/clientarea/accounting/SendInvoices.jpg\"><br>\n"
                            + "  <font color=\"000000\">").print(thisAOServer.getHostname());
                    if(failoverServer!=null) out.print(" on ").print(failoverServer.getHostname());
                    out.print("</font>\n"
                            + "  </h1>\n"
                            + "  <hr><font size=\"1\">\n"
                            + "  | <a href=\"../../MRTG.ao\" target=\"_parent\">Servers</a> |\n"
                            + "  <a href=\"stats.html\">Stats Overview</a> |\n"
                            + "  <a href=\"load.html\">Load</a> |\n"
                            + "  <a href=\"cpu.html\">CPU</a> |\n"
                            + "  <a href=\"diskio.html\">DiskIO</a> |\n");
                    for(int c=0;c<dfDevices.size();c++) {
                        out.print("  <a href=\"").print(dfSafeNames.get(c)).print(".html\">").print(dfDevices.get(c)).print("</a> |\n");
                    }
                    out.print("  <a href=\"mem.html\"> Memory</a> |\n");
                    // Add the network devices
                    List<NetDevice> netDevices=thisServer.getNetDevices();
                    for(NetDevice netDevice : netDevices) {
                        out.print("  <a href=\"").print(netDevice.getNetDeviceID().getName()).print(".html\"> ").print(netDevice.getDescription()).print("</a> |\n");
                    }
                    out.print("  <a href=\"swap.html\">Swap</a> |\n"
                            + "  </font>\n"
                            + "  </center>\n"
                            + "  <hr>\n"
                            + "\n"
                            + "Interval: 5\n");
                    for(NetDevice netDevice : netDevices) {
                        String deviceId=netDevice.getNetDeviceID().getName();
                        out.print("\n"
                                + "Target[").print(deviceId).print("]: `").print(daemonBin).print("/mrtg_net_device ").print(deviceId).print("`\n"
                                + "Options[").print(deviceId).print("]: noinfo, growright, transparent\n"
                                + "MaxBytes[").print(deviceId).print("]: ").print(netDevice.getMaxBitRate()==-1 ? 100000000 : netDevice.getMaxBitRate()).print("\n"
                                + "kilo[").print(deviceId).print("]: 1024\n"
                                + "YLegend[").print(deviceId).print("]: Bits per second\n"
                                + "ShortLegend[").print(deviceId).print("]: b/s\n"
                                + "Legend1[").print(deviceId).print("]: Incoming Traffic in Bits per second\n"
                                + "Legend2[").print(deviceId).print("]: Outgoing Traffic in Bits per second\n"
                                + "Legend3[").print(deviceId).print("]: Maximal 5 Minute Incoming Traffic\n"
                                + "Legend4[").print(deviceId).print("]: Maximal 5 Minute Outgoing Traffic\n"
                                + "LegendI[").print(deviceId).print("]:  In:\n"
                                + "LegendO[").print(deviceId).print("]:  Out:\n"
                                + "Timezone[").print(deviceId).print("]: ").print(thisAOServer.getTimeZone()).print("\n"
                                + "Title[").print(deviceId).print("]: ").print(netDevice.getDescription()).print(" traffic\n"
                                + "PageFoot[").print(deviceId).print("]: <p>\n"
                                + "PageTop[").print(deviceId).print("]: <H2>").print(netDevice.getDescription()).print(" traffic</H2>\n"
                                + "XSize[").print(deviceId).print("]: ").print(GRAPH_WIDTH).print("\n"
                                + "YSize[").print(deviceId).print("]: ").print(GRAPH_HEIGHT).print("\n");
                    }
                    out.print("\n"
                            + "Target[load]: `").print(daemonBin).print("/mrtg_load`\n"
                            + "Options[load]: gauge, noinfo, growright, transparent, nopercent\n"
                            + "MaxBytes[load]: 100000\n"
                            + "YLegend[load]: Load Average (x 1000)\n"
                            + "ShortLegend[load]: / 1000\n"
                            + "Legend1[load]: Load Average\n"
                            + "Legend2[load]: Load Average\n"
                            + "Legend3[load]: Load Average\n"
                            + "Legend4[load]: Load Average\n"
                            + "LegendI[load]:  Load:\n"
                            + "LegendO[load]:  Load:\n"
                            + "Timezone[load]: ").print(thisAOServer.getTimeZone()).print("\n"
                            + "Title[load]: Load Average (x 1000)\n"
                            + "PageFoot[load]: <p>\n"
                            + "PageTop[load]: <H2>Load Average (x 1000)</H2>\n"
                            + "XSize[load]: ").print(GRAPH_WIDTH).print("\n"
                            + "YSize[load]: ").print(GRAPH_HEIGHT).print("\n");
                    // Figure out the number of CPUs
                    int numCPU=getNumberOfCPUs();
                    out.print("\n"
                            + "Target[cpu]: `").print(daemonBin).print("/mrtg_cpu`\n"
                            + "Options[cpu]: gauge, noinfo, growright, transparent, nopercent\n"
                            + "MaxBytes[cpu]: 100\n"
                            + "YLegend[cpu]: CPU Utilization\n"
                            + "ShortLegend[cpu]: %\n");
                    if(numCPU==8) {
                        out.print("Legend1[cpu]: CPU 0 - 3\n"
                                + "Legend2[cpu]: CPU 4 - 7\n"
                                + "Legend3[cpu]: Maximal 5 Minute\n"
                                + "Legend4[cpu]: Maximal 5 Minute\n"
                                + "LegendI[cpu]:  cpu0-3:\n"
                                + "LegendO[cpu]:  cpu4-7:\n");
                    } else if(numCPU==4) {
                        out.print("Legend1[cpu]: CPU 0 and 1\n"
                                + "Legend2[cpu]: CPU 2 and 3\n"
                                + "Legend3[cpu]: Maximal 5 Minute\n"
                                + "Legend4[cpu]: Maximal 5 Minute\n"
                                + "LegendI[cpu]:  cpu0+1:\n"
                                + "LegendO[cpu]:  cpu2+3:\n");
                    } else if(numCPU==2) {
                        out.print("Legend1[cpu]: CPU 0\n"
                                + "Legend2[cpu]: CPU 1\n"
                                + "Legend3[cpu]: Maximal 5 Minute\n"
                                + "Legend4[cpu]: Maximal 5 Minute\n"
                                + "LegendI[cpu]:  cpu0:\n"
                                + "LegendO[cpu]:  cpu1:\n");
                    } else if(numCPU==1) {
                        out.print("Legend1[cpu]: System\n"
                                + "Legend2[cpu]: Total\n"
                                + "Legend3[cpu]: Maximal 5 Minute\n"
                                + "Legend4[cpu]: Maximal 5 Minute\n"
                                + "LegendI[cpu]:  system:\n"
                                + "LegendO[cpu]:  total:\n");
                    } else throw new IOException("Unsupported number of CPUs: "+numCPU);
                    out.print("Timezone[cpu]: ").print(thisAOServer.getTimeZone()).print("\n"
                            + "Title[cpu]: Server CPU Utilization (%)\n"
                            + "PageFoot[cpu]: <p>\n"
                            + "PageTop[cpu]: <H2>Server CPU Utilization (%)</H2>\n"
                            + "XSize[cpu]: ").print(GRAPH_WIDTH).print("\n"
                            + "YSize[cpu]: ").print(GRAPH_HEIGHT).print("\n"
                            + "\n"
                            + "Target[mem]: `").print(daemonBin).print("/mrtg_mem`\n"
                            + "Options[mem]: gauge, noinfo, growright, transparent\n"
                            + "MaxBytes[mem]: 100\n"
                            + "YLegend[mem]: % Free memory and swap space\n"
                            + "ShortLegend[mem]: %\n"
                            + "Legend1[mem]: % swap space used\n"
                            + "Legend2[mem]: % memory used\n"
                            + "Legend3[mem]: Maximal 5 Minute\n"
                            + "Legend4[mem]: Maximal 5 Minute\n"
                            + "LegendI[mem]:  Swp:\n"
                            + "LegendO[mem]:  Mem:\n"
                            + "Timezone[mem]: ").print(thisAOServer.getTimeZone()).print("\n"
                            + "Title[mem]: Server Memory and Swap space\n"
                            + "PageFoot[mem]: <p>\n"
                            + "PageTop[mem]: <H2>Server Memory and Swap space</H2>\n"
                            + "XSize[mem]: ").print(GRAPH_WIDTH).print("\n"
                            + "YSize[mem]: ").print(GRAPH_HEIGHT).print("\n"
                            + "\n"
                            + "Target[diskio]: `").print(daemonBin).print("/mrtg_diskio`\n"
                            + "Options[diskio]: gauge, noinfo, growright, transparent, nopercent\n"
                            + "MaxBytes[diskio]: 100000000\n"
                            + "YLegend[diskio]: Disk I/O blocks/sec\n"
                            + "ShortLegend[diskio]: blk/s\n"
                            + "Legend1[diskio]: read\n"
                            + "Legend2[diskio]: write\n"
                            + "Legend3[diskio]: Maximal 5 Minute\n"
                            + "Legend4[diskio]: Maximal 5 Minute\n"
                            + "LegendI[diskio]:  read:\n"
                            + "LegendO[diskio]:  write:\n"
                            + "Timezone[diskio]: ").print(thisAOServer.getTimeZone()).print("\n"
                            + "Title[diskio]: Server Disk I/O (blocks per second)\n"
                            + "PageFoot[diskio]: <p>\n"
                            + "PageTop[diskio]: <H2>Server Disk I/O (blocks per second)</H2>\n"
                            + "XSize[diskio]: ").print(GRAPH_WIDTH).print("\n"
                            + "YSize[diskio]: ").print(GRAPH_HEIGHT).print("\n");
                    for(int c=0;c<dfDevices.size();c++) {
                        String device = dfDevices.get(c);
                        String safeName = dfSafeNames.get(c);
                        out.print("\n"
                                + "Target[").print(safeName).print("]: `").print(daemonBin).print("/mrtg_df ").print(device).print("`\n"
                                + "Options[").print(safeName).print("]: gauge, noinfo, growright, transparent\n"
                                + "MaxBytes[").print(safeName).print("]: 100\n"
                                + "YLegend[").print(safeName).print("]: % Used space and inodes\n"
                                + "ShortLegend[").print(safeName).print("]: %\n"
                                + "Legend1[").print(safeName).print("]: % space used\n"
                                + "Legend2[").print(safeName).print("]: % inodes used\n"
                                + "Legend3[").print(safeName).print("]: Maximal 5 Minute\n"
                                + "Legend4[").print(safeName).print("]: Maximal 5 Minute\n"
                                + "LegendI[").print(safeName).print("]:  Space:\n"
                                + "LegendO[").print(safeName).print("]:  Inodes:\n"
                                + "Timezone[").print(safeName).print("]: ").print(thisAOServer.getTimeZone()).print("\n"
                                + "Title[").print(safeName).print("]: ").print(device).print(" Space and Inodes (%)\n"
                                + "PageFoot[").print(safeName).print("]: <p>\n"
                                + "PageTop[").print(safeName).print("]: <H2>").print(device).print(" Space and Inodes (%)</H2>\n"
                                + "XSize[").print(safeName).print("]: ").print(GRAPH_WIDTH).print("\n"
                                + "YSize[").print(safeName).print("]: ").print(GRAPH_HEIGHT).print("\n");
                    }
                    out.print("\n"
                            + "Target[swap]: `").print(daemonBin).print("/mrtg_swap`\n"
                            + "Options[swap]: gauge, noinfo, growright, transparent, nopercent\n"
                            + "MaxBytes[swap]: 100000000\n"
                            + "YLegend[swap]: In+Out blocks per second\n"
                            + "ShortLegend[swap]: io blk/s\n"
                            + "Legend1[swap]: swap\n"
                            + "Legend2[swap]: page\n"
                            + "Legend3[swap]: Maximal 5 Minute\n"
                            + "Legend4[swap]: Maximal 5 Minute\n"
                            + "LegendI[swap]:  swap:\n"
                            + "LegendO[swap]:  page:\n"
                            + "Timezone[swap]: ").print(thisAOServer.getTimeZone()).print("\n"
                            + "Title[swap]: Server Swap and Paging I/O (in+out blocks per second)\n"
                            + "PageFoot[swap]: <p>\n"
                            + "PageTop[swap]: <H2>Server Swap and Paging I/O (in+out blocks per second)</H2>\n"
                            + "XSize[swap]: ").print(GRAPH_WIDTH).print("\n"
                            + "YSize[swap]: ").print(GRAPH_HEIGHT).print("\n");
                    out.flush();
                } finally {
                    out.close();
                }
                byte[] newFile=bout.toByteArray();
                if(!cfgFile.getStat(tempStat).exists() || !cfgFile.contentEquals(newFile)) {
                    OutputStream fileOut=cfgFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        fileOut.write(newFile);
                        fileOut.flush();
                    } finally {
                        fileOut.close();
                    }
                }
            }

            /*
             * Rewrite stats.html
             */
            {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ChainWriter out=new ChainWriter(bout);
                try {
                    out.print("<!--\n"
                            + "  Automatically generated by ").print(MrtgManager.class.getName()).print("\n"
                            + "-->\n"
                            + "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2//EN\">\n"
                            + "<HTML>\n"
                            + "  <HEAD>\n"
                            + "    <TITLE>Stats Overview</TITLE>\n"
                            + "    <META HTTP-EQUIV=\"Refresh\" CONTENT=\"300\">\n"
                            + "    <META HTTP-EQUIV=\"Pragma\" CONTENT=\"no-cache\">\n"
                            + "    <META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=iso-8859-1\">\n"
                            + "  </HEAD>\n"
                            + "\n"
                            + "  <BODY BGCOLOR=\"#ffffff\">\n"
                            + "    <font face=\"Verdana, Arial, Helvetica, sans-serif\">\n"
                            + "      <center>\n"
                            + "        <h1>\n"
                            + "          <img src=\"https://www.aoindustries.com/images/clientarea/accounting/SendInvoices.jpg\"><br>\n"
                            + "	  <font color=\"000000\">").print(thisAOServer.getHostname());
                    if(failoverServer!=null) out.print(" on ").print(failoverServer.getHostname());
                    out.print("</font>\n"
                            + "        </h1>\n"
                            + "        <hr>\n"
                            + "\n"
                            + "        <font size=\"1\">\n"
                            + "        | <a href=\"../../MRTG.ao\" target=\"_parent\">Servers</a> |\n"
                            + "        <a href=\"stats.html\">Stats Overview</a> |\n"
                            + "        <a href=\"load.html\">Load</a> |\n"
                            + "        <a href=\"cpu.html\">CPU</a> |\n"
                            + "        <a href=\"diskio.html\">DiskIO</a> |\n");
                    for(int c=0;c<dfDevices.size();c++) {
                        out.print("        <a href=\"").print(dfSafeNames.get(c)).print(".html\">").print(dfDevices.get(c)).print("</a> |\n");
                    }
                    out.print("        <a href=\"mem.html\"> Memory</a> |\n");
                    // Add the network devices
                    List<NetDevice> netDevices=thisServer.getNetDevices();
                    for(NetDevice netDevice : netDevices) {
                        out.print("        <a href=\"").print(netDevice.getNetDeviceID().getName()).print(".html\"> ").print(netDevice.getDescription()).print("</a> |\n");
                    }
                    out.print("        <a href=\"swap.html\">Swap</a> |\n"
                            + "      </font></center>\n"
                            + "\n"
                            + "      <hr>\n"
                            + "      <H2>Load Average (times 1000)</H2><BR>\n"
                            + "      <A href=\"load.html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"load-day.png\" ALT=\"load\"></A>\n"
                            + "      <hr>\n"
                            + "      <H2>Server CPU Utilization (%)</H2><BR>\n"
                            + "      <A href=\"cpu.html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"cpu-day.png\" ALT=\"cpu\"></A>\n"
                            + "      <hr>\n"
                            + "      <H2>Server Disk I/O (blocks per second)</H2><BR>\n"
                            + "\n"
                            + "      <A href=\"diskio.html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"diskio-day.png\" ALT=\"diskio\"></A>\n");
                    for(int c=0;c<dfDevices.size();c++) {
                        out.print("      <hr>\n"
                                + "      <H2>").print(dfDevices.get(c)).print(" Space and Inodes (%)</H2><BR>\n"
                                + "      <A href=\"").print(dfSafeNames.get(c)).print(".html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"").print(dfSafeNames.get(c)).print("-day.png\" ALT=\"").print(dfDevices.get(c)).print("\"></A>\n");
                    }
                    out.print("      <hr>\n"
                            + "      <H2>Server Memory and Swap space (%)</H2><BR>\n"
                            + "      <A href=\"mem.html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"mem-day.png\" ALT=\"mem\"></A>\n");
                    for(NetDevice netDevice : netDevices) {
                        String deviceId=netDevice.getNetDeviceID().getName();
                        out.print("      <hr>\n"
                                + "      <H2>").print(netDevice.getDescription()).print(" traffic</H2><BR>\n"
                                + "      <A href=\"").print(deviceId).print(".html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"").print(deviceId).print("-day.png\" ALT=\"").print(deviceId).print("\"></A>\n");
                    }
                    out.print("      <hr>\n"
                            + "      <H2>Server Swap and Paging I/O (in+out blocks per second)</H2><BR>\n"
                            + "      <A href=\"swap.html\"><IMG BORDER=0 VSPACE=10 WIDTH=700 HEIGHT=185 ALIGN=TOP SRC=\"swap-day.png\" ALT=\"swap\"></A>\n"
                            + "<!-- Begin MRTG Block -->\n"
                            + "<BR><HR><BR>\n");
                    if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("<TABLE BORDER=0 CELLSPACING=0 CELLPADDING=0>\n"
                                + "  <TR>\n"
                                + "    <TD WIDTH=63><A\n"
                                + "    HREF=\"http://people.ee.ethz.ch/~oetiker/webtools/mrtg/\"><IMG\n"
                                + "    BORDER=0 SRC=\"mrtg-l.png\" WIDTH=63 HEIGHT=25 ALT=\"MRTG\"></A></TD>\n"
                                + "    <TD WIDTH=25><A\n"
                                + "    HREF=\"http://people.ee.ethz.ch/~oetiker/webtools/mrtg/\"><IMG\n"
                                + "    BORDER=0 SRC=\"mrtg-m.png\" WIDTH=25 HEIGHT=25 ALT=\"\"></A></TD>\n"
                                + "    <TD WIDTH=388><A\n"
                                + "    HREF=\"http://people.ee.ethz.ch/~oetiker/webtools/mrtg/\"><IMG\n"
                                + "    BORDER=0 SRC=\"mrtg-r.png\" WIDTH=388 HEIGHT=25\n"
                                + "    ALT=\"Multi Router Traffic Grapher\"></A></TD>\n"
                                + "  </TR>\n"
                                + "\n"
                                + "</TABLE>\n"
                                + "<TABLE BORDER=0 CELLSPACING=0 CELLPADDING=0>\n"
                                + "  <TR VALIGN=top>\n"
                                + "  <TD WIDTH=88 ALIGN=RIGHT><FONT FACE=\"Arial,Helvetica\" SIZE=2>2.10.15</FONT></TD>\n"
                                + "  <TD WIDTH=388 ALIGN=RIGHT><FONT FACE=\"Arial,Helvetica\" SIZE=2>\n"
                                + "  <A HREF=\"http://people.ee.ethz.ch/~oetiker/\">Tobias Oetiker</A>\n"
                                + "  <A HREF=\"mailto:oetiker@ee.ethz.ch\">&lt;oetiker@ee.ethz.ch&gt;</A> \n"
                                + "and&nbsp;<A HREF=\"http://www.bungi.com/\">Dave&nbsp;Rand</A>&nbsp;<A HREF=\"mailto:dlr@bungi.com\">&lt;dlr@bungi.com&gt;</A></FONT>  </TD>\n"
                                + "\n"
                                + "</TR>\n"
                                + "</TABLE>\n");
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("<TABLE BORDER=\"0\" CELLSPACING=\"0\" CELLPADDING=\"0\" summary=\"\">\n"
                                + "  <TR>\n"
                                + "    <TD WIDTH=\"63\"><A\n"
                                + "    HREF=\"mrtg.html\"><IMG\n"
                                + "    ALT=\"\" BORDER=\"0\" SRC=\"mrtg-l.png\" width=\"63\" height=\"25\"></A></TD>\n"
                                + "    <TD WIDTH=\"25\"><A\n"
                                + "    HREF=\"mrtg.html\"><IMG\n"
                                + "    ALT=\"MRTG\" BORDER=\"0\" SRC=\"mrtg-m.png\" width=\"25\" height=\"25\"></A></TD>\n"
                                + "    <TD WIDTH=\"388\"><A\n"
                                + "    HREF=\"mrtg.html\"><IMG\n"
                                + "    ALT=\"\" BORDER=\"0\" SRC=\"mrtg-r.png\" width=\"388\" height=\"25\"></A></TD>\n"
                                + "  </TR>\n"
                                + "</TABLE>\n"
                                + "<img src=\"https://www.aoindustries.com/layout/ao/blank.gif\" alt=\"\" width=\"1\" height=\"4\" align=\"bottom\" border=\"0\">\n"
                                + "<TABLE BORDER=\"0\" CELLSPACING=\"0\" CELLPADDING=\"0\" summary=\"\">\n"
                                + "  <TR VALIGN=\"top\">\n"
                                + "  <TD><FONT FACE=\"Arial,Helvetica\" SIZE=\"2\">\n"
                                + "  <A HREF=\"http://people.ee.ethz.ch/~oetiker\">Tobias Oetiker</A>\n"
                                + "  <A HREF=\"mailto:oetiker@ee.ethz.ch\">&lt;oetiker@ee.ethz.ch&gt;</A>\n"
                                + "  and&nbsp;<A HREF=\"http://www.bungi.com\">Dave&nbsp;Rand</A>&nbsp;<A HREF=\"mailto:dlr@bungi.com\">&lt;dlr@bungi.com&gt;</A></FONT>\n"
                                + "  </TD>\n"
                                + "</TR>\n"
                                + "</TABLE>\n");
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    out.print("<!-- End MRTG Block -->\n"
                            + "    <p>\n"
                            + "  </BODY>\n"
                            + "</HTML>\n");
                    out.flush();
                } finally {
                    out.close();
                }
                byte[] newFile=bout.toByteArray();
                if(!statsFile.getStat(tempStat).exists() || !statsFile.contentEquals(newFile)) {
                    OutputStream fileOut=statsFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        fileOut.write(newFile);
                        fileOut.flush();
                    } finally {
                        fileOut.close();
                    }
                }
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MrtgManager.class)
                && mrtgManager==null
            ) {
                System.out.print("Starting MrtgManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                mrtgManager=new MrtgManager();
                conn.aoServers.addTableListener(mrtgManager, 0);
                conn.netDevices.addTableListener(mrtgManager, 0);
                conn.netDeviceIDs.addTableListener(mrtgManager, 0);
                conn.servers.addTableListener(mrtgManager, 0);
                conn.timeZones.addTableListener(mrtgManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild mrtg.cfg";
    }
    
    /**
     * Reads /proc/cpuinfo and determines the number of CPUs.
     */
    public static int getNumberOfCPUs() throws IOException {
        BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/cpuinfo")));
        try {
            int count=0;
            String line;
            while((line=in.readLine())!=null) {
                if(line.startsWith("processor\t: ")) count++;
            }
            return count;
        } finally {
            in.close();
        }
    }
    
    /**
     * Gets the list of devices for df commands.  When in a failover state, returns empty list.
     */
    public static List<String> getDFDevices() throws IOException, SQLException {
        AOServer thisAOServer = AOServDaemon.getThisAOServer();
        if(thisAOServer.getFailoverServer()!=null) return Collections.emptyList();
        int osv = thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        List<String> devices = new ArrayList<String>();
        Process P = Runtime.getRuntime().exec(
            new String[] {
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                ? "/opt/aoserv-daemon/bin/list_partitions"
                : "/usr/aoserv/daemon/bin/list_partitions"
            }
        );
        try {
            P.getOutputStream().close();
            BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
            try {
                String line;
                while((line=in.readLine())!=null) {
                    if(devices.contains(line)) {
                        AOServDaemon.reportWarning(new Throwable("Warning: duplicate device from list_partitions: "+line), null);
                    } else {
                        devices.add(line);
                    }
                }
            } finally {
                in.close();
            }
        } finally {
            try {
                int retCode = P.waitFor();
                if(retCode!=0) throw new IOException("Non-zero return value from list_partitions: "+retCode);
            } catch(InterruptedException err) {
                AOServDaemon.reportWarning(err, null);
            }
        }

        Collections.sort(devices);
        return devices;
    }
    
    public static List<String> getSafeNames(List<String> devices) throws IOException {
        if(devices.isEmpty()) return Collections.emptyList();
        List<String> safeNames = new ArrayList<String>(devices.size());
        for(String device : devices) {
            String safeName;
            if(device.equals("/var/lib/pgsql.aes256.img")) {
                safeName = "pgsqlaes256";
            } else if(device.equals("/www.aes256.img")) {
                safeName = "wwwaes256";
            } else if(device.equals("/ao.aes256.img")) {
                safeName = "aoaes256";
            } else if(device.equals("/ao.copy.aes256.img")) {
                safeName = "aocopyaes256";
            } else {
                if(device.startsWith("/dev/")) device=device.substring(5);
                // All characters should now be a-z, A-Z, and 0-9
                if(device.length()==0) throw new IOException("Empty device name: "+device);
                for(int c=0;c<device.length();c++) {
                    char ch=device.charAt(c);
                    if(
                        (ch<'a' || ch>'z')
                        && (ch<'A' || ch>'Z')
                        && (ch<'0' || ch>'9')
                    ) throw new IOException("Invalid character in device.  ch="+ch+", device="+device);
                }
                safeName = device;
            }
            safeNames.add(safeName);
        }
        return safeNames;
    }

    public static final File mandrivaMrtgDirectory=new File("/var/www/html/mrtg");
    public static final File redhatMrtgDirectory=new File("/var/www/mrtg");

    public static void getMrtgFile(String filename, CompressedDataOutputStream out) throws IOException, SQLException {
        // Currently only Mandrake 10.1 supported
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        File mrtgDirectory;
        if(
            osv==OperatingSystemVersion.MANDRAKE_10_1_I586
            || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
        ) {
            mrtgDirectory = mandrivaMrtgDirectory;
        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
            mrtgDirectory = redhatMrtgDirectory;
        } else throw new SQLException("Unsupport OperatingSystemVersion: "+osv);

        File file=new File(mrtgDirectory, filename);
        FileInputStream in=new FileInputStream(file);
        try {
            byte[] buff=BufferManager.getBytes();
            try {
                int ret;
                while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                    out.write(AOServDaemonProtocol.NEXT);
                    out.writeShort(ret);
                    out.write(buff, 0, ret);
                }
            } finally {
                BufferManager.release(buff);
            }
        } finally {
            in.close();
        }
    }
}
