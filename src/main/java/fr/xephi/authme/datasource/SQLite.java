package fr.xephi.authme.datasource;

import com.google.common.annotations.VisibleForTesting;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.cache.auth.PlayerAuth;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.DatabaseSettings;
import fr.xephi.authme.util.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 */
public class SQLite implements DataSource {

    private final String database;
    private final String tableName;
    private final Columns col;
    private Connection con;

    /**
     * Constructor for SQLite.
     *
     * @param settings The settings instance
     *
     * @throws ClassNotFoundException if no driver could be found for the datasource
     * @throws SQLException           when initialization of a SQL datasource failed
     */
    public SQLite(Settings settings) throws ClassNotFoundException, SQLException {
        this.database = settings.getProperty(DatabaseSettings.MYSQL_DATABASE);
        this.tableName = settings.getProperty(DatabaseSettings.MYSQL_TABLE);
        this.col = new Columns(settings);

        try {
            this.connect();
            this.setup();
        } catch (ClassNotFoundException | SQLException ex) {
            ConsoleLogger.logException("Error during SQLite initialization:", ex);
            throw ex;
        }
    }

    @VisibleForTesting
    SQLite(Settings settings, Connection connection) {
        this.database = settings.getProperty(DatabaseSettings.MYSQL_DATABASE);
        this.tableName = settings.getProperty(DatabaseSettings.MYSQL_TABLE);
        this.col = new Columns(settings);
        this.con = connection;
    }

    private static void logSqlException(SQLException e) {
        ConsoleLogger.logException("Error while executing SQL statement:", e);
    }

    private void connect() throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        ConsoleLogger.info("SQLite driver loaded");
        this.con = DriverManager.getConnection("jdbc:sqlite:plugins/AuthMe/" + database + ".db");
    }

    @VisibleForTesting
    protected void setup() throws SQLException {
        try (Statement st = con.createStatement()) {
            // Note: cannot add unique fields later on in SQLite, so we add it on initialization
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + col.ID + " INTEGER AUTO_INCREMENT, "
                + col.NAME + " VARCHAR(255) NOT NULL UNIQUE, "
                + "CONSTRAINT table_const_prim PRIMARY KEY (" + col.ID + "));");

            DatabaseMetaData md = con.getMetaData();

            if (isColumnMissing(md, col.REAL_NAME)) {
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN "
                    + col.REAL_NAME + " VARCHAR(255) NOT NULL DEFAULT 'Player';");
            }

            if (isColumnMissing(md, col.PASSWORD)) {
                st.executeUpdate("ALTER TABLE " + tableName
                    + " ADD COLUMN " + col.PASSWORD + " VARCHAR(255) NOT NULL DEFAULT '';");
            }

            if (!col.SALT.isEmpty() && isColumnMissing(md, col.SALT)) {
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + col.SALT + " VARCHAR(255);");
            }

            if (isColumnMissing(md, col.IP)) {
                st.executeUpdate("ALTER TABLE " + tableName
                    + " ADD COLUMN " + col.IP + " VARCHAR(40) NOT NULL DEFAULT '';");
            }

            if (isColumnMissing(md, col.LAST_LOGIN)) {
                st.executeUpdate("ALTER TABLE " + tableName
                    + " ADD COLUMN " + col.LAST_LOGIN + " TIMESTAMP;");
            }

            if (isColumnMissing(md, col.LASTLOC_X)) {
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + col.LASTLOC_X
                    + " DOUBLE NOT NULL DEFAULT '0.0';");
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + col.LASTLOC_Y
                    + " DOUBLE NOT NULL DEFAULT '0.0';");
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + col.LASTLOC_Z
                    + " DOUBLE NOT NULL DEFAULT '0.0';");
            }

            if (isColumnMissing(md, col.LASTLOC_WORLD)) {
                st.executeUpdate("ALTER TABLE " + tableName
                    + " ADD COLUMN " + col.LASTLOC_WORLD + " VARCHAR(255) NOT NULL DEFAULT 'world';");
            }

            if (isColumnMissing(md, col.EMAIL)) {
                st.executeUpdate("ALTER TABLE " + tableName
                    + " ADD COLUMN " + col.EMAIL + " VARCHAR(255) DEFAULT 'your@email.com';");
            }

            if (isColumnMissing(md, col.IS_LOGGED)) {
                st.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + col.IS_LOGGED + " INT DEFAULT '0';");
            }
        }
        ConsoleLogger.info("SQLite Setup finished");
    }

    private boolean isColumnMissing(DatabaseMetaData metaData, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, tableName, columnName)) {
            return !rs.next();
        }
    }

    @Override
    public void reload() {
        close(con);
        try {
            this.connect();
            this.setup();
        } catch (ClassNotFoundException | SQLException ex) {
            ConsoleLogger.logException("Error during SQLite initialization:", ex);
        }
    }

    @Override
    public boolean isAuthAvailable(String user) {
        PreparedStatement pst = null;
        ResultSet rs = null;
        try {
            pst = con.prepareStatement("SELECT 1 FROM " + tableName + " WHERE LOWER(" + col.NAME + ")=LOWER(?);");
            pst.setString(1, user);
            rs = pst.executeQuery();
            return rs.next();
        } catch (SQLException ex) {
            ConsoleLogger.warning(ex.getMessage());
            return false;
        } finally {
            close(rs);
            close(pst);
        }
    }

    @Override
    public HashedPassword getPassword(String user) {
        boolean useSalt = !col.SALT.isEmpty();
        String sql = "SELECT " + col.PASSWORD
            + (useSalt ? ", " + col.SALT : "")
            + " FROM " + tableName + " WHERE " + col.NAME + "=?";
        try (PreparedStatement pst = con.prepareStatement(sql)) {
            pst.setString(1, user);
            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    return new HashedPassword(rs.getString(col.PASSWORD),
                        useSalt ? rs.getString(col.SALT) : null);
                }
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return null;
    }

    @Override
    public PlayerAuth getAuth(String user) {
        PreparedStatement pst = null;
        ResultSet rs = null;
        try {
            pst = con.prepareStatement("SELECT * FROM " + tableName + " WHERE LOWER(" + col.NAME + ")=LOWER(?);");
            pst.setString(1, user);
            rs = pst.executeQuery();
            if (rs.next()) {
                return buildAuthFromResultSet(rs);
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(rs);
            close(pst);
        }
        return null;
    }

    @Override
    public boolean saveAuth(PlayerAuth auth) {
        PreparedStatement pst = null;
        try {
            HashedPassword password = auth.getPassword();
            if (col.SALT.isEmpty()) {
                if (!StringUtils.isEmpty(auth.getPassword().getSalt())) {
                    ConsoleLogger.warning("Warning! Detected hashed password with separate salt but the salt column "
                        + "is not set in the config!");
                }
                pst = con.prepareStatement("INSERT INTO " + tableName + "(" + col.NAME + "," + col.PASSWORD +
                    "," + col.IP + "," + col.LAST_LOGIN + "," + col.REAL_NAME + "," + col.EMAIL +
                    ") VALUES (?,?,?,?,?,?);");
                pst.setString(1, auth.getNickname());
                pst.setString(2, password.getHash());
                pst.setString(3, auth.getIp());
                pst.setLong(4, auth.getLastLogin());
                pst.setString(5, auth.getRealName());
                pst.setString(6, auth.getEmail());
                pst.executeUpdate();
            } else {
                pst = con.prepareStatement("INSERT INTO " + tableName + "(" + col.NAME + "," + col.PASSWORD + ","
                    + col.IP + "," + col.LAST_LOGIN + "," + col.REAL_NAME + "," + col.EMAIL + "," + col.SALT
                    + ") VALUES (?,?,?,?,?,?,?);");
                pst.setString(1, auth.getNickname());
                pst.setString(2, password.getHash());
                pst.setString(3, auth.getIp());
                pst.setLong(4, auth.getLastLogin());
                pst.setString(5, auth.getRealName());
                pst.setString(6, auth.getEmail());
                pst.setString(7, password.getSalt());
                pst.executeUpdate();
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
        return true;
    }

    @Override
    public boolean updatePassword(PlayerAuth auth) {
        return updatePassword(auth.getNickname(), auth.getPassword());
    }

    @Override
    public boolean updatePassword(String user, HashedPassword password) {
        user = user.toLowerCase();
        PreparedStatement pst = null;
        try {
            boolean useSalt = !col.SALT.isEmpty();
            String sql = "UPDATE " + tableName + " SET " + col.PASSWORD + " = ?"
                + (useSalt ? ", " + col.SALT + " = ?" : "")
                + " WHERE " + col.NAME + " = ?";
            pst = con.prepareStatement(sql);
            pst.setString(1, password.getHash());
            if (useSalt) {
                pst.setString(2, password.getSalt());
                pst.setString(3, user);
            } else {
                pst.setString(2, user);
            }
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
        return false;
    }

    @Override
    public boolean updateSession(PlayerAuth auth) {
        PreparedStatement pst = null;
        try {
            pst = con.prepareStatement("UPDATE " + tableName + " SET " + col.IP + "=?, " + col.LAST_LOGIN + "=?, " + col.REAL_NAME + "=? WHERE " + col.NAME + "=?;");
            pst.setString(1, auth.getIp());
            pst.setLong(2, auth.getLastLogin());
            pst.setString(3, auth.getRealName());
            pst.setString(4, auth.getNickname());
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
        return false;
    }

    @Override
    public Set<String> getRecordsToPurge(long until, boolean includeEntriesWithLastLoginZero) {
        Set<String> list = new HashSet<>();

        String select = "SELECT " + col.NAME + " FROM " + tableName + " WHERE " + col.LAST_LOGIN + " < ?";
        if (!includeEntriesWithLastLoginZero) {
            select += " AND " + col.LAST_LOGIN + " <> 0";
        }
        try (PreparedStatement selectPst = con.prepareStatement(select)) {
            selectPst.setLong(1, until);
            try (ResultSet rs = selectPst.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(col.NAME));
                }
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }

        return list;
    }

    @Override
    public void purgeRecords(Collection<String> toPurge) {
        String delete = "DELETE FROM " + tableName + " WHERE " + col.NAME + "=?;";
        try (PreparedStatement deletePst = con.prepareStatement(delete)) {
            for (String name : toPurge) {
                deletePst.setString(1, name.toLowerCase());
                deletePst.executeUpdate();
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
    }

    @Override
    public boolean removeAuth(String user) {
        PreparedStatement pst = null;
        try {
            pst = con.prepareStatement("DELETE FROM " + tableName + " WHERE " + col.NAME + "=?;");
            pst.setString(1, user.toLowerCase());
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
        return false;
    }

    @Override
    public boolean updateQuitLoc(PlayerAuth auth) {
        PreparedStatement pst = null;
        try {
            pst = con.prepareStatement("UPDATE " + tableName + " SET " + col.LASTLOC_X + "=?, " + col.LASTLOC_Y + "=?, " + col.LASTLOC_Z + "=?, " + col.LASTLOC_WORLD + "=? WHERE " + col.NAME + "=?;");
            pst.setDouble(1, auth.getQuitLocX());
            pst.setDouble(2, auth.getQuitLocY());
            pst.setDouble(3, auth.getQuitLocZ());
            pst.setString(4, auth.getWorld());
            pst.setString(5, auth.getNickname());
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
        return false;
    }

    @Override
    public boolean updateEmail(PlayerAuth auth) {
        String sql = "UPDATE " + tableName + " SET " + col.EMAIL + "=? WHERE " + col.NAME + "=?;";
        try (PreparedStatement pst = con.prepareStatement(sql)) {
            pst.setString(1, auth.getEmail());
            pst.setString(2, auth.getNickname());
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return false;
    }

    @Override
    public void close() {
        try {
            if (con != null && !con.isClosed()) {
                con.close();
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
    }

    private void close(Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ex) {
                logSqlException(ex);
            }
        }
    }

    private void close(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ex) {
                logSqlException(ex);
            }
        }
    }

    private void close(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ex) {
                logSqlException(ex);
            }
        }
    }

    @Override
    public List<String> getAllAuthsByIp(String ip) {
        PreparedStatement pst = null;
        ResultSet rs = null;
        List<String> countIp = new ArrayList<>();
        try {
            pst = con.prepareStatement("SELECT " + col.NAME + " FROM " + tableName + " WHERE " + col.IP + "=?;");
            pst.setString(1, ip);
            rs = pst.executeQuery();
            while (rs.next()) {
                countIp.add(rs.getString(col.NAME));
            }
            return countIp;
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(rs);
            close(pst);
        }
        return new ArrayList<>();
    }

    @Override
    public int countAuthsByEmail(String email) {
        String sql = "SELECT COUNT(1) FROM " + tableName + " WHERE " + col.EMAIL + " = ? COLLATE NOCASE;";
        try (PreparedStatement pst = con.prepareStatement(sql)) {
            pst.setString(1, email);
            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return 0;
    }

    @Override
    public DataSourceType getType() {
        return DataSourceType.SQLITE;
    }

    @Override
    public boolean isLogged(String user) {
        PreparedStatement pst = null;
        ResultSet rs = null;
        try {
            pst = con.prepareStatement("SELECT * FROM " + tableName + " WHERE LOWER(" + col.NAME + ")=?;");
            pst.setString(1, user);
            rs = pst.executeQuery();
            if (rs.next())
                return (rs.getInt(col.IS_LOGGED) == 1);
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(rs);
            close(pst);
        }
        return false;
    }

    @Override
    public void setLogged(String user) {
        PreparedStatement pst = null;
        try {
            pst = con.prepareStatement("UPDATE " + tableName + " SET " + col.IS_LOGGED + "=? WHERE LOWER(" + col.NAME + ")=?;");
            pst.setInt(1, 1);
            pst.setString(2, user);
            pst.executeUpdate();
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
    }

    @Override
    public void setUnlogged(String user) {
        PreparedStatement pst = null;
        if (user != null)
            try {
                pst = con.prepareStatement("UPDATE " + tableName + " SET " + col.IS_LOGGED + "=? WHERE LOWER(" + col.NAME + ")=?;");
                pst.setInt(1, 0);
                pst.setString(2, user);
                pst.executeUpdate();
            } catch (SQLException ex) {
                logSqlException(ex);
            } finally {
                close(pst);
            }
    }

    @Override
    public void purgeLogged() {
        PreparedStatement pst = null;
        try {
            pst = con.prepareStatement("UPDATE " + tableName + " SET " + col.IS_LOGGED + "=? WHERE " + col.IS_LOGGED + "=?;");
            pst.setInt(1, 0);
            pst.setInt(2, 1);
            pst.executeUpdate();
        } catch (SQLException ex) {
            logSqlException(ex);
        } finally {
            close(pst);
        }
    }

    @Override
    public int getAccountsRegistered() {
        String sql = "SELECT COUNT(*) FROM " + tableName + ";";
        try (PreparedStatement pst = con.prepareStatement(sql); ResultSet rs = pst.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return 0;
    }

    @Override
    public boolean updateRealName(String user, String realName) {
        String sql = "UPDATE " + tableName + " SET " + col.REAL_NAME + "=? WHERE " + col.NAME + "=?;";
        try (PreparedStatement pst = con.prepareStatement(sql)) {
            pst.setString(1, realName);
            pst.setString(2, user);
            pst.executeUpdate();
            return true;
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return false;
    }

    @Override
    public List<PlayerAuth> getAllAuths() {
        List<PlayerAuth> auths = new ArrayList<>();
        String sql = "SELECT * FROM " + tableName + ";";
        try (PreparedStatement pst = con.prepareStatement(sql); ResultSet rs = pst.executeQuery()) {
            while (rs.next()) {
                PlayerAuth auth = buildAuthFromResultSet(rs);
                auths.add(auth);
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return auths;
    }

    @Override
    public List<PlayerAuth> getLoggedPlayers() {
        List<PlayerAuth> auths = new ArrayList<>();
        String sql = "SELECT * FROM " + tableName + " WHERE " + col.IS_LOGGED + "=1;";
        try (PreparedStatement pst = con.prepareStatement(sql); ResultSet rs = pst.executeQuery()) {
            while (rs.next()) {
                PlayerAuth auth = buildAuthFromResultSet(rs);
                auths.add(auth);
            }
        } catch (SQLException ex) {
            logSqlException(ex);
        }
        return auths;
    }

    private PlayerAuth buildAuthFromResultSet(ResultSet row) throws SQLException {
        String salt = !col.SALT.isEmpty() ? row.getString(col.SALT) : null;

        PlayerAuth.Builder authBuilder = PlayerAuth.builder()
            .name(row.getString(col.NAME))
            .email(row.getString(col.EMAIL))
            .realName(row.getString(col.REAL_NAME))
            .password(row.getString(col.PASSWORD), salt)
            .lastLogin(row.getLong(col.LAST_LOGIN))
            .locX(row.getDouble(col.LASTLOC_X))
            .locY(row.getDouble(col.LASTLOC_Y))
            .locZ(row.getDouble(col.LASTLOC_Z))
            .locWorld(row.getString(col.LASTLOC_WORLD));

        String ip = row.getString(col.IP);
        if (!ip.isEmpty()) {
            authBuilder.ip(ip);
        }
        return authBuilder.build();
    }
}
