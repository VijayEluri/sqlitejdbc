/*
 * Copyright (c) 2007 David Crawshaw <david@zentus.com>
 * 
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package org.sqlite;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class JDBC implements Driver
{
    private static final String PREFIX = "jdbc:sqlite:";

    static {
        try {
            DriverManager.registerDriver(new JDBC());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getMajorVersion() { return 1; }
    public int getMinorVersion() { return 1; }

    public boolean jdbcCompliant() { return false; }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Util.unsupported();
    }

    public boolean acceptsURL(String url) {
        return url != null && url.toLowerCase().startsWith(PREFIX);
    }

    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
            throws SQLException {
        DriverPropertyInfo sharedCache = new DriverPropertyInfo(
            "shared_cache", "false");
        sharedCache.choices = new String[] { "true", "false" };
        sharedCache.description =
            "Enable SQLite Shared-Cache mode, native driver only.";
        sharedCache.required = false;

        DriverPropertyInfo julianDay = new DriverPropertyInfo(
            "julian_day", "false");
        julianDay.choices = new String[] { "true", "false" };
        julianDay.description =
            "Store Dates/Times as julian day numbers.";
        julianDay.required = false;

        return new DriverPropertyInfo[] { sharedCache };
    }

    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        url = url.trim();

        // if no file name is given use a memory database
        String file = PREFIX.equalsIgnoreCase(url) ?
            ":memory:" : url.substring(PREFIX.length());

        boolean sharedCache = Boolean.parseBoolean(info.getProperty("shared_cache"));
        boolean julianDayMode = Boolean.parseBoolean(info.getProperty("julian_day"));
        return new Conn(url, file, sharedCache, julianDayMode);
    }
}
