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

public class ConnectSQLMR extends Thread {

	// server
	private final static int port = 8765;
	private ServerSocket server;
	private Socket socket;
	private InputStream in;
	private OutputStream out;
	private int byteLen = 1024;
	private Buffer sendToUser = new Buffer(byteLen);

	// Column information
	private InputStream columnInfoStream;
	private InputStreamReader columnInfoReader;
	private BufferedReader columnInfoBuffer;
	private String[] parseColumnInfo = new String[4];

	// Row data information
	private InputStream rowDataStream;
	private InputStreamReader rowDataReader;
	private BufferedReader rowDataBuffer;
	private int command;
	private String[] parseRowData = new String[4];// parseRowData[1]="java", parseRowData[2]=SQLMR.jar, parseRowData[3]=statement

	// client's information
	private String user;
	private String password;
	private String database;
	private String encoding;

	public ConnectSQLMR() {
		try {
			server = new ServerSocket(port);
		} catch (IOException e) {
			System.out.println("IOException: " + e.toString());
		}
	}

	public void run() {

		parseRowData[0] = "java";
		parseRowData[1] = "-jar";
		parseRowData[2] = "/var/www/localhost/htdocs/mjHive.jar";

		parseColumnInfo[0] = "java";
		parseColumnInfo[1] = "columnInfo";// -jar
		parseColumnInfo[2] = "";// waiting for junior
		
		byte[] StreamBuf = new byte[byteLen];

		while (true) {
			socket = null;
			try {
				synchronized (server) {
					socket = server.accept();
				}

				socket.setSoTimeout(15000);
				in = new BufferedInputStream(socket.getInputStream());
				out = new BufferedOutputStream(socket.getOutputStream());

				getClientInfo();
			
				int count;

				while ((count = in.read(StreamBuf, 0, 2)) == 2) {//if there have packets
					System.out.println("count: " + count);//XXX: for testing
					

					// TODO : tmp omit at 7/29 11:37 am
					// if(statement.equals(""))
					// break;


					String statement = getStatement();
					
					// parsing column information
					parseColumnInfo[3] = statement;
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
					int tableNameLen;// byte
					String tableName;
					int nameLen;// byte
					String name;
					int colLen;
					String colType;
					int colFlag;// need to care
					byte colDecimals;
					int packetLen;
					int multipacketseq = 0x00;// set field multi-packet
												// sequential number
					String[] columnSplit;

					for (int i = 0; i < columnCount; i++) {
						if ((columnInfoLine = columnInfoBuffer.readLine()) == null)
							throw new NullPointerException();

						columnSplit = columnInfoLine.split("	");
						if (columnSplit.length != 8)
							throw new IOException(
									"column information incorrect!");

						tableNameLen = Integer.parseInt(columnSplit[0]);
						tableName = columnSplit[1];
						nameLen = Integer.parseInt(columnSplit[2]);
						name = columnSplit[3];
						colLen = Integer.parseInt(columnSplit[4]);
						colType = columnSplit[5];
						colFlag = Integer.parseInt(columnSplit[6]);// XXX determining this column is BLOB or not
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
					}
					out.write(sendToUser.getByteBuffer(), 0,
							sendToUser.getPosition());
					out.flush();

					// parsing raw data
					parseRowData[3] = statement;
					Runtime rowDataRT = Runtime.getRuntime();
					Process rowDataExec = rowDataRT.exec(parseRowData);
					rowDataStream = rowDataExec.getInputStream();
					rowDataReader = new InputStreamReader(rowDataStream);
					rowDataBuffer = new BufferedReader(rowDataReader);
					// TODO close stream when we don't want to use again.

					sendToUser.clear();
					String rowDataLine;
					String[] rowDataSplit;
					if ((rowDataLine = rowDataBuffer.readLine()) == null)
						continue;// Doing update or insert
					rowDataSplit = rowDataLine.split("	");
					packetLen = rowDataLine.length() + 2 * rowDataSplit.length
							+ 1;// 3-1 =2 //3 bytes for (sw+int) minus 1 byte
								// for split byte.

					sendToUser.writeByte((byte) (packetLen & 0xff));
					sendToUser.writeByte((byte) ((packetLen >> 8) & 0xff));
					sendToUser.writeByte((byte) ((packetLen >> 16) & 0xff));
					sendToUser.writeByte((byte) 0x00);// discard
														// (multi-packetseq)

					for (int i = 0; i < rowDataSplit.length; i++) {
						sendToUser.writeByte((byte) 0xfc);
						sendToUser.writeInt(rowDataSplit[i].length());
						sendToUser.writeString(rowDataSplit[i], encoding);
					}

					System.out.println("dataline: " + rowDataLine);
					while ((rowDataLine = rowDataBuffer.readLine()) != null) {
						rowDataSplit = rowDataLine.split("	");

						packetLen = rowDataLine.length() + 2
								* rowDataSplit.length + 1;// 3-1 =2 //3 bytes
															// for (sw+int)
															// minus 1 byte for
															// split byte.

						sendToUser.writeByte((byte) (packetLen & 0xff));
						sendToUser.writeByte((byte) ((packetLen >> 8) & 0xff));
						sendToUser.writeByte((byte) ((packetLen >> 16) & 0xff));
						sendToUser.writeByte((byte) 0x00);// discard
															// (multi-packetseq)

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
					System.out.println("chechPointA");
					// for testing
					// in.read(StreamBuf);
					// System.out.println(new String(StreamBuf));
				}
				System.out.println("chechPointB");
				out.close();
				out = null;
				/*----------------------*/

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
			byte[] StreamBuf = new byte[1024];
			
			in.read(StreamBuf, 0, 2);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			encoding = new String(StreamBuf, 0, InfoLen);

			in.read(StreamBuf, 0, 2);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			user = new String(StreamBuf, 0, InfoLen, encoding);

			in.read(StreamBuf, 0, 2);
			InfoLen = readInt(StreamBuf);
			in.read(StreamBuf, 0, InfoLen);
			password = new String(StreamBuf, 0, InfoLen, encoding);

			in.read(StreamBuf, 0, 2);
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

	
	private String getStatement() {
		int statementLen;
		StringBuffer statement = new StringBuffer(byteLen);
		byte[] StreamBuf = new byte[1024];

		statementLen = readInt(StreamBuf);

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
			System.out.println(statement.toString());
			return statement.toString();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private int sqlmrToJavaType(String sqlmrType) {
		if (sqlmrType.equalsIgnoreCase("BIT")) {
			return 16;// FIELD_TYPE_BIT
		} else if (sqlmrType.equalsIgnoreCase("TINYINT")) { //$NON-NLS-1$
			return 1;// FIELD_TYPE_TINY
		} else if (sqlmrType.equalsIgnoreCase("SMALLINT")) { //$NON-NLS-1$
			return 2;// FIELD_TYPE_SHORT
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMINT")) { //$NON-NLS-1$
			return 9;// FIELD_TYPE_INT24
		} else if (sqlmrType.equalsIgnoreCase("INT") || sqlmrType.equalsIgnoreCase("INTEGER")) { //$NON-NLS-1$ //$NON-NLS-2$
			return 3;// FIELD_TYPE_LONG
		} else if (sqlmrType.equalsIgnoreCase("BIGINT")) { //$NON-NLS-1$
			return 8;// FIELD_TYPE_LONGLONG
		} else if (sqlmrType.equalsIgnoreCase("INT24")) { //$NON-NLS-1$
			return 9;// FIELD_TYPE_INT24
		} else if (sqlmrType.equalsIgnoreCase("REAL")) { //$NON-NLS-1$
			return 5;// FIELD_TYPE_DOUBLE
		} else if (sqlmrType.equalsIgnoreCase("FLOAT")) { //$NON-NLS-1$
			return 4;// FIELD_TYPE_FLOAT
		} else if (sqlmrType.equalsIgnoreCase("DECIMAL")) { //$NON-NLS-1$
			return 0;// FIELD_TYPE_DECIMAL
		} else if (sqlmrType.equalsIgnoreCase("NUMERIC")) { //$NON-NLS-1$
			return 0;// FIELD_TYPE_DECIMAL
		} else if (sqlmrType.equalsIgnoreCase("DOUBLE")) { //$NON-NLS-1$
			return 5;// FIELD_TYPE_DOUBLE
		} else if (sqlmrType.equalsIgnoreCase("CHAR")) { //$NON-NLS-1$
			return 254;// FIELD_TYPE_STRING
		} else if (sqlmrType.equalsIgnoreCase("VARCHAR")) { //$NON-NLS-1$
			return 253;// FIELD_TYPE_VAR_STRING
		} else if (sqlmrType.equalsIgnoreCase("DATE")) { //$NON-NLS-1$
			return 10;// FIELD_TYPE_DATE
		} else if (sqlmrType.equalsIgnoreCase("TIME")) { //$NON-NLS-1$
			return 11;// FIELD_TYPE_TIME
		} else if (sqlmrType.equalsIgnoreCase("YEAR")) { //$NON-NLS-1$
			return 13;// FIELD_TYPE_YEAR
		} else if (sqlmrType.equalsIgnoreCase("TIMESTAMP")) { //$NON-NLS-1$
			return 7;// FIELD_TYPE_TIMESTAMP
		} else if (sqlmrType.equalsIgnoreCase("DATETIME")) { //$NON-NLS-1$
			return 12;// FIELD_TYPE_DATETIME
		} else if (sqlmrType.equalsIgnoreCase("TINYBLOB")) { //$NON-NLS-1$
			return java.sql.Types.BINARY;
		} else if (sqlmrType.equalsIgnoreCase("BLOB")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMBLOB")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("LONGBLOB")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARBINARY;
		} else if (sqlmrType.equalsIgnoreCase("TINYTEXT")) { //$NON-NLS-1$
			return java.sql.Types.VARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("TEXT")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("MEDIUMTEXT")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("LONGTEXT")) { //$NON-NLS-1$
			return java.sql.Types.LONGVARCHAR;
		} else if (sqlmrType.equalsIgnoreCase("ENUM")) { //$NON-NLS-1$
			return 247;// FIELD_TYPE_ENUM
		} else if (sqlmrType.equalsIgnoreCase("SET")) { //$NON-NLS-1$
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