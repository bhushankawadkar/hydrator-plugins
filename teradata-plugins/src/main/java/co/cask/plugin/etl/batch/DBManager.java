/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.plugin.etl.batch;

import co.cask.cdap.api.plugin.PluginProperties;
import co.cask.cdap.etl.api.Destroyable;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.plugin.etl.batch.source.TeradataSource;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Class to manage common database operations for {@link TeradataSource} and {@link TeradataSink}.
 */
public class DBManager implements Destroyable {
  private static final Logger LOG = LoggerFactory.getLogger(DBManager.class);
  private final TeradataConfig teradataConfig;
  private JDBCDriverShim driverShim;

  public DBManager(TeradataConfig teradataConfig) {
    this.teradataConfig = teradataConfig;
  }

  public void validateJDBCPluginPipeline(PipelineConfigurer pipelineConfigurer, String jdbcPluginId) {
    Preconditions.checkArgument(!(teradataConfig.user == null && teradataConfig.password != null),
                                "user is null. Please provide both user name and password if database requires " +
                                  "authentication. If not, please remove password and retry.");
    Preconditions.checkArgument(!(teradataConfig.user != null && teradataConfig.password == null),
                                "password is null. Please provide both user name and password if database requires" +
                                  "authentication. If not, please remove dbUser and retry.");
    Class<? extends Driver> jdbcDriverClass = pipelineConfigurer.usePluginClass(teradataConfig.jdbcPluginType,
                                                                                teradataConfig.jdbcPluginName,
                                                                                jdbcPluginId,
                                                                                PluginProperties.builder().build());
    Preconditions.checkArgument(
      jdbcDriverClass != null, "Unable to load JDBC Driver class for plugin name '%s'. Please make sure that the " +
        "plugin '%s' of type '%s' containing the driver has been installed correctly.", teradataConfig.jdbcPluginName,
      teradataConfig.jdbcPluginName, teradataConfig.jdbcPluginType);
    Preconditions.checkArgument(
      tableExists(jdbcDriverClass), "Table %s does not exist. Please check that the 'tableName' property has been " +
        "set correctly, and that the connection string %s points to a valid database.",
      teradataConfig.connectionString, teradataConfig.tableName);
  }

  private boolean tableExists(Class<? extends Driver> jdbcDriverClass) {
    try {
      ensureJDBCDriverIsAvailable(jdbcDriverClass);
    } catch (IllegalAccessException | InstantiationException | SQLException e) {
      LOG.error("Unable to load or register JDBC driver {} while checking for the existence of the database table {}.",
                jdbcDriverClass, teradataConfig.tableName, e);
      throw Throwables.propagate(e);
    }

    Connection connection;
    try {
      if (teradataConfig.user == null) {
        connection = DriverManager.getConnection(teradataConfig.connectionString);
      } else {
        connection = DriverManager.getConnection(teradataConfig.connectionString, teradataConfig.user, teradataConfig.password);
      }

      try {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, teradataConfig.tableName, null)) {
          return rs.next();
        }
      } finally {
        connection.close();
      }
    } catch (SQLException e) {
      LOG.error("Exception while trying to check the existence of database table {} for connection {}.",
                teradataConfig.tableName, teradataConfig.connectionString, e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * Ensures that the JDBC Driver specified in configuration is available and can be loaded. Also registers it with
   * {@link DriverManager} if it is not already registered.
   */
  public void ensureJDBCDriverIsAvailable(Class<? extends Driver> jdbcDriverClass)
    throws IllegalAccessException, InstantiationException, SQLException {
    try {
      DriverManager.getDriver(teradataConfig.connectionString);
    } catch (SQLException e) {
      // Driver not found. We will try to register it with the DriverManager.
      LOG.debug("Plugin Type: {} and Plugin Name: {}; Driver Class: {} not found. Registering JDBC driver via shim {} ",
                teradataConfig.jdbcPluginType, teradataConfig.jdbcPluginName, jdbcDriverClass.getName(),
                JDBCDriverShim.class.getName());
      driverShim = new JDBCDriverShim(jdbcDriverClass.newInstance());
      try {
        DBUtils.deregisterAllDrivers(jdbcDriverClass);
      } catch (NoSuchFieldException | ClassNotFoundException e1) {
        LOG.error("Unable to deregister JDBC Driver class {}", jdbcDriverClass);
      }
      DriverManager.registerDriver(driverShim);
    }
  }

  @Override
  public void destroy() {
    try {
      // DriverManager handles nulls
      DriverManager.deregisterDriver(driverShim);
    } catch (SQLException e) {
      LOG.warn("Error while de-registering JDBC drivers in ETLDBOutputFormat.", e);
      throw Throwables.propagate(e);
    }
  }
}
