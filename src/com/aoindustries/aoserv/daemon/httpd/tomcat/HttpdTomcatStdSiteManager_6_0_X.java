package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatStdSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_6_0_X extends HttpdTomcatStdSiteManager<TomcatCommon_6_0_X> {

    HttpdTomcatStdSiteManager_6_0_X(HttpdTomcatStdSite tomcatStdSite) throws SQLException, IOException {
        super(tomcatStdSite);
    }

    /**
     * Builds a standard install for Tomcat 6.0.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // Resolve and allocate stuff used throughout the method
        final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
        final TomcatCommon tomcatCommon = getTomcatCommon();
        final String siteDir = siteDirectory.getPath();
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final int uid = lsa.getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();
        final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
        final AOServer thisAOServer = AOServDaemon.getThisAOServer();
        final Server server = thisAOServer.getServer();
        final int osv = server.getOperatingSystemVersion().getPkey();
        final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
        final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        FileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/conf/Catalina", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
        if (!httpdSite.isDisabled()) FileUtils.ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
        FileUtils.mkdir(siteDir+"/temp", 0770, uid, gid);
        FileUtils.ln("var/log", siteDir+"/logs", uid, gid);
        FileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/run", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/work", 0750, uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);

        /*
         * Set up the bash profile source
         */
        String profileFile=siteDir+"/bin/profile";
        LinuxAccountManager.setBashProfile(lsa, profileFile);
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(profileFile).getSecureOutputStream(
                    uid,
                    gid,
                    0750,
                    false
                )
            )
        );
        try {
            out.print("#!/bin/sh\n"
                    + "\n"
                    + ". /etc/profile\n"
                    + ". ").print(osConfig.getScriptInclude("jdk"+tomcatCommon.getDefaultJdkVersion()+".sh")).print("\n"
                    + ". ").print(osConfig.getScriptInclude("php-"+httpdConfig.getDefaultPhpVersion()+".sh")).print('\n');
            if(postgresServerMinorVersion!=null) out.print(". ").print(osConfig.getScriptInclude("postgresql-"+postgresServerMinorVersion+".sh")).print('\n');
            out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
                    + "\n"
                    + "umask 002\n"
                    + "export DISPLAY=:0.0\n"
                    + "\n"
                    + "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
                    + "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
                    + "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
                    + "\n"
                    + "export PATH=\"${PATH}:").print(siteDir).print("/bin\"\n"
                    + "\n"
                    + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M'\n");
        } finally {
            out.close();
        }

        /*
         * Write the bin/tomcat script.
         */
        String tomcatScript=siteDir+"/bin/tomcat";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(tomcatScript).getSecureOutputStream(
                    uid,
                    gid,
                    0700,
                    false
                )
            )
        );
        try {
            out.print("#!/bin/sh\n"
                    + "\n"
                    + "TOMCAT_HOME=\"").print(siteDir).print("\"\n"
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
                    + "        cd \"$TOMCAT_HOME\"\n"
                    + "        . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    + "        \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
                    + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
                    + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "    fi\n"
                    + "elif [ \"$1\" = \"daemon\" ]; then\n"
                    + "    cd \"$TOMCAT_HOME\"\n"
                    + "    . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "\n"
                    + "    while [ 1 ]; do\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    + "        mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err.old\"\n"
                    + "        \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
                    + "        echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "        wait\n"
                    + "        RETCODE=\"$?\"\n"
                    + "        echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
                    + "        sleep 5\n"
                    + "    done\n"
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
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

        out=new ChainWriter(new UnixFile(siteDir+"/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true));
        try {
            out.print("#!/bin/sh\n"
                    + "exec \"").print(siteDir).print("/bin/tomcat\" stop\n");
        } finally {
            out.close();
        }

        out=new ChainWriter(new UnixFile(siteDir+"/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true));
        try {
            out.print("#!/bin/sh\n"
                    + "exec \"").print(siteDir).print("/bin/tomcat\" start\n");
        } finally {
            out.close();
        }

        FileUtils.ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", siteDir+"/bin/tomcat-juli.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/version.sh", siteDir+"/bin/version.sh", uid, gid);

        // Create the lib directory and all contents
        FileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
        FileUtils.lnAll("../../.."+tomcatDirectory+"/lib/", siteDir+"/lib/", uid, gid);

        if(postgresServerMinorVersion!=null) {
            String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
            if(postgresPath!=null) FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", siteDir+"/lib/postgresql.jar", uid, gid);
        }
        String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
        if(mysqlConnectorPath!=null) {
            String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
            FileUtils.ln("../../.."+mysqlConnectorPath, siteDir+"/lib/"+filename, uid, gid);
        }

        /*
         * Write the conf/catalina.policy file
         */
        {
            UnixFile cp=new UnixFile(siteDir+"/conf/catalina.policy");
            new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
            cp.chown(uid, gid).setMode(0660);
        }

        {
            UnixFile cp=new UnixFile(siteDir+"/conf/catalina.properties");
            new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
            cp.chown(uid, gid).setMode(0660);
        }

        {
            UnixFile cp=new UnixFile(siteDir+"/conf/context.xml");
            new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
            cp.chown(uid, gid).setMode(0660);
        }

        {
            UnixFile cp=new UnixFile(siteDir+"/conf/logging.properties");
            new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
            cp.chown(uid, gid).setMode(0660);
        }
        {
            UnixFile tu=new UnixFile(siteDir+"/conf/tomcat-users.xml");
            new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tu, false);
            tu.chown(uid, gid).setMode(0660);
        }
        {
            UnixFile wx=new UnixFile(siteDir+"/conf/web.xml");
            new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(wx, false);
            wx.chown(uid, gid).setMode(0660);
        }

        /*
         * Write the ROOT/WEB-INF/web.xml file.
         */
        String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false)
            )
        );
        try {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                    + "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\"\n"
                    + "   xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                    + "   xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd\"\n"
                    + "   version=\"2.5\">\n"
                    + "  <display-name>Welcome to Tomcat</display-name>\n"
                    + "  <description>\n"
                    + "     Welcome to Tomcat\n"
                    + "  </description>\n"
                    + "</web-app>\n");
        } finally {
            out.close();
        }
    }

    public TomcatCommon_6_0_X getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

    protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
        final TomcatCommon tomcatCommon = getTomcatCommon();
        AOServConnector conn = AOServDaemon.getConnector();

        // Build to RAM first
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ChainWriter out = new ChainWriter(bout);
        try {
            List<HttpdWorker> hws=tomcatSite.getHttpdWorkers();
            if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+httpdSite.getPkey()+", found "+hws.size());
            HttpdWorker hw=hws.get(0);
            String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
            if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                throw new SQLException("HttpdWorker #"+hw.getPkey()+" for HttpdTomcatStdSite #"+httpdSite.getPkey()+" must be AJP13 but it is "+hwProtocol);
            }
            if(!httpdSite.isManual()) out.print(autoWarning);
            NetBind shutdownPort=tomcatStdSite.getTomcat4ShutdownPort();
            if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite="+tomcatStdSite);
            String shutdownKey=tomcatStdSite.getTomcat4ShutdownKey();
            if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite="+tomcatStdSite);
            out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\">\n"
                    + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
                    + "  <Listener className=\"org.apache.catalina.core.JasperListener\" />\n"
                    + "  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" />\n"
                    + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
                    + "  <GlobalNamingResources>\n"
                    + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                    + "              type=\"org.apache.catalina.UserDatabase\"\n"
                    + "              description=\"User database that can be updated and saved\"\n"
                    + "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                    + "              pathname=\"conf/tomcat-users.xml\" />\n"
                    + "  </GlobalNamingResources>\n"
                    + "  <Service name=\"Catalina\">\n"
                    + "    <Connector\n"
                    + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                    + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                    + "      protocol=\"AJP/1.3\"\n"
                    + "      redirectPort=\"8443\"\n"
                    + "    />\n"
                    + "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
                    + "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                    + "             resourceName=\"UserDatabase\" />\"\n"
                    + "      <Host\n"
                    + "        name=\"localhost\"\n"
                    + "        appBase=\"webapps\"\n"
                    + "        unpackWARs=\"true\"\n"
                    + "        autoDeploy=\"true\"\n"
                    + "        xmlValidation=\"false\"\n"
                    + "        xmlNamespaceAware=\"false\"\n"
                    + "      >\n");
            for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                out.print("        <Context\n");
                if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                        + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                        + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                        + "          override=\"").print(htc.allowOverride()).print("\"\n"
                        + "          path=\"").print(htc.getPath()).print("\"\n"
                        + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                        + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                        + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                if(parameters.isEmpty() && dataSources.isEmpty()) {
                    out.print("        />\n");
                } else {
                    out.print("        >\n");
                    // Parameters
                    for(HttpdTomcatParameter parameter : parameters) {
                        tomcatCommon.writeHttpdTomcatParameter(parameter, out);
                    }
                    // Data Sources
                    for(HttpdTomcatDataSource dataSource : dataSources) {
                        tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
                    }
                    out.print("        </Context>\n");
                }
            }
            out.print("      </Host>\n"
                    + "    </Engine>\n"
                    + "  </Service>\n"
                    + "</Server>\n");
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}