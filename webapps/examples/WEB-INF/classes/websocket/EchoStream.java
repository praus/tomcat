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

import org.apache.catalina.websocket.WebSocketConnection;
import org.apache.catalina.websocket.WebSocketFrame;
import org.apache.catalina.websocket.WebSocketServlet;

public class EchoStream extends WebSocketServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected WebSocketConnection createWebSocketConnection() {
        // Create a connection that echoes back anything it receives
        return new EchoStreamConnection();
    }

    private final class EchoStreamConnection extends WebSocketConnection {
//        @Override
//        protected void onOpen() {
//            
//        }
        
        @Override
        protected void onMessage(WebSocketFrame frame) throws IOException {
            // Echo the frame right back
            send(frame);
        }
        
//        @Override
//        protected void onFinalFragment() {
//            
//        }
//        
//        @Override
//        protected void onClose() {
//            
//        }
//        
//        @Override
//        protected void onError() {
//            
//        }
    }
}
