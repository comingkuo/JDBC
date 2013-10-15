package com.mysql.jdbc;

import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Types;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;


/*
 *This class is a SQLMR server, client can use this to communicate with SQLMR.
 *In this server, we will receive client's information(name, password, database) and statements.
 *Then we will execute those statements and wrap the result set in packets. 
 * */
public class ConnectSQLMR extends Thread {

	// server
	private final static int timeOut = 15000;
	private ServerSocket server;
	private Socket socket;
	private InputStream in;
	private OutputStream out;
	private int byteLen = 1024;
	private int intTypeLen = 2;
	private Buffer sendToUser = new Buffer(byteLen);


	// client's information
	private String user;
	private String password;
	private String database;
	private String encoding;

	
	/*
	 * 
	 * */
	public ConnectSQLMR() {
		int port = 8765;
		
		while (true) {
			try {
				server = new ServerSocket(port);
				System.out.println("port is: " + port);
				break;
			} catch (IOException e) {
				port += Math.random() * 10;
			}
		}
	}

	public void run() {
		int statementLen;
		String statement;
		byte[] queryLen = new byte[2];

		while (true) {
			socket = null;
			try {
				synchronized (server) {
					socket = server.accept();
				}

				socket.setSoTimeout(timeOut);
				in = new BufferedInputStream(socket.getInputStream());
				out = new BufferedOutputStream(socket.getOutputStream());

				getClientInfo();
			
				int countDIgit;

				while ((countDIgit = in.read(queryLen, 0, intTypeLen)) != -1) {//if there have packets
					System.out.println("count: " + countDIgit);//XXX: for testing
					statementLen = readInt(queryLen);
					statement = getStatement(statementLen);
					
					SendColumnInfo(statement);
					SendResults(statement);
				}
				
				//clear client's information to let others can use this.
				out.close();
				out = null;
				in.close();
				in = null;
				socket.close();
				user = null;
				password = null;
				database = null;

			} catch (IOException ioe) {
				ioe.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}

		}

	}

	public static void main(String args[]) throws Exception {
		(new ConnectSQLMR()).start();
	}

	private int readInt(byte[] b) {
		return (b[0] & 0xff) | ((b[1] & 0xff) << 8);
	}

	private void getClientInfo() {
		try {
			int InfoLen;
			byte[] StreamBuf = new byte[byteLen];
			
			in.read(StreamBuf, 0, intTypeLen);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			encoding = new String(StreamBuf, 0, InfoLen);

			in.read(StreamBuf, 0, intTypeLen);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			user = new String(StreamBuf, 0, InfoLen, encoding);

			in.read(StreamBuf, 0, intTypeLen);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			password = new String(StreamBuf, 0, InfoLen, encoding);

			in.read(StreamBuf, 0, intTypeLen);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			database = new String(StreamBuf, 0, InfoLen, encoding);

			System.out.println("encoding:" + encoding + " user: " + user
					+ " password: " + password + " database: " + database
					+ " DBlen: " + InfoLen);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	
	private String getStatement(int statementLen) {
		int command;
		StringBuffer statement = new StringBuffer(byteLen);
		byte[] StreamBuf = new byte[byteLen];

		try {
			in.read(StreamBuf, 0, 1);
			command = (int) StreamBuf[0];
			statement = new StringBuffer(byteLen);
			// statement
			if (statementLen <= byteLen) {
				in.read(StreamBuf, 0, statementLen);
				statement.append(new String(StreamBuf, 0, statementLen,
						encoding));
			} else {
				while (statementLen > byteLen) {
					statementLen -= byteLen;
					in.read(StreamBuf, 0, byteLen);
					statement
							.append(new String(StreamBuf, 0, byteLen, encoding));
				}
				if (statementLen > 0) {
					in.read(StreamBuf, 0, statementLen);
					statement.append(new String(StreamBuf, 0, statementLen,
							encoding));
				}
			}
			return statement.toString();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private void  SendResults(String statement) {
		// Row data information
		InputStream rowDataStream;
		InputStreamReader rowDataReader;
		BufferedReader rowDataBuffer;
		String[] parseRowData = new String[4];// parseRowData[1]="java", parseRowData[2]=SQLMR.jar, parseRowData[3]=statement
		int packetLen;
		
		parseRowData[0] = "java";
		parseRowData[1] = "-jar";
		parseRowData[2] = "/var/www/localhost/htdocs/mjHive.jar";
		parseRowData[3] = statement;
		
		try {
		// parsing row data
		Runtime rowDataRT = Runtime.getRuntime();
		Process rowDataExec = rowDataRT.exec(parseRowData);
		rowDataStream = rowDataExec.getInputStream();
		rowDataReader = new InputStreamReader(rowDataStream);
		rowDataBuffer = new BufferedReader(rowDataReader);

		sendToUser.clear();
		String rowDataLine;
		String[] rowDataSplit;
			if ((rowDataLine = rowDataBuffer.readLine()) == null) {
				// Doing update or insert
			} else {
				rowDataSplit = rowDataLine.split("	");
				packetLen = rowDataLine.length() + 2 * rowDataSplit.length + 1;// 3-1=2,3bytes for (sw+int) minus 1 byte for split byte.

				sendToUser.writeByte((byte) (packetLen & 0xff));
				sendToUser.writeByte((byte) ((packetLen >> 8) & 0xff));
				sendToUser.writeByte((byte) ((packetLen >> 16) & 0xff));
				sendToUser.writeByte((byte) 0x00);// discard (multi-packetseq)

				for (int i = 0; i < rowDataSplit.length; i++) {
					sendToUser.writeByte((byte) 0xfc);
					sendToUser.writeInt(rowDataSplit[i].length());
					sendToUser.writeString(rowDataSplit[i], encoding);
				}

				System.out.println("dataline: " + rowDataLine);
				while ((rowDataLine = rowDataBuffer.readLine()) != null) {
					rowDataSplit = rowDataLine.split("	");

					packetLen = rowDataLine.length() + 2 * rowDataSplit.length
							+ 1;// 3-1 =2 //3 bytes for (sw+int) minus 1 byte for split byte.

					sendToUser.writeByte((byte) (packetLen & 0xff));
					sendToUser.writeByte((byte) ((packetLen >> 8) & 0xff));
					sendToUser.writeByte((byte) ((packetLen >> 16) & 0xff));
					sendToUser.writeByte((byte) 0x00);// discard (multi-packetseq)

					System.out.println("dataline: " + rowDataLine);
					for (int i = 0; i < rowDataSplit.length; i++) {
						sendToUser.writeByte((byte) 0xfc);
						sendToUser.writeInt(rowDataSplit[i].length());
						sendToUser.writeString(rowDataSplit[i], encoding);
					}
				}
				
				// last packet
				sendToUser.writeByte((byte) 0x01);
				sendToUser.writeByte((byte) 0x00);
				sendToUser.writeByte((byte) 0x00);
				sendToUser.writeByte((byte) 0x00);
				sendToUser.writeByte((byte) 0xfe);
				
				out.write(sendToUser.getByteBuffer(), 0,
						sendToUser.getPosition());
				out.flush();
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	private void SendColumnInfo(String statement) {
		// Column information
		InputStream columnInfoStream;
		InputStreamReader columnInfoReader;
		BufferedReader columnInfoBuffer;
		String[] parseColumnInfo = new String[4];
		
		parseColumnInfo[0] = "java";
		parseColumnInfo[1] = "-cp .:parser.jar";//"columnInfo";
		parseColumnInfo[2] = "mj.SqlParser.SqlParser";// waiting for junior
		parseColumnInfo[3] = statement;

		// parsing column information
		try {
			Runtime columnInfoRT = Runtime.getRuntime();
			Process columnInfoExec = columnInfoRT.exec(parseColumnInfo);
			columnInfoStream = columnInfoExec.getInputStream();
			columnInfoReader = new InputStreamReader(columnInfoStream);
			columnInfoBuffer = new BufferedReader(columnInfoReader);

			String columnInfoLine;

			int columnCount;
			if ((columnInfoLine = columnInfoBuffer.readLine()) != null)
				columnCount = Integer.parseInt(columnInfoLine);
			else
				throw new NullPointerException();

			sendToUser.clear();
			sendToUser.writeByte((byte) 0xfc);
			sendToUser.writeInt(columnCount);

			/* column info */
			int tableNameLen;
			String tableName;
			int nameLen;
			String name;
			int colLen;
			String colType;
			int colFlag;// need to care
			byte colDecimals;
			int packetLen;
			int multipacketseq = 0x00;// set field multi-packet sequential number
			String[] columnSplit;

			for (int i = 0; i < columnCount; i++) {
				if ((columnInfoLine = columnInfoBuffer.readLine()) == null)
					//FIXME : if is insert or update, there will not return anything. 
					throw new NullPointerException();

				columnSplit = columnInfoLine.split("	");
				if (columnSplit.length != 8)
					throw new IOException("column information incorrect!");

				tableNameLen = Integer.parseInt(columnSplit[0]);
				tableName = columnSplit[1];
				nameLen = Integer.parseInt(columnSplit[2]);
				name = columnSplit[3];
				colLen = Integer.parseInt(columnSplit[4]);
				colType = columnSplit[5];
				colFlag = Integer.parseInt(columnSplit[6]);
				colDecimals = (byte) (Integer.parseInt(columnSplit[7]) & 0xff);// Decimal of type double, float and bigdecimal

				/* compute columnInfo packet statementLen */

				/* packet header */
				packetLen = tableName.length() + name.length() + 12;
				sendToUser.writeByte((byte) (packetLen & 0xff));
				sendToUser.writeByte((byte) ((packetLen >> 8) & 0xff));
				sendToUser.writeByte((byte) ((packetLen >> 16) & 0xff));
				sendToUser.writeByte((byte) multipacketseq);

				/* metadata of column */
				sendToUser.writeByte((byte) tableNameLen);
				sendToUser.writeString(tableName, encoding);
				sendToUser.writeByte((byte) nameLen);
				sendToUser.writeString(name, encoding);
				sendToUser.writeByte((byte) 0x02);// set column statementLen
				sendToUser.writeInt(colLen);
				sendToUser.writeByte((byte) 0x02);
				sendToUser.writeInt(sqlmrToJavaType(colType));
				sendToUser.writeByte((byte) 0x02);// We know it's
													// currently 2
				sendToUser.writeInt(colFlag);
				sendToUser.writeByte(colDecimals);
				
				out.write(sendToUser.getByteBuffer(), 0,
						sendToUser.getPosition());
				out.flush();
			}

		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	                               
	
	private int sqlmrToJavaType(String sqlmrType) {
		if (sqlmrType.equalsIgnoreCase("BIT")) {
			return 16;// FIELD_TYPE_BIT
		} else if (sqlmrType.equalsIgnoreCase("TINYINT")) {
			return 1;// FIELD_TYPE_TINY
		} else if (sqlmrType.equalsIgnoreCase("SMALLINT")) {
			return 2;// FIELD_TYPE_SHORT
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMINT")) {
			return 9;// FIELD_TYPE_INT24
		} else if (sqlmrType.equalsIgnoreCase("INT") || sqlmrType.equalsIgnoreCase("INTEGER")) {
			return 3;// FIELD_TYPE_LONG
		} else if (sqlmrType.equalsIgnoreCase("BIGINT")) {
			return 8;// FIELD_TYPE_LONGLONG
		} else if (sqlmrType.equalsIgnoreCase("INT24")) {
			return 9;// FIELD_TYPE_INT24
		} else if (sqlmrType.equalsIgnoreCase("REAL")) {
			return 5;// FIELD_TYPE_DOUBLE
		} else if (sqlmrType.equalsIgnoreCase("FLOAT")) {
			return 4;// FIELD_TYPE_FLOAT
		} else if (sqlmrType.equalsIgnoreCase("DECIMAL")) {
			return 0;// FIELD_TYPE_DECIMAL
		} else if (sqlmrType.equalsIgnoreCase("NUMERIC")) {
			return 0;// FIELD_TYPE_DECIMAL
		} else if (sqlmrType.equalsIgnoreCase("DOUBLE")) {
			return 5;// FIELD_TYPE_DOUBLE
		} else if (sqlmrType.equalsIgnoreCase("CHAR")) {
			return 254;// FIELD_TYPE_STRING
		} else if (sqlmrType.equalsIgnoreCase("VARCHAR")) {
			return 253;// FIELD_TYPE_VAR_STRING
		} else if (sqlmrType.equalsIgnoreCase("DATE")) {
			return 10;// FIELD_TYPE_DATE
		} else if (sqlmrType.equalsIgnoreCase("TIME")) {
			return 11;// FIELD_TYPE_TIME
		} else if (sqlmrType.equalsIgnoreCase("YEAR")) {
			return 13;// FIELD_TYPE_YEAR
		} else if (sqlmrType.equalsIgnoreCase("TIMESTAMP")) {
			return 7;// FIELD_TYPE_TIMESTAMP
		} else if (sqlmrType.equalsIgnoreCase("DATETIME")) {
			return 12;// FIELD_TYPE_DATETIME
		} else if (sqlmrType.equalsIgnoreCase("TINYBLOB")) {
			return java.sql.Types.BINARY;
		} else if (sqlmrType.equalsIgnoreCase("BLOB")) {
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMBLOB")) {
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("LONGBLOB")) {
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("TINYTEXT")) {
			return java.sql.Types.VARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("TEXT")) {
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMTEXT")) {
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("LONGTEXT")) {
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("ENUM")) {
			return 247;// FIELD_TYPE_ENUM
		} else if (sqlmrType.equalsIgnoreCase("SET")) {
			return 248;// FIELD_TYPE_SET
		} else if (sqlmrType.equalsIgnoreCase("GEOMETRY")) {
			return 255;// FIELD_TYPE_GEOMETRY
		} else if (sqlmrType.equalsIgnoreCase("BINARY")) {
			return Types.BINARY; // no concrete type on the wire
		} else if (sqlmrType.equalsIgnoreCase("VARBINARY")) {
			return Types.VARBINARY; // no concrete type on the wire
		} else if (sqlmrType.equalsIgnoreCase("BIT")) {
			return 16;// FIELD_TYPE_BIT
		} else {
			return 15;// FIELD_TYPE_VARCHAR
		}
	}

}