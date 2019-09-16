package com.in10s.plugin.cassandra;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.in10s.platform.configuration.ApplicationConfiguration;
import com.in10s.rm.plugin.AbstractPlugin;
import com.in10s.rm.plugin.Plugin;
import com.in10s.rm.plugin.PluginProperty;
import com.in10s.rm.plugin.PluginPropertyTypes;
import com.in10s.rm.plugin.PluginRequest;
import com.in10s.rm.plugin.PluginResponse;
import com.in10s.rm.pojos.DataFieldTypes;
import com.in10s.rm.services.RMTextParser;
import com.in10s.rm.utils.JSONUtils;
import com.in10s.rm.utils.RequestUtils;

/**
 * 
 * CassandraPlugin
 * 
 * Input : PluginRequest POJO - which contains query details
 * 
 * Output : PluginResponse POJO - which holds response of task, if fails to
 * execute the given query then returns Error Message.
 *
 * @author ranjan.k
 */

@Component(immediate = true)
@Provides(specifications = { Plugin.class })
@Instantiate
public class CassandraPlugin extends AbstractPlugin  {

	/**
	 * LogService to Log messages.
	 */
	@Requires
	com.in10s.rm.logger.LogService logger;

	@Requires
	CassandraSource cs;

	@Requires
	RMTextParser parser;

	/**
	 * To create/convert JSONObjects and JSONArrays.
	 */
	ObjectMapper mapper = new ObjectMapper();

	@Requires
	CassandraSource cassandra;

	@Requires
	ApplicationConfiguration configs;

	

	@Validate
	public void start() {
		logger.info("CassandraPlugin 1.0.1 Started...");
	}

	@Override
	public List<PluginProperty> getPluginProperties() {

		List<PluginProperty> list = new ArrayList<PluginProperty>();

		PluginProperty fileTypeProp = new PluginProperty();
		fileTypeProp.setName("datasourceId");
		fileTypeProp.setType(PluginPropertyTypes.DEFAULT);
		fileTypeProp.setDesc("datasource Id");
		list.add(fileTypeProp);

		PluginProperty property = new PluginProperty();
		property.setName("queryType");
		property.setType(PluginPropertyTypes.DEFAULT);
		property.setDesc("");
		list.add(property);

		property = new PluginProperty();
		property.setName("query");
		property.setType(PluginPropertyTypes.DEFAULT);
		property.setDesc("");
		list.add(property);

		property = new PluginProperty();
		property.setName("inputs");
		property.setType(PluginPropertyTypes.INPUT_PROPERTY);
		property.setDesc("List of input columns of query exampl, [{'name':'empid','type':'integer'}]");
		list.add(property);

		property = new PluginProperty();
		property.setName("outputs");
		property.setType(PluginPropertyTypes.OUTPUT_PROPERTY);
		property.setDesc("");
		list.add(property);

		return list;
	}
	
	
	
	
	
	
	

	@Override
	public PluginResponse executePlugin(PluginRequest pluginRequest,
			JsonNode inJson) {
		long start = System.currentTimeMillis();
		logger.info("CassandraPlugin-> executePlugin() ");
		logger.debug("CassandraPlugin, pluginRequest ={0}", pluginRequest);
		logger.debug("CassandraPlugin, inJson ={0}", inJson);

		/**
		 * PluginResponse Object which contains the query result after executing
		 * the query. or contains error message if execution fails.
		 */
		PluginResponse pluginResponse = new PluginResponse();
		pluginResponse.setSuccess(false);
		pluginResponse.setErrorMessage("Error While Proccessing");

		/**
		 * List Of file input streams, these will be used for blob types and
		 * closed in finally block.
		 */
		List<FileInputStream> fileStreams = new ArrayList<FileInputStream>();

		try {
			/**
			 * Checking plugin type, if it is not Cassandra plugin then stops
			 * the execution and returns error message.
			 */
			if (pluginRequest.getPluginType() != getPluginType() || !pluginRequest.getVersion().equalsIgnoreCase(getVersion())) {
				logger.info("Invalid Plugin Type, Unable to proceed.");
				pluginResponse.setErrorMessage("InValid Plugin Type");
				return pluginResponse;
			}

			/**
			 * Getting all the required values from query like insert Columns
			 * and column values etc. insert Column Example,
			 * [{'name':'deptId','type':'integer'}]
			 * 
			 */

			ObjectNode propValues = (ObjectNode) pluginRequest.getTemplate();
			String dsId = propValues.get("datasourceId").asText();
			if (dsId == null || (dsId != null && dsId.length() <= 0)) {
				logger.info("Datasource Id not found, Unable to proceed.");
				pluginResponse.setErrorMessage("Datasource Id not found");
				return pluginResponse;
			}
			
			Session session = cassandra.getSession(dsId);
			ResultSet rs = null;
			long constart = System.currentTimeMillis();
			int totalRecords = 0;
			ArrayNode placeholderCols = null;
			if (propValues.get("inputs").isTextual()) {
				placeholderCols = (ArrayNode) mapper.readTree(propValues.get(
						"inputs").asText());
			} else {
				placeholderCols = (ArrayNode) propValues.get("inputs");
			}

			String queryString = propValues.get("query").asText();

			String queryType = propValues.get("queryType").asText();
			if (queryString == null) {
				queryString = propValues.get("query").asText();
			}
			if (inJson.isObject()) {

				/**
				 * Executing query
				 */
				logger.info("before parse : " + queryString);
				queryString = (String) parser.parseText(queryString, inJson);
				logger.info("after parse : " + queryString);

				if (placeholderCols.size() > 0) {
					PreparedStatement ps = session.prepare(queryString);
					logger.info("Executing prepare query");
					BoundStatement bs = NoSQLUtils.bindPreparedStatementValues(	ps, (ObjectNode) inJson, placeholderCols, logger,fileStreams, configs);
					rs = session.execute(bs);
				} else {
					logger.info("Executing base query");
					logger.info("QueryString before execution:{0}",queryString);
					
					for(char c:queryString.toCharArray()){
						logger.info("char "+c+"="+ "\\u" + Integer.toHexString(c | 0x10000).substring(1));
					}
					
					
					rs = session.execute(queryString);
				}
				totalRecords = 1;

			} else {
				PreparedStatement ps = session.prepare(queryString);
				BatchStatement batchStmt = new BatchStatement();
				ArrayNode paramsArray = (ArrayNode) inJson;

				/**
				 * Setting values to prepared statement
				 */
				Iterator<JsonNode> itr = paramsArray.iterator();
				while (itr.hasNext()) {
					BoundStatement bs = NoSQLUtils.bindPreparedStatementValues(
							ps, (ObjectNode) itr.next(), placeholderCols,
							logger, fileStreams, configs);
					batchStmt.add(bs);
				}

				/**
				 * Executing query
				 */
				rs = session.execute(batchStmt);
				totalRecords = paramsArray.size();
			}
			logger.debug("queryString to be executed:{0}", queryString);
			logger.info("Query executed succefully");
			logger.debug("Query Execution time="
					+ (System.currentTimeMillis() - constart) + " ms.");

			/**
			 * Setting value to Plug-in Response Object.
			 */
			pluginResponse.setSuccess(true);

			JsonNode selectColsNode = propValues.get("outputs");
			ArrayNode querySCols = null;
			if (selectColsNode.isTextual()) {
				querySCols = (ArrayNode) mapper.readTree(selectColsNode
						.asText());
			} else {
				querySCols = (ArrayNode) selectColsNode;
			}

			if (selectColsNode.size() > 0) {

				Iterator<Row> itr = rs.iterator();
				/**
				 * Array object which holds all the rows.
				 */
				ArrayNode rowSet = mapper.createArrayNode();

				/**
				 * Contains total number of rows fetched from database.
				 */
				totalRecords = 0;

				/**
				 * Preparing data array Object.
				 */

				while (itr.hasNext()) {
					Row dbrow = itr.next();

					/**
					 * Object node which holds the row data.
					 */
					ObjectNode row = mapper.createObjectNode();
					Iterator<JsonNode> selectList = querySCols.iterator();
					while (selectList.hasNext()) {
						ObjectNode col = (ObjectNode) selectList.next();
						String colName = col.get("name").asText();
						String colType = col.get("type").asText();

						/**
						 * Fetching column value from result set based on column
						 * type.
						 */
						switch (colType) {
						case DataFieldTypes.CLOB: {
							String ClobData = dbrow.getString(colName);
							row.put(colName, ClobData);
							break;
						}
						case DataFieldTypes.BLOB: {

							/**
							 * Blob column data can be returned as String, or
							 * can also be returned as file, If it is file then
							 * column details will be like,
							 * 
							 * [
							 * 'name':'employeeResume','type':'blob','isFile':tr
							 * u e ]
							 * 
							 * Finally we return the file absolute path as
							 * value.
							 * 
							 */
							ByteBuffer blob = dbrow.getBytes(colName);
							String BlobData = "";
							boolean isFile = col.get("isFile") != null ? col
									.get("isFile").asBoolean() : true;
							boolean makeServerURL = col.get("url") != null ? col
									.get("url").asBoolean() : false;
							if (blob != null) {
								byte[] bdata = blob.array();
								if (isFile) {
									File file = null;
									if (makeServerURL) {
										File downloadFiles = new File(
												configs.getBaseDir()
														+ "/"
														+ "templates"
														+ "/"
														+ "solutions"
														+ "/"
														+ "assets"
														+ "/"
														+ RequestUtils
																.getSolutionId()
														+ "/"
														+ RequestUtils
																.getSolutionVersion()
														+ "/" + "DownloadFiles");
										downloadFiles.mkdirs();
										String fileName = col.get("name")
												.asText()
												+ "_"
												+ System.currentTimeMillis()
												+ "."
												+ (col.has("extension") ? col
														.get("extension")
														.asText() : "temp");
										file = new File(downloadFiles, fileName);
										file.createNewFile();
										BlobData = "/"
												+ "solutions"
												+ "/"
												+ "assets"
												+ "/"
												+ RequestUtils.getSolutionId()
												+ "/"
												+ RequestUtils
														.getSolutionVersion()
												+ "/" + "DownloadFiles" + "/"
												+ fileName;
									} else {
										file = RequestUtils.getTempFile(col
												.has("extension") ? col.get(
												"extension").asText() : "temp");
										BlobData = file.getAbsolutePath();
									}
									if (!file.exists()) {
										logger.info("file created"
												+ file.createNewFile());
									}
									OutputStream targetFile = new FileOutputStream(
											file);
									targetFile.write(bdata);
									targetFile.close();

									logger.debug(colName
											+ " is file type : done="
											+ BlobData);
								} else {
									BlobData = new String(bdata);
								}
							}
							row.put(colName, BlobData);
							break;
						}
						case DataFieldTypes.TIMESTAMP:
						case DataFieldTypes.DATE: {

							/**
							 * Timestamp value can be in milliseconds or can
							 * also be in specified date format, if it is
							 * specific date format then column value will be
							 * like,
							 * 
							 * [
							 * 'name':'empDOB','type':'timestampe','dateformat':
							 * ' M M M - D D - Y Y Y HH:MM:SS']
							 * 
							 */
							String dateformat = col.get("dateformat") != null ? col
									.get("dateformat").asText() : null;
							String dateValue = "";
							java.util.Date time = dbrow.getTimestamp(colName);
							if (time != null) {
								if (dateformat == null) {
									dateValue = String.valueOf(time.getTime());
								} else {
									dateValue = com.in10s.rm.utils.DateUtils
											.convertDateTOString(
													time.getTime(), dateformat);
								}
							}
							row.put(colName, dateValue);
							break;
						}

						case DataFieldTypes.SHORT: {
							row.put(colName, dbrow.getShort(colName));
							break;
						}
						case DataFieldTypes.INTEGER: {
							row.put(colName, dbrow.getInt(colName));
							break;
						}
						case DataFieldTypes.LONG: {
							row.put(colName, dbrow.getLong(colName));
							break;
						}
						case DataFieldTypes.FLOAT: {
							row.put(colName, dbrow.getFloat(colName));
							break;
						}
						case DataFieldTypes.DOUBLE: {
							row.put(colName, dbrow.getDouble(colName));
							break;
						}
						case DataFieldTypes.CHAR: {
							row.put(colName, dbrow.getString(colName));
							break;
						}
						default: {
							row.put(colName, dbrow.getString(colName));
						}
						}
					}
					totalRecords++;
					rowSet.add(row);
				}

				/**
				 * Setting value to Plug-in Response Object.
				 */
				pluginResponse.setData(rowSet);
			}
			pluginResponse.setTotalRecords(totalRecords);

		} catch (Exception cause) {
			logger.error("Exception while executing query", cause);
		} finally {
			for (int i = 0; i < fileStreams.size(); i++) {
				try {
					fileStreams.get(i).close();
				} catch (Exception e) {
				}
			}
			long end = System.currentTimeMillis();
			logger.debug("Query Processing time= " + (end - start) + " ms.");
		}
		return pluginResponse;
	}

	@Override
	public boolean hasCacheSupport() {
		return true;
	}

	@Override
	public boolean hasBulkInputSupport() {
		return true;
	}

	@Override
	public boolean hasTotalRecordSupport() {
		return true;
	}

	@Override
	public String description() {
		return "Cassandra Plugin";
	}
	
	@Override
	public boolean isDefault() {
		return true;
	}
	
	
	

}