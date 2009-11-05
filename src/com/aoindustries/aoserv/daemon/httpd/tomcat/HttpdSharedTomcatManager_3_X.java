package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdSharedTomcat version 3.X configurations.
 * 
 * TODO: Replace all uses of "replace" with a read file then call replace only if one of the "from" values is found.  Should be faster
 *       be eliminating unnecessary subprocesses.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdSharedTomcatManager_3_X<TC extends TomcatCommon_3_X> extends HttpdSharedTomcatManager<TC> {

    HttpdSharedTomcatManager_3_X(HttpdSharedTomcat sharedTomcat) {
        super(sharedTomcat);
    }

    void buildSharedTomcatDirectory(UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        /*
         * Get values used in the rest of the loop.
         */
        final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
        final Stat tempStat = new Stat();
        final AOServer aoServer = AOServDaemon.getThisAOServer();
        final HttpdTomcatVersion htv=sharedTomcat.getHttpdTomcatVersion();
        final String tomcatDirectory=htv.getInstallDirectory();
        final TC tomcatCommon = getTomcatCommon();
        final LinuxServerAccount lsa = sharedTomcat.getLinuxServerAccount();
        final int lsaUID = lsa.getUID().getID();
        final LinuxServerGroup lsg = sharedTomcat.getLinuxServerGroup();
        final int lsgGID = lsg.getGID().getID();
        final String wwwGroupDir = sharedTomcatDirectory.getPath();
        final String wwwDirectory = httpdConfig.getHttpdSitesDirectory();
        final UnixFile daemonUF = new UnixFile(sharedTomcatDirectory, "daemon", false);
        // Create and fill in the directory if it does not exist or is owned by root.
        final UnixFile workUF = new UnixFile(sharedTomcatDirectory, "work", false);

        boolean needRestart=false;
        if (!sharedTomcatDirectory.getStat(tempStat).exists() || sharedTomcatDirectory.getStat(tempStat).getUID() == UnixFile.ROOT_GID) {

            // Create the /wwwgroup/name/...

            // 001
            if (!sharedTomcatDirectory.getStat(tempStat).exists()) sharedTomcatDirectory.mkdir();
            sharedTomcatDirectory.setMode(0770);
            new UnixFile(sharedTomcatDirectory, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(sharedTomcatDirectory, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
            UnixFile varUF = new UnixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

            workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);

            PostgresServer postgresServer=aoServer.getPreferredPostgresServer();
            String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            String profileFile = wwwGroupDir + "/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);

            UnixFile profileUF = new UnixFile(profileFile);
            ChainWriter out = new ChainWriter(
                new BufferedOutputStream(
                    profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false)
                )
            );
            try {
                out.print("#!/bin/sh\n"
                          + "\n");
                out.print(". /etc/profile\n"
                        + ". ").print(osConfig.getScriptInclude("jdk"+tomcatCommon.getDefaultJdkVersion()+".sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jakarta-oro-2.0.1.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jakarta-regexp-1.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jakarta-servletapi-3.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jakarta-tomcat-3.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jetspeed-1.1.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("cocoon-1.8.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("xerces-1.2.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("ant-1.6.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("xalan-1.2.d02.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("php-"+httpdConfig.getDefaultPhpVersion()+".sh")).print('\n');
                if(postgresServerMinorVersion!=null) out.print(". ").print(osConfig.getScriptInclude("postgresql-"+postgresServerMinorVersion+".sh")).print('\n');
                out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
                        + ". ").print(osConfig.getScriptInclude("castor-0.8.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("cos-27May2002.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("ecs-1.3.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("freemarker-1.5.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("gnu.regexp-1.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jaf-1.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("slide-1.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("kavachart-3.1.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("javamail-1.1.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jdbc-2.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jsse-1.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("jyve-20000907.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("mm.mysql-2.0.7.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("openxml-1.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("pop3-1.1.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("soap-2.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("spfc-0.2.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("turbine-20000907.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("village-1.3.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("webmacro-27-08-2000.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("xang-0.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("xmlrpc-1.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("interclient-2.0.sh")).print("\n"
                        + ". ").print(osConfig.getScriptInclude("poolman-1.4.sh")).print("\n"
                        + "export \"CLASSPATH=/usr/aoserv/lib-1.3/aocode-public.jar:$CLASSPATH\"\n"
                        //+ ". ").print(osConfig.getScriptInclude("fop-0.15.sh")).print('\n'
                        + "\n"
                        + "export PATH=\"${PATH}:").print(wwwGroupDir).print("/bin\"\n"
                        + "\n"
                        + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M'\n"
                        + "\n");
                out.print("# Add site group classes\n"
                        + "CLASSPATH=\"${CLASSPATH}:").print(wwwGroupDir).print("/classes\"\n"
                        + "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
                        + "    if [ -f \"$i\" ]; then\n"
                        + "        CLASSPATH=\"${CLASSPATH}:$i\"\n"
                        + "    fi\n"
                        + "done\n"
                        + "\n");
                out.print(". ").print(wwwGroupDir).print("/bin/profile.sites\n"
                        + "\n"
                        + "for SITE in $SITES\n"
                        + "do\n"
                        + "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n");
                out.print("    CLASSPATH=\"${CLASSPATH}:").print(wwwDirectory).print("/${SITE}/classes\"\n"
                        + "\n"
                        + "    for i in ").print(wwwDirectory).print("/${SITE}/lib/* ; do\n"
                        + "        if [ -f \"$i\" ]; then\n"
                        + "            CLASSPATH=\"${CLASSPATH}:$i\"\n"
                        + "        fi\n"
                        + "    done\n");
                out.print("done\n"
                        + "export CLASSPATH\n");
            } finally {
                out.close();
            }

            // 004

            UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
            out=new ChainWriter(
                new BufferedOutputStream(
                    tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false)
                )
            );
            try {
            out.print("#!/bin/sh\n"
                    + "\n"
                    + "TOMCAT_HOME=\"").print(wwwGroupDir).print("\"\n"
                    + "\n"
                    + "if [ \"$1\" = \"start\" ]; then\n"
                    + "    \"$0\" stop\n"
                    + "    \"$0\" daemon &\n"
                    + "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                    + "elif [ \"$1\" = \"stop\" ]; then\n"
                    + "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
                    + "        kill `cat \"${TOMCAT_HOME}/var/run/tomcat.pid\"`\n"
                    + "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                    + "    fi\n"
                    + "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
                    + "        . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    //+ "        ulimit -S -m 196608 -v 400000\n"
                    //+ "        ulimit -H -m 196608 -v 400000\n"
                    + "        if [ \"$SITES\" != \"\" ]; then\n"
                    + "            cd \"$TOMCAT_HOME\"\n"
            );
            out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat stop $SITES &>/dev/null\n");
            out.print("        fi\n"
                    + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
                    + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "    fi\n"
                    + "elif [ \"$1\" = \"daemon\" ]; then\n"
                    + "    cd \"$TOMCAT_HOME\"\n"
                    + "    . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "\n"
                    + "    if [ \"$SITES\" != \"\" ]; then\n"
                    + "        while [ 1 ]; do\n"
                    //+ "            ulimit -S -m 196608 -v 400000\n"
                    //+ "            ulimit -H -m 196608 -v 400000\n"
                    + "            umask 002\n"
                    + "            export DISPLAY=:0.0\n"
            );
            out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat start $SITES &>var/log/servlet_err &\n");
            out.print("            echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "            wait\n"
                    + "            RETCODE=\"$?\"\n"
                    + "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
                    + "            sleep 5\n"
                    + "        done\n"
                    + "    fi\n"
                    + "    rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                    + "else\n"
                    + "    echo \"Usage:\"\n"
                    + "    echo \"tomcat {start|stop}\"\n"
                    + "    echo \"        start - start tomcat\"\n"
                    + "    echo \"        stop  - stop tomcat\"\n"
                    + "fi\n"
            );
            } finally {
                out.close();
            }

            // The classes directory
            new UnixFile(sharedTomcatDirectory, "classes", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);

            // Create /lib
            new UnixFile(sharedTomcatDirectory, "lib", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
            FileUtils.lnAll("../../.."+tomcatDirectory+"/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);
            FileUtils.ln("../../.."+tomcatDirectory+"/lib/jasper-runtime.jar", wwwGroupDir+"/lib/jasper-runtime.jar", lsaUID, lsgGID);
            if(postgresServerMinorVersion!=null) {
                String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
                if(postgresPath!=null) FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
            }
            String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
            if(mysqlConnectorPath!=null) {
                String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
                FileUtils.ln("../../.."+mysqlConnectorPath, wwwGroupDir+"/lib/"+filename, lsaUID, lsgGID);
            }
            UnixFile servErrUF = new UnixFile(varUF, "log/servlet_err", false);
            servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false).close();

            // Set the ownership to avoid future rebuilds of this directory
            sharedTomcatDirectory.chown(lsaUID, lsgGID);

            needRestart=true;
        }

        // always rebuild profile.sites file
        UnixFile newSitesFileUF = new UnixFile(sharedTomcatDirectory, "bin/profile.sites.new", false);
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true)
            )
        );
        List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
        try {
            out.print("export SITES=\"");
            boolean didOne=false;
            for(int j = 0; j< sites.size(); j++) {
                HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
                if(!hs.isDisabled()) {
                    if (didOne) out.print(' ');
                    else didOne=true;
                    out.print(hs.getSiteName());
                }
            }
            out.print("\"\n");
        } finally {
            out.close();
        }
        // flag as needing a restart if this file is different than any existing
        UnixFile sitesFile = new UnixFile(sharedTomcatDirectory, "bin/profile.sites", false);
        if(!sitesFile.getStat(tempStat).exists() || !newSitesFileUF.contentEquals(sitesFile)) {
            needRestart=true;
            UnixFile backupFile=new UnixFile(sharedTomcatDirectory, "bin/profile.sites.old", false);
            if(sitesFile.getStat(tempStat).exists()) sitesFile.renameTo(backupFile);
            newSitesFileUF.renameTo(sitesFile);
        } else newSitesFileUF.delete();

        // make work directories and remove extra work dirs
        List<String> workFiles = new SortedArrayList<String>();
        String[] wlist = workUF.getFile().list();
        if(wlist!=null) {
            for (int j = 0; j<wlist.length; j++) {
                workFiles.add(wlist[j]);
            }
        }
        for (int j = 0; j< sites.size(); j++) {
            HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
            if(!hs.isDisabled()) {
                String subwork = hs.getSiteName();
                workFiles.remove(subwork);
                UnixFile workDir = new UnixFile(workUF, subwork, false);
                if (!workDir.getStat(tempStat).exists()) {
                    workDir
                        .mkdir()
                        .chown(
                            lsaUID, 
                            sites.get(j)
                                .getHttpdTomcatSite()
                                .getHttpdSite()
                                .getLinuxServerGroup()
                                .getGID()
                                .getID()
                        )
                        .setMode(0750)
                    ;
                }
            }
        }
        for (int c = 0; c < workFiles.size(); c++) {
            deleteFileList.add(new File(workUF.getFile(), workFiles.get(c)));
        }
        
        // Enable/Disable
        UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
        if(!sharedTomcat.isDisabled()) {
            // Enabled
            if(!daemonSymlink.getStat(tempStat).exists()) {
                daemonSymlink.symLink("../bin/tomcat").chown(
                    lsaUID,
                    lsgGID
                );
            }
        } else {
            // Disabled
            if(daemonSymlink.getStat(tempStat).exists()) daemonSymlink.delete();
        }

        // Start if needed
        if(needRestart && !sharedTomcat.isDisabled()) sharedTomcatsNeedingRestarted.add(sharedTomcat);
    }

    protected boolean upgradeSharedTomcatDirectory(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}
