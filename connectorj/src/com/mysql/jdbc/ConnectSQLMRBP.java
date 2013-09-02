package com.mysql.jdbc;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;


public class ConnectSQLMRBP extends Thread{
  private final static int port = 8765;
  private ServerSocket server;
  
  public ConnectSQLMRBP() {
    try {
      server = new ServerSocket(port);
    } catch (IOException e){
      System.out.println("IOException: " + e.toString());
    }
  }

  public void run() {
    Socket socket;
    InputStream in;
    OutputStream out;

    //SQLMR IO
    InputStream SQLResult;
    InputStreamReader resultReader;
    BufferedReader ResultBr;

    int byteLen = 1024;
    byte[] b = new byte[byteLen];
  //TODO : using Buffer packet;
    int length;
    int command;

    //read login info(user,pw,database)
    String user;
    String password;
    String database;
    StringBuffer statement;
    String encoding;
    String[] parse = new String[4];//parse[1]="java", parse[2]=SQLMR.jar, parse[3]=statement
    
    parse[0] = "java";
    //parse[1] = "-jar";
    parse[1] = "helloworld";
    //parse[2] = "/var/www/localhost/htdocs/mjHive.jar";
    parse[2] = "";
      
      
      while(true) {
    	socket = null;
        try {
            synchronized (server) {
              socket = server.accept();
            }
        socket.setSoTimeout(15000);
        in = new BufferedInputStream(socket.getInputStream());
    	out = new BufferedOutputStream(socket.getOutputStream());
    	//doHandshake
    	in.read(b, 0, 2);
    	length = readInt(b);
    	in.read(b, 0, length);
    	encoding = new String(b, 0, length);
    	
        in.read(b, 0, 2);
        length = readInt(b);        
        in.read(b, 0, length);
        user = new String(b, 0, length, encoding);
        
        in.read(b, 0, 2);
        length = readInt(b);        
        in.read(b, 0, length);
        password = new String(b, 0, length, encoding);
        
        in.read(b, 0, 2);
        length = readInt(b);        
        in.read(b, 0, length);
        database = new String(b, 0, length, encoding);
        
        System.out.println("encoding:" + encoding +" user: " + user + " password: " + password + " database: " + database + " DBlen: " + length );
        //---------------
        
        int count;
        while((count = in.read(b, 0, 2)) == 2) {/*position unset*/
        	System.out.println("count: " + count);
        	//statement length
        	length = readInt(b);
        	System.out.println("length b: " + length);
        	/*if(length == 0)
        	{
        		length = in.read(b);
        		System.out.println("b:" + new String(b) + " length: " + length);
        	}*/
        	//command
        	in.read(b, 0, 1);
        	command = (int) b[0];
        	statement = new StringBuffer(1024);
        	//statement
        	//TODO : need to fix, if length > b.length will have IndexOutOfBoundException
        	if(length <= byteLen) {
        		in.read(b, 0, length);
        		statement.append(new String(b, 0, length, encoding));
        	} else {
        		//tmpLen = length;
        		while(length > byteLen) {
        			length -= byteLen;
        			in.read(b, 0, byteLen);
        			statement.append(new String(b, 0, byteLen, encoding));
        		}
        		if(length > 0 ) {
            		in.read(b, 0, length);
            		statement.append(new String(b, 0, length, encoding));	
        		}
        	}
        	//TODO : tmp omit at 7/29 11:37 am
        	//if(statement.equals(""))
        		//break;
        	
        	System.out.println("command: " + command + " statement: " + statement);
        	
        	parse[3] = statement.toString();
        	Runtime rt = Runtime.getRuntime();
        	Process proc = rt.exec(parse);
        	SQLResult = proc.getInputStream();
        	resultReader = new InputStreamReader(SQLResult);
        	ResultBr = new BufferedReader(resultReader);
        	
        	String line = null;
        	while((line=ResultBr.readLine()) != null)
        		System.out.println("result line: " + line);
        	int exitVal = proc.waitFor();
        	System.out.println("Process exitValue: " + exitVal);
        	//execSQL()/* Runtime rt */
        

 
        	Buffer sendToUser = new Buffer(1024);

    	
        	//set field multi-packetseq
        	int multipacketseq = 0x00;
        


       
//TODO : testing and will delete it

        	sendToUser.clear();
        //column count information
        	sendToUser.writeByte((byte) 0xfc);
        	sendToUser.writeInt(1);
        
/* single field packet format */      
        	//set field packet length,  23
        	sendToUser.writeByte((byte) 0x17);
        	sendToUser.writeByte((byte) 0x00); 
        	sendToUser.writeByte((byte) 0x00);
        

        	sendToUser.writeByte((byte) multipacketseq++);
        
        	//set table name start,length
        	sendToUser.writeByte((byte) 0x06);
        	sendToUser.writeString("lolllT", encoding);
        	//set name start, length
        	sendToUser.writeByte((byte) 0x06);
        	sendToUser.writeString("dadadN", encoding);
        	//set colLength
        	sendToUser.writeByte((byte) 0x02);
        	sendToUser.writeInt(6);// Internal length of the field
        	//set colType
        	sendToUser.writeByte((byte) 0x02);
        	sendToUser.writeInt(4);//Using MysqlDefs.MysqlToJavaType(String)
        	//packet.readByte(); // We know it's currently 2
        	sendToUser.writeByte((byte) 0x02);
        	//set colFlag = (short) packet.readInt();
        	sendToUser.writeByte((byte) 0x00);
        	//set colDecimals = (packet.readByte() & 0xff);
        	sendToUser.writeByte((byte) 0x85);//133,the number are used in testing, and have no any meaning
        
        	
        	out.write(sendToUser.getByteBuffer(), 0, sendToUser.getPosition());
        	out.flush();
        	
        	sendToUser.clear();
/* Rowdata set */
        	//set header
        	sendToUser.writeByte((byte) 0x08);
        	sendToUser.writeByte((byte) 0x00); 
        	sendToUser.writeByte((byte) 0x00);
        	sendToUser.writeByte((byte) 0x00); // discard (multi-packetseq)
  /* for columnCount */
        	//sw = this.mysqlInput.read() & 0xff;
        	sendToUser.writeByte((byte) 0xfc);
        	sendToUser.writeInt(5);
        	//rowData content
        	sendToUser.writeString("12345", encoding);
  /* for loop end */
        
/* 2nd rowset */
        	//set header
        	sendToUser.writeByte((byte) 0x06);
        	sendToUser.writeByte((byte) 0x00); 
        	sendToUser.writeByte((byte) 0x00);
        	sendToUser.writeByte((byte) 0x00); // discard (multi-packetseq)
  /* for columnCount */
        	//sw = this.mysqlInput.read() & 0xff;
        	sendToUser.writeByte((byte) 0xfc);
        	sendToUser.writeInt(3);
        	//rowData content
        	sendToUser.writeString("345", encoding);
  /* for loop end */      
        
 /* last packet */
        	sendToUser.writeByte((byte) 0x01);
        	sendToUser.writeByte((byte) 0x00); 
        	sendToUser.writeByte((byte) 0x00);
        	sendToUser.writeByte((byte) 0x00); // discard (multi-packetseq)
        	//sw = this.mysqlInput.read() & 0xff;
        	sendToUser.writeByte((byte) 0xfe);        
        
       // sendToUser.writeString("I'm new Server hello!");

       // out = new BufferedOutputStream(socket.getOutputStream());
        
        	out.write(sendToUser.getByteBuffer(), 0, sendToUser.getPosition());
        //out.write("I'm server hello".getBytes());
        
        
        	out.flush();
        }
        out.close();
        out = null;
        /*----------------------*/

        
        in.close();
        in = null;
        socket.close();
        
        user = null;
        password = null;
        database = null;
        statement = null;
        
        
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

      
    }


  }

  public static void main(String args[]) throws Exception {
    (new ConnectSQLMRBP()).start();
  }
  
  final int readInt(byte[] b) {
	return (b[0] & 0xff) | ((b[1] & 0xff) << 8);
  }
  

}