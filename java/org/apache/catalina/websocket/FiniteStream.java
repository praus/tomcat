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
package org.apache.catalina.websocket;

import java.io.IOException;
import java.io.InputStream;

/**
 * This class wraps an input stream with known finite length (64-bit for now)
 */
public class FiniteStream extends InputStream {
    /**
     * The underlying stream
     */
    private final InputStream input;

    /**
     * Remaining number of bytes in the input stream
     */
    private long remaining;

    /**
     * Wraps a given stream with known length
     * 
     * @param InputStream
     *            the stream to wrap
     * @parma long the length in bytes to read from the stream
     */
    public FiniteStream(InputStream stream, long length) {
	input = stream;

	remaining = length;
    }

    /**
     * @returns the number of bytes in the stream still to read
     */
    public long remaining() {
	return remaining;
    }

    /**
     * @see InputStream
     */
    @Override
    public int read() throws IOException {
	if (remaining > 0) {
	    
	    remaining = remaining - 1;

	    return input.read();
	    
	} else {
	    return -1;
	}
    }
}
