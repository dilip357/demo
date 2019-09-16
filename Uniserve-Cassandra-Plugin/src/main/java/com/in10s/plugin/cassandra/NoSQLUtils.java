package com.in10s.plugin.cassandra;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.LocalDate;
import com.datastax.driver.core.PreparedStatement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.in10s.platform.configuration.ApplicationConfiguration;
import com.in10s.rm.logger.LogService;
import com.in10s.rm.pojos.DataFieldTypes;

public class NoSQLUtils {

	public static BoundStatement bindPreparedStatementValues(
			PreparedStatement preparedStatement, ObjectNode params,
			ArrayNode placeholderCols, LogService logger,
			List<FileInputStream> fileStreams, ApplicationConfiguration configs) {
		BoundStatement boundStatement = preparedStatement.bind();

		try {
			Iterator<JsonNode> placeholderList = placeholderCols.iterator();
			logger.info("params " + params);
			int index = 1;
			while (placeholderList.hasNext()) {
				ObjectNode col = (ObjectNode) placeholderList.next();

				/**
				 * Getting column details like name and type of column. Example
				 * node: {'name':'deptId','type':'integer'}
				 */
				String colName = col.get("name").asText();
				String colType = col.get("type").asText().toLowerCase();

				/**
				 * Getting column value of specific column from passed where
				 * column values.
				 */
				JsonNode jsonValue = params.get(colName);

				String colValue = "";
				if (jsonValue.isTextual()) {
					colValue = jsonValue.textValue();
				} else {
					colValue = jsonValue.toString();
				}
				logger.debug("Column Name=" + colName + ", Column Type="
						+ colType + " , Column Value=" + colValue);

				/**
				 * Setting null value to prepared statement if the column value
				 * is null or empty.
				 */
				if (colValue == null
						|| (colValue != null && colValue.length() == 0)
						|| (colValue != null && colValue
								.equalsIgnoreCase("null"))) {
					logger.info("Setting null value to Column Name=" + colName);
					// setNull(preparedStatement, colType, index++);
				} else {

					/**
					 * Setting value to prepared statement based on column type.
					 */
					switch (colType) {
					case DataFieldTypes.CLOB: {
						boundStatement.setString(index++, colValue);
						break;
					}
					case DataFieldTypes.BLOB: {

						/**
						 * Blob data can be passed as String or can also be a
						 * file. If it is a file then the column details will be
						 * like
						 * 
						 * [{'name':'empResume','type':'blob','isFile':true}]
						 * 
						 * and Column value will be like
						 * 
						 * {'empResume':'E:/ResumeRepository/employee123.docs/'}
						 * 
						 */

						boolean isFile = col.get("isFile") != null ? col.get(
								"isFile").asBoolean() : true;
						if (!isFile) {
							ByteBuffer byteBuffer = ByteBuffer.wrap(colValue
									.getBytes("UTF-8"));
							boundStatement.setBytes(colName, byteBuffer);
						} else {
							FileChannel fc = null;
							try {
								File file = new File(colValue);
								if (file.exists()) {
									FileInputStream fis = new FileInputStream(
											file);
									fc = fis.getChannel();
									ByteBuffer byteBuffer = ByteBuffer
											.allocate((int) fc.size());
									fc.read(byteBuffer);
									byteBuffer.rewind();
									boundStatement
											.setBytes(colName, byteBuffer);
									logger.debug(colName
											+ " is file type : done");
									fileStreams.add(fis);
								}
							} catch (IOException cause) {
								logger.error("", cause);
							} finally {
								if (fc != null) {
									fc.close();
								}
							}
						}
						break;
					}
					case DataFieldTypes.TIMESTAMP: {

						/**
						 * TIMESTAMP data can be passed in milliseconds or can
						 * also be a date like Jan-26-1992 12:46:40 . If it is a
						 * date then the column details will be like
						 * 
						 * [{
						 * 'name':'empDOB','type':'timestamp','dateformat':'MMM-
						 * D D - Y Y Y Y HH:MM:SS'}]
						 * 
						 * and Column value will be like
						 * 
						 * {'empDOB':'Jan-26-1992 12:46:40'}
						 * 
						 */
						String dateformat = col.get("dateformat") != null ? col
								.get("dateformat").asText() : null;
						java.sql.Timestamp timestamp = null;
						if (dateformat == null) {
							dateformat = configs.get("default.dateformat");
						}
						try {
							timestamp = com.in10s.rm.utils.DateUtils
									.parseTimestamp(dateformat, colValue);
						} catch (Exception e) {
							timestamp = new Timestamp(Long.parseLong(colValue));

						}

						boundStatement.setTimestamp(colName, timestamp);
						break;
					}
					case DataFieldTypes.DATE: {

						/**
						 * Date data can be passed in milliseconds or can also
						 * be a date like Jan-26-1992. If it is a date then the
						 * column details will be like
						 * 
						 * [{ 'name':'empJoinDate','type':'date','dateformat': '
						 * M M M - D D - Y Y Y Y ' } ]
						 * 
						 * and Column value will be like
						 * 
						 * {'empJoinDate':'Jan-26-2015'}
						 * 
						 */
						LocalDate date = null;
						String dateformat = col.get("dateformat") != null ? col
								.get("dateformat").asText() : null;
						if (dateformat == null) {
							dateformat = configs.get("default.dateformat");
						}
						logger.info("default.dateformat = " + dateformat);
						try {
							SimpleDateFormat parser = new SimpleDateFormat(
									dateformat);
							date = LocalDate.fromMillisSinceEpoch(parser.parse(
									colValue).getTime());

						} catch (Exception cause) {
							date = LocalDate.fromMillisSinceEpoch(Long
									.parseLong(colValue));
						}
						boundStatement.setDate(colName, date);
						break;
					}
					case DataFieldTypes.SHORT: {
						boundStatement.setShort(colName,
								Short.parseShort(colValue));
						break;
					}
					case DataFieldTypes.INTEGER: {
						boundStatement.setInt(colName,
								Integer.parseInt(colValue));
						break;
					}
					case DataFieldTypes.LONG: {
						boundStatement.setLong(colName,
								Long.parseLong(colValue));
						break;
					}
					case DataFieldTypes.FLOAT: {
						boundStatement.setFloat(colName,
								Float.parseFloat(colValue));
						break;
					}
					case DataFieldTypes.DOUBLE: {
						boundStatement.setDouble(colName,
								Double.parseDouble(colValue));
						break;
					}
					default: {
						boundStatement.setString(colName, colValue);
					}
					}
				}
			}
		} catch (Exception cause) {
			logger.error("Invalid column index =", cause);
		}
		return boundStatement;
	}

	public static String getUSNDataTypeFromNoSQLDataType(int nosqltype) {
		if (nosqltype == 1) {
			return "char";
		} else if (nosqltype == 19 || nosqltype == 20) {
			return "short";
		} else if (nosqltype == 9) {
			return "integer";
		} else if (nosqltype == 14 || nosqltype == 2) {
			return "long";
		} else if (nosqltype == 6 || nosqltype == 8) {
			return "float";
		} else if (nosqltype == 7) {
			return "double";
		} else if (nosqltype == 4) {
			return "boolean";
		} else if (nosqltype == 3) {
			return "blob";
		} else if (nosqltype == 11) {
			return "timestamp";
		} else if (nosqltype == 17) {
			return "date";
		} else {
			return "string";
		}
	}
}
