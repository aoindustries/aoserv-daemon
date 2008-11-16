package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.io.unix.UnixFile;

/**
 * Manages HttpdTomcatSharedSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdTomcatSharedSiteManager_6_0_X extends HttpdTomcatSharedSiteManager {

    public HttpdTomcatSharedSiteManager_6_0_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    protected void configureSiteDirectory(UnixFile wwwDirectory) {
        throw new RuntimeException("TODO");
    }
}