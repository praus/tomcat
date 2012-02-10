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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.catalina.websocket.StreamInbound;
import org.apache.catalina.websocket.WebSocketServlet;
import org.apache.tomcat.util.buf.B2CConverter;

public class EchoStream extends WebSocketServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected StreamInbound createWebSocketInbound() {
		return new EchoStreamInbound();
	}

	private final class EchoStreamInbound extends StreamInbound implements
			Runnable {

	    private static final int queueSize = 1;
	    
		private final BlockingQueue<Conduit> queue
		        = new ArrayBlockingQueue<Conduit>(queueSize);

		protected EchoStreamInbound() {
			Thread thread = new Thread(this);
			thread.start();
		}

		@Override
		protected void onBinaryData(final InputStream in) throws IOException {
			try {
				PipedInputStream pipe = new PipedInputStream();
				OutputStream out = new PipedOutputStream(pipe);

				queue.put(new Conduit(in, out));
				
				getStreamOutbound().writeBinaryStream(pipe);

			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		@Override
		protected void onTextData(final Reader reader) throws IOException {
			try {
			    Charset charset = B2CConverter.UTF_8;
				PipedInputStream pipeStream = new PipedInputStream();
				OutputStream out = new PipedOutputStream(pipeStream);
				Writer writer = new OutputStreamWriter(out, charset);
				Reader pipe = new InputStreamReader(pipeStream, charset);

				queue.put(new Conduit(reader, writer));

				getStreamOutbound().writeTextStream(pipe);

			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		public void run() {
			while (true) {
				try {
					Conduit conduit = queue.take();
					
					if(conduit.isText()) {
					    handleTextFrame(conduit);
					}
					else {
					    handleBinaryFrame(conduit);
					}
				} catch (IOException e) {
					System.err.println("websocket stream io exception");
					e.printStackTrace();
					break;
				} catch (InterruptedException e) {
					System.err.println("websocket thread take() interrupted");
					break;
				}
			}
		}
		
		private void handleTextFrame(Conduit conduit) throws IOException
		{
		    Reader reader = conduit.getReader();
            Writer writer = conduit.getWriter();

            int i;
            while ((i = reader.read()) != -1) {
                System.out.print((char) i);
                writer.write(i);
            }

            System.out.println();
            writer.close();
		}
		
		private void handleBinaryFrame(Conduit conduit) throws IOException
        {
            InputStream  input  = conduit.getInputStream();
            OutputStream output = conduit.getOutputStream();

            int i;
            while ((i = input.read()) != -1) {
                System.out.print((char) i);
                output.write(i);
            }

            System.out.println();
            output.close();
        }
	}

	private static final class Conduit {
        // Text/binary flag
        private final boolean isText;
	    
	    // Binary
		private final InputStream inputStream;
		private final OutputStream outputStream;
		
		// Text
		private final Reader reader;
		private final Writer writer;

		// Binary
		protected Conduit(InputStream incoming, OutputStream outgoing) {
			inputStream  = incoming;
			outputStream = outgoing;
            reader = null;
            writer = null;
			isText = false;
		}
		
		// Text
		protected Conduit(Reader incoming, Writer outgoing) {
            inputStream  = null;
            outputStream = null;
            reader = incoming;
            writer = outgoing;
            isText = true;
        }
		
        protected boolean isText() {
            return isText;
        }
        
		protected InputStream getInputStream() {
			return inputStream;
		}

		protected OutputStream getOutputStream() {
			return outputStream;
		}
		
		protected Reader getReader() {
            return reader;
        }

        protected Writer getWriter() {
            return writer;
        }
	}
}
