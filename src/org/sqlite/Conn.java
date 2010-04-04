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

import java.io.File;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;

class Conn implements Connection
{
    private final String url;
    private final boolean readOnly;
    private DB db = null;
    private MetaData meta = null;
    private boolean autoCommit = true;
    private int timeout = 0;
    private int transactionIsolation = TRANSACTION_SERIALIZABLE;
    private int savepointId = 0;

    public Conn(String url, String filename, boolean sharedCache, boolean julianDayMode)
            throws SQLException {
        this(url, filename);
        db.shared_cache(sharedCache);
        db.setJulianDayMode(julianDayMode);
    }
    public Conn(String url, String filename) throws SQLException {
        boolean ro = false;

        // check the path to the file exists
        if (!":memory:".equals(filename)) {
            File file = new File(filename).getAbsoluteFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                for (File up = parent; up != null && !up.exists();) {
                    parent = up;
                    up = up.getParentFile();
                }
                throw new SQLException("path to '" + filename + "': '"
                        + parent + "' does not exist");
            }

            // check write access if file does not exist
            try {
                 // The extra check to exists() is necessary as createNewFile()
                 // does not follow the JavaDoc when used on read-only shares.
                if (!file.exists() && file.createNewFile()) file.delete();
            } catch (Exception e) {
                throw new SQLException(
                    "opening db: '" + filename + "': " +e.getMessage());
            }
            filename = file.getAbsolutePath();
            if (file.exists())
                ro = !file.canWrite();
        }

        readOnly = ro;

        if (NativeDB.load())
            db = new NativeDB();
        else
            throw new SQLException("no SQLite library found");

        this.url = url;
        db.open(this, filename);
        setTimeout(3000);
    }

    int getTimeout() { return timeout; }
    void setTimeout(int ms) throws SQLException {
        timeout = ms;
        db.busy_timeout(ms);
    }
    String url() { return url; }
    String libversion() throws SQLException { return db.libversion(); }
    DB db() { return db; }

    private void checkOpen() throws SQLException {
        if (db == null)  throw new SQLException("database connection closed");
    }

    private void checkCursor(int rst, int rsc, int rsh) throws SQLException {
        if (rst != ResultSet.TYPE_FORWARD_ONLY) throw new SQLException(
            "SQLite only supports TYPE_FORWARD_ONLY cursors");
        if (rsc != ResultSet.CONCUR_READ_ONLY) throw new SQLException(
            "SQLite only supports CONCUR_READ_ONLY cursors");
        if (rsh != ResultSet.CLOSE_CURSORS_AT_COMMIT) throw new SQLException(
            "SQLite only supports closing cursors at commit");
    }

    public void finalize() throws SQLException { close(); }
    public void close() throws SQLException {
        if (db == null) return;
        if (meta != null) meta.close();

        db.close();
        db = null;
    }

    public boolean isClosed() throws SQLException { return db == null; }

    public String getCatalog() throws SQLException { checkOpen(); return null; }
    public void setCatalog(String catalog) throws SQLException { checkOpen(); }

    public int getHoldability() throws SQLException {
        checkOpen();  return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    public void setHoldability(int h) throws SQLException {
        checkOpen();
        if (h != ResultSet.CLOSE_CURSORS_AT_COMMIT) throw new SQLException(
            "SQLite only supports CLOSE_CURSORS_AT_COMMIT");
    }

    public int getTransactionIsolation() { return transactionIsolation; }
    public void setTransactionIsolation(int level) throws SQLException {
       switch (level) {
       case TRANSACTION_SERIALIZABLE:
           db.exec("PRAGMA read_uncommitted = false;");
           break;
       case TRANSACTION_READ_UNCOMMITTED:
           db.exec("PRAGMA read_uncommitted = true;");
           break;
       default:
           throw new SQLException("SQLite supports only TRANSACTION_SERIALIZABLE and TRANSACTION_READ_UNCOMMITTED.");
       }
       transactionIsolation = level;
    }

    public Map<String,Class<?>> getTypeMap() throws SQLException
        { throw new SQLException("not yet implemented");}
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException
        { throw new SQLException("not yet implemented");}

    public boolean isReadOnly() throws SQLException { return readOnly; }
    public void setReadOnly(boolean ro) throws SQLException {}

    public DatabaseMetaData getMetaData() {
        if (meta == null) meta = new MetaData(this);
        return meta;
    }

    public String nativeSQL(String sql) { return sql; }

    public void clearWarnings() throws SQLException { }
    public SQLWarning getWarnings() throws SQLException { return null; }

    public boolean getAutoCommit() throws SQLException {
        checkOpen(); return autoCommit; }
    public void setAutoCommit(boolean ac) throws SQLException {
        checkOpen();
        if (autoCommit == ac) return;
        autoCommit = ac;
        db.exec(autoCommit ? "commit;" : "begin;");
    }

    public void commit() throws SQLException {
        checkOpen();
        if (autoCommit) throw new SQLException("database in auto-commit mode");
        db.exec("commit;");
        db.exec("begin;");
    }

    public void rollback() throws SQLException {
        checkOpen();
        if (autoCommit) throw new SQLException("database in auto-commit mode");
        db.exec("rollback;");
        db.exec("begin;");
    }

    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY,
                               ResultSet.CONCUR_READ_ONLY,
                               ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
    public Statement createStatement(int rsType, int rsConcurr)
        throws SQLException { return createStatement(rsType, rsConcurr,
                                          ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
    public Statement createStatement(int rst, int rsc, int rsh)
        throws SQLException {
        checkCursor(rst, rsc, rsh);
        return new Stmt(this);
    }

    public CallableStatement prepareCall(String sql) throws SQLException {
        return prepareCall(sql, ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY,
                                ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
    public CallableStatement prepareCall(String sql, int rst, int rsc)
                                throws SQLException {
        return prepareCall(sql, rst, rsc, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
    public CallableStatement prepareCall(String sql, int rst, int rsc, int rsh)
                                throws SQLException {
        throw new SQLException("SQLite does not support Stored Procedures");
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                                     ResultSet.CONCUR_READ_ONLY);
    }
    public PreparedStatement prepareStatement(String sql, int autoC)
        throws SQLException { throw new SQLException("NYI"); }
    public PreparedStatement prepareStatement(String sql, int[] colInds)
        throws SQLException { throw new SQLException("NYI"); }
    public PreparedStatement prepareStatement(String sql, String[] colNames)
        throws SQLException { throw new SQLException("NYI"); }
    public PreparedStatement prepareStatement(String sql, int rst, int rsc)
                                throws SQLException {
        return prepareStatement(sql, rst, rsc,
                                ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    public PreparedStatement prepareStatement(
            String sql, int rst, int rsc, int rsh) throws SQLException {
        checkCursor(rst, rsc, rsh);
        return new PrepStmt(this, sql);
    }

    /** Used to supply DatabaseMetaData.getDriverVersion(). */
    String getDriverVersion() {
        if (db != null) {
            return "native";
        }
        return "unloaded";
    }

    public Savepoint setSavepoint() throws SQLException {
        final Spt spt = new Spt(savepointId++);
        db.exec("SAVEPOINT " + spt.getNameOrId());
        return spt;
    }
    public Savepoint setSavepoint(String name) throws SQLException {
        final Spt spt = new Spt(name);
        db.exec("SAVEPOINT " + spt.getNameOrId());
        return spt;
    }
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        db.exec("RELEASE SAVEPOINT "+ ((Spt)savepoint).getNameOrId());
    }
    public void rollback(Savepoint savepoint) throws SQLException {
        db.exec("ROLLBACK TO SAVEPOINT " + ((Spt)savepoint).getNameOrId());
    }

    public Clob createClob() throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public Blob createBlob() throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public NClob createNClob() throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public SQLXML createSQLXML() throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public boolean isValid(int timeout) throws SQLException {
        return false;  // FIXME
    }

    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new SQLClientInfoException(); // TODO
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new SQLClientInfoException(); // TODO
    }

    public String getClientInfo(String name) throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public Properties getClientInfo() throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLException("NYI"); // TODO
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("Not a wrapper");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
