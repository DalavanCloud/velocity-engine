package org.apache.velocity.runtime.resource.loader;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.util.ExtProperties;

import org.apache.commons.lang3.StringUtils;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * <P>This is a simple template file loader that loads templates
 * from a DataSource instead of plain files.
 *
 * <P>It can be configured with a datasource name, a table name,
 * id column (name), content column (the template body) and a
 * datetime column (for last modification info).
 * <br>
 * <br>
 * Example configuration snippet for velocity.properties:
 * <br>
 * <br>
 * resource.loader = file, ds <br>
 * <br>
 * ds.resource.loader.public.name = DataSource <br>
 * ds.resource.loader.description = Velocity DataSource Resource Loader <br>
 * ds.resource.loader.class = org.apache.velocity.runtime.resource.loader.DataSourceResourceLoader <br>
 * ds.resource.loader.resource.datasource = java:comp/env/jdbc/Velocity <br>
 * ds.resource.loader.resource.table = tb_velocity_template <br>
 * ds.resource.loader.resource.keycolumn = id_template <br>
 * ds.resource.loader.resource.templatecolumn = template_definition <br>
 * ds.resource.loader.resource.timestampcolumn = template_timestamp <br>
 * ds.resource.loader.cache = false <br>
 * ds.resource.loader.modificationCheckInterval = 60 <br>
 * <br>
 * <P>Optionally, the developer can instantiate the DataSourceResourceLoader and set the DataSource via code in
 * a manner similar to the following:
 * <BR>
 * <BR>
 * DataSourceResourceLoader ds = new DataSourceResourceLoader();<BR>
 * ds.setDataSource(DATASOURCE);<BR>
 * Velocity.setProperty("ds.resource.loader.instance",ds);<BR>
 * <P> The property <code>ds.resource.loader.class</code> should be left out, otherwise all the other
 * properties in velocity.properties would remain the same.
 * <BR>
 * <BR>
 *
 * Example WEB-INF/web.xml: <br>
 * <br>
 *  <resource-ref> <br>
 *   <description>Velocity template DataSource</description> <br>
 *   <res-ref-name>jdbc/Velocity</res-ref-name> <br>
 *   <res-type>javax.sql.DataSource</res-type> <br>
 *   <res-auth>Container</res-auth> <br>
 *  </resource-ref> <br>
 * <br>
 *  <br>
 * and Tomcat 4 server.xml file: <br>
 *  [...] <br>
 *  <Context path="/exampleVelocity" docBase="exampleVelocity" debug="0"> <br>
 *  [...] <br>
 *   <ResourceParams name="jdbc/Velocity"> <br>
 *    <parameter> <br>
 *      <name>driverClassName</name> <br>
 *      <value>org.hsql.jdbcDriver</value> <br>
 *    </parameter> <br>
 *    <parameter> <br>
 *     <name>driverName</name> <br>
 *     <value>jdbc:HypersonicSQL:database</value> <br>
 *    </parameter> <br>
 *    <parameter> <br>
 *     <name>user</name> <br>
 *     <value>database_username</value> <br>
 *    </parameter> <br>
 *    <parameter> <br>
 *     <name>password</name> <br>
 *     <value>database_password</value> <br>
 *    </parameter> <br>
 *   </ResourceParams> <br>
 *  [...] <br>
 *  </Context> <br>
 *  [...] <br>
 * <br>
 *  Example sql script:<br>
 *  CREATE TABLE tb_velocity_template ( <br>
 *  id_template varchar (40) NOT NULL , <br>
 *  template_definition text (16) NOT NULL , <br>
 *  template_timestamp datetime NOT NULL  <br>
 *  ) <br>
 *
 * @author <a href="mailto:wglass@forio.com">Will Glass-Husain</a>
 * @author <a href="mailto:matt@raibledesigns.com">Matt Raible</a>
 * @author <a href="mailto:david.kinnvall@alertir.com">David Kinnvall</a>
 * @author <a href="mailto:paulo.gaspar@krankikom.de">Paulo Gaspar</a>
 * @author <a href="mailto:lachiewicz@plusnet.pl">Sylwester Lachiewicz</a>
 * @author <a href="mailto:henning@apache.org">Henning P. Schmiedehausen</a>
 * @version $Id$
 * @since 1.5
 */
public class DataSourceResourceLoader extends ResourceLoader
{
    private String dataSourceName;
    private String tableName;
    private String keyColumn;
    private String templateColumn;
    private String timestampColumn;
    private InitialContext ctx;
    private DataSource dataSource;

    /*
        Keep connection and prepared statements open. It's not just an optimization:
        For several engines, the connection, and/or the statement, and/or the result set
        must be kept open for the reader to be valid.
     */
    private Connection connection = null;
    private PreparedStatement templatePrepStatement = null;
    private PreparedStatement timestampPrepStatement = null;

    private static class SelfCleaningReader extends FilterReader
    {
        private ResultSet resultSet;

        public SelfCleaningReader(Reader reader, ResultSet resultSet)
        {
            super(reader);
            this.resultSet = resultSet;
        }

        @Override
        public void close() throws IOException
        {
            super.close();
            try
            {
                resultSet.close();
            }
            catch (RuntimeException re)
            {
                throw re;
            }
            catch (Exception e)
            {
                // ignore
            }
        }
    }

    /**
     * @see ResourceLoader#init(org.apache.velocity.util.ExtProperties)
     */
    public void init(ExtProperties configuration)
    {
        dataSourceName  = StringUtils.trim(configuration.getString("resource.datasource"));
        tableName       = StringUtils.trim(configuration.getString("resource.table"));
        keyColumn       = StringUtils.trim(configuration.getString("resource.keycolumn"));
        templateColumn  = StringUtils.trim(configuration.getString("resource.templatecolumn"));
        timestampColumn = StringUtils.trim(configuration.getString("resource.timestampcolumn"));

        if (dataSource != null)
        {
            log.debug("DataSourceResourceLoader: using dataSource instance with table \"{}\"", tableName);
            log.debug("DataSourceResourceLoader: using columns \"{}\", \"{}\" and \"{}\"", keyColumn, templateColumn, timestampColumn);

            log.trace("DataSourceResourceLoader initialized.");
        }
        else if (dataSourceName != null)
        {
            log.debug("DataSourceResourceLoader: using \"{}\" datasource with table \"{}\"", dataSourceName, tableName);
            log.debug("DataSourceResourceLoader: using columns \"{}\", \"{}\" and \"{}\"", keyColumn, templateColumn, timestampColumn);

            log.trace("DataSourceResourceLoader initialized.");
        }
        else
        {
            String msg = "DataSourceResourceLoader not properly initialized. No DataSource was identified.";
            log.error(msg);
            throw new RuntimeException(msg);
        }
    }

    /**
     * Set the DataSource used by this resource loader.  Call this as an alternative to
     * specifying the data source name via properties.
     * @param dataSource The data source for this ResourceLoader.
     */
    public void setDataSource(final DataSource dataSource)
    {
        this.dataSource = dataSource;
    }

    /**
     * @see ResourceLoader#isSourceModified(org.apache.velocity.runtime.resource.Resource)
     */
    public boolean isSourceModified(final Resource resource)
    {
        return (resource.getLastModified() !=
                readLastModified(resource, "checking timestamp"));
    }

    /**
     * @see ResourceLoader#getLastModified(org.apache.velocity.runtime.resource.Resource)
     */
    public long getLastModified(final Resource resource)
    {
        return readLastModified(resource, "getting timestamp");
    }

    /**
     * Get an InputStream so that the Runtime can build a
     * template with it.
     *
     * @param name name of template
     * @param encoding asked encoding
     * @return InputStream containing template
     * @throws ResourceNotFoundException
     * @since 2.0
     */
    public synchronized Reader getResourceReader(final String name, String encoding)
            throws ResourceNotFoundException
    {
        if (StringUtils.isEmpty(name))
        {
            throw new ResourceNotFoundException("DataSourceResourceLoader: Template name was empty or null");
        }

        ResultSet rs = null;
        try
        {
            checkDBConnection();
            rs = fetchResult(templatePrepStatement, name);

            if (rs.next())
            {
                Reader reader = rs.getCharacterStream(templateColumn);
                if (reader == null)
                {
                    throw new ResourceNotFoundException("DataSourceResourceLoader: "
                            + "template column for '"
                            + name + "' is null");
                }
                return new SelfCleaningReader(reader, rs);
            }
            else
            {
                throw new ResourceNotFoundException("DataSourceResourceLoader: "
                        + "could not find resource '"
                        + name + "'");

            }
        }
        catch (SQLException | NamingException sqle)
        {
            String msg = "DataSourceResourceLoader: database problem while getting resource '"
                    + name + "': ";

            log.error(msg, sqle);
            throw new ResourceNotFoundException(msg);
        }
    }

    /**
     * Fetches the last modification time of the resource
     *
     * @param resource Resource object we are finding timestamp of
     * @param operation string for logging, indicating caller's intention
     *
     * @return timestamp as long
     */
    private long readLastModified(final Resource resource, final String operation)
    {
        long timeStamp = 0;

        /* get the template name from the resource */
        String name = resource.getName();
        if (name == null || name.length() == 0)
        {
            String msg = "DataSourceResourceLoader: Template name was empty or null";
            log.error(msg);
            throw new NullPointerException(msg);
        }
        else
        {
            ResultSet rs = null;

            try
            {
                checkDBConnection();
                rs = fetchResult(timestampPrepStatement, name);

                if (rs.next())
                {
                    Timestamp ts = rs.getTimestamp(timestampColumn);
                    timeStamp = ts != null ? ts.getTime() : 0;
                }
                else
                {
                    String msg = "DataSourceResourceLoader: could not find resource "
                              + name + " while " + operation;
                    log.error(msg);
                    throw new ResourceNotFoundException(msg);
                }
            }
            catch (SQLException | NamingException sqle)
            {
                String msg = "DataSourceResourceLoader: database problem while "
                            + operation + " of '" + name + "': ";

                log.error(msg, sqle);
                throw new VelocityException(msg, sqle);
            }
            finally
            {
                closeResultSet(rs);
            }
        }
        return timeStamp;
    }

    /**
     * Gets connection to the datasource specified through the configuration
     * parameters.
     *
     */
    private void openDBConnection() throws NamingException, SQLException
    {
        if (dataSource == null)
        {
            if (ctx == null)
            {
                ctx = new InitialContext();
            }

            dataSource = (DataSource) ctx.lookup(dataSourceName);
        }

        if (connection != null)
        {
            closeDBConnection();
        }

        connection = dataSource.getConnection();
        templatePrepStatement = prepareStatement(connection, templateColumn, tableName, keyColumn);
        timestampPrepStatement = prepareStatement(connection, timestampColumn, tableName, keyColumn);
    }

    /**
     * Checks the connection is valid
     *
     */
    private void checkDBConnection() throws NamingException, SQLException
    {
        if (connection == null || !connection.isValid(0))
        {
            openDBConnection();
        }
    }

    /**
     * Close DB connection on finalization
     *
     * @throws Throwable
     */
    protected void finalize()
        throws Throwable
    {
        closeDBConnection();
    }

    /**
     * Closes the prepared statements and the connection to the datasource
     */
    private void closeDBConnection()
    {
        if (templatePrepStatement != null)
        {
            try
            {
                templatePrepStatement.close();
            }
            catch (RuntimeException re)
            {
                throw re;
            }
            catch (SQLException e)
            {
                // ignore
            }
            finally
            {
                templatePrepStatement = null;
            }
        }
        if (timestampPrepStatement != null)
        {
            try
            {
                timestampPrepStatement.close();
            }
            catch (RuntimeException re)
            {
                throw re;
            }
            catch (SQLException e)
            {
                // ignore
            }
            finally
            {
                timestampPrepStatement = null;
            }
        }
        if (connection != null)
        {
            try
            {
                connection.close();
            }
            catch (RuntimeException re)
            {
                throw re;
            }
            catch (SQLException e)
            {
                // ignore
            }
            finally
            {
                connection = null;
            }
        }
    }

    /**
     * Closes the result set.
     */
    private void closeResultSet(final ResultSet rs)
    {
        if (rs != null)
        {
            try
            {
//                rs.close();
            }
            catch (RuntimeException re)
            {
                throw re;
            }
            catch (Exception e)
            {
                // ignore
            }
        }
    }

    /**
     * Creates the following PreparedStatement query :
     * <br>
     *  SELECT <i>columnNames</i> FROM <i>tableName</i> WHERE <i>keyColumn</i>
     *     = '<i>templateName</i>'
     * <br>
     * where <i>keyColumn</i> is a class member set in init()
     *
     * @param conn connection to datasource
     * @param columnNames columns to fetch from datasource
     * @param tableName table to fetch from
     * @param keyColumn column whose value should match templateName
     * @return PreparedStatement
     */
    protected PreparedStatement prepareStatement(
        final Connection conn,
        final String columnNames,
        final String tableName,
        final String keyColumn
    ) throws SQLException
    {
        PreparedStatement ps = conn.prepareStatement("SELECT " + columnNames + " FROM "+ tableName + " WHERE " + keyColumn + " = ?");
        return ps;
    }

    /**
     * Fetches the result for a given template name.
     * Inherit this method if there is any calculation to perform on the template name.
     *
     * @param ps target prepared statement
     * @param templateName input template name
     * @return result set
     * @throws SQLException
     */
    protected ResultSet fetchResult(
        final PreparedStatement ps,
        final String templateName
    ) throws SQLException
    {
        ps.setString(1, templateName);
        return ps.executeQuery();
    }

}
