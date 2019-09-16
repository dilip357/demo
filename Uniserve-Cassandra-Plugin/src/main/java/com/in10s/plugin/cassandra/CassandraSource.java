package com.in10s.plugin.cassandra;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.in10s.rm.logger.LogService;
import com.in10s.rm.services.CoreService;
import com.in10s.rm.services.Resource;
import com.in10s.rm.utils.DatasourceService;
import com.in10s.rm.utils.JSONUtils;

@Component(immediate = true)
@Provides(specifications = { Resource.class, CassandraSource.class,
		CoreService.class })
@Instantiate
public class CassandraSource implements Resource {

	Map<String, Map<String, Object>> cassandraSources = new HashMap<String, Map<String, Object>>();

	ObjectNode currDsValues = null;

	public ObjectNode getCurrDsValues() {
		return currDsValues;
	}

	public void setCurrDsValues(ObjectNode currDsValues) {
		this.currDsValues = currDsValues;
	}

	@Requires
	LogService logger;

	@Requires
	DatasourceService dsService;

	@Override
	public String getName() {
		return "CassandraSource";
	}

	@Override
	public String getVersion() {
		return "1.0.0";
	}

	@Override
	public String url() {
		return "/pluginmanager/CassandraPlugin/1.0.1/templates/CassandraSource.thl.html";
	}

	public Session getSession(String dsId) {
		logger.info("inside getSession111");
		if (cassandraSources.containsKey(dsId)) {
			return (Session) cassandraSources.get(dsId).get("session");
		} else {
			JsonNode dsDetails = dsService.getDataSourceById(dsId);
			logger.debug("dsDetails=={0}", dsDetails);
			try {
				ObjectNode details = (ObjectNode) JSONUtils.getMapper().readTree(dsDetails.get("DS_VALUE").asText());
				setCurrDsValues(details);
				logger.info("Creating new session for datasourceId:{0}", dsId);
				String contactPoint = details.get("contactPoint").asText();
				int hostPort = details.get("hostPort").asInt();
				String keyspace = details.get("keyspace").asText();
				Cluster cluster = Cluster.builder()
						.addContactPoint(contactPoint).withPort(hostPort)
						.build();
				logger.info("Cluster created.");
				Session session = cluster.connect(keyspace);
				logger.info("Session created.");
				Map<String, Object> details1 = new HashMap<String, Object>();
				details1.put("session", session);
				details1.put("cluster", cluster);
				cassandraSources.put(dsId, details1);
				return session;
			} catch (IOException e) {
				logger.error("", e);
				return null;
			}

		}
	}

	@Validate
	private void start() {
		logger.info("CassandraSource has been started.");
	}

	@Invalidate
	private void stop() {
		Iterator<String> it = cassandraSources.keySet().iterator();
		while (it.hasNext()) {
			String dsId = it.next();
			Map<String, Object> map = cassandraSources.get(dsId);
			if (map != null) {
				Session session = (Session) map.get("session");
				if (session != null) {
					try {
						session.close();
						logger.info("Session has been closed.");
					} catch (Exception e) {

					}
				}
				Cluster cluster = (Cluster) map.get("cluster");
				if (cluster != null) {
					try {
						cluster.close();
						logger.info("Cluster has been closed.");
					} catch (Exception e) {

					}
				}
			}
		}
		logger.info("CassandraSource has been stopped.");
	}

	public JsonNode getTableList(String dsId) {
		logger.info("Inside getTableList");
		logger.debug("dsId={0}", dsId);
		ArrayNode tables = JSONUtils.createArrayNode();
		Session session = getSession(dsId);
		if (session != null) {
			try {
				ObjectNode dsValues = getCurrDsValues();
				Iterator<TableMetadata> tablesItr = session.getCluster()
						.getMetadata()
						.getKeyspace(dsValues.get("keyspace").asText())
						.getTables().iterator();
				while (tablesItr.hasNext()) {
					TableMetadata tmd = tablesItr.next();
					ObjectNode table = JSONUtils.createObjectNode();
					table.put("name", tmd.getName());
					tables.add(table);
				}
			} catch (Exception cause) {
				logger.error("Error in getTableList==", cause);
			}
		}
		logger.debug("returning tables={0}", tables);
		return tables;
	}

	public JsonNode getColumnList(String dsId, ArrayNode tableNames) {
		logger.info("Inside getColumnList");
		logger.debug("dsId={0},tableNames={1}", dsId, tableNames);
		ArrayNode columns = JSONUtils.createArrayNode();
		if (tableNames.size() > 0) {
			Session session = getSession(dsId);
			if (session != null) {
				try {
					ObjectNode dsValues = getCurrDsValues();
					Iterator<TableMetadata> tablesItr = session.getCluster()
							.getMetadata()
							.getKeyspace(dsValues.get("keyspace").asText())
							.getTables().iterator();
					while (tablesItr.hasNext()) {
						TableMetadata tmd = tablesItr.next();
						String tableName = tmd.getName();
						for (JsonNode name : tableNames) {
							if (tableName.equals(name.asText())) {
								Iterator<ColumnMetadata> columnItr = tmd
										.getColumns().iterator();
								while (columnItr.hasNext()) {
									ColumnMetadata cmd = columnItr.next();
									ObjectNode column = JSONUtils
											.createObjectNode();
									DataType dt = cmd.getType();
									column.put("name", cmd.getName());
									column.put("type", NoSQLUtils
											.getUSNDataTypeFromNoSQLDataType(dt
													.getName().ordinal()));
									columns.add(column);
								}
							}
						}
					}
				} catch (Exception cause) {
					logger.error("Error in getColumnList==", cause);
				}
			}
		}
		logger.debug("returning columns={0}", columns);
		return columns;
	}
	
	public JsonNode getColumnListWithTable(String dsId, ArrayNode tableNames) {
		logger.info("Inside getColumnListWithTable");
		logger.debug("dsId={0},tableNames={1}", dsId, tableNames);
		ArrayNode result = JSONUtils.createArrayNode();
		if (tableNames.size() > 0) {
			Session session = getSession(dsId);
			if (session != null) {
				try {
					ObjectNode dsValues = getCurrDsValues();
					Iterator<TableMetadata> tablesItr = session.getCluster()
							.getMetadata()
							.getKeyspace(dsValues.get("keyspace").asText())
							.getTables().iterator();
					while (tablesItr.hasNext()) {
						TableMetadata tmd = tablesItr.next();
						String tableName = tmd.getName();
						for (JsonNode name : tableNames) {
							if (tableName.equals(name.asText())) {
								Iterator<ColumnMetadata> columnItr = tmd
										.getColumns().iterator();
								ArrayNode columns = JSONUtils.createArrayNode();
								while (columnItr.hasNext()) {
									ColumnMetadata cmd = columnItr.next();
									ObjectNode column = JSONUtils
											.createObjectNode();
									DataType dt = cmd.getType();
									column.put("name", cmd.getName());
									column.put("type", NoSQLUtils
											.getUSNDataTypeFromNoSQLDataType(dt
													.getName().ordinal()));
									columns.add(column);
								}
								ObjectNode table = JSONUtils.createObjectNode();
								table.put("tableName", name.asText());
								table.set("columns", columns);
								result.add(table);
							}
						}
					}
				} catch (Exception cause) {
					logger.error("Error in getColumnListWithTable==", cause);
				}
			}
		}
		logger.debug("returning result={0}", result);
		return result;
	}

}
