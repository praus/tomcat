/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package websocket;

import java.io.IOException;
import java.io.Reader;

import org.apache.catalina.websocket.WebSocketConnection;
import org.apache.catalina.websocket.WebSocketFrame;
import org.apache.catalina.websocket.WebSocketServlet;

public class EchoMessage extends WebSocketServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected WebSocketConnection createWebSocketConnection() {
	    // Create a connection that prints out whatever messages it receives
		return new PrintMessageConnection();
	}

	private final class PrintMessageConnection extends WebSocketConnection
	{
        @Override
        protected void onMessage(WebSocketFrame frame) throws IOException {
            Reader payload = frame.getPayloadReader();

            System.out.print("<message opcode=\"" + frame.getOpcode() + "\">");
            
            int i;
            while((i = payload.read()) != -1)
            {
                System.out.print((char) i);
            }
        }
   
        @Override
        protected void onFinalFragment() {
            System.out.println("</message>");
        }
	}
}
