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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * This class masks (and demasks) WebSocket payload streams
 */
public class MaskingStream extends InputStream {
    /**
     * Length in bytes of the masking key field
     */
    private static final int maskingKeyLength = 4;

    /**
     * The bit mask representing the useful bits of the mask itself
     */
    private static final int maskingKeyBits = maskingKeyLength - 1;

    /**
     * The underlying stream being masked
     */
    private final InputStream input;

    /**
     * The masking key
     */
    private final int[] mask = new int[maskingKeyLength];

    /**
     * The current position in the mask
     */
    private int position = -1;

    /**
     * Masks (or demasks) the given input stream
     * 
     * @param InputStream
     *            the stream to mask
     * @throws IllegalArgumentException
     */
    public MaskingStream(InputStream streamToMask, byte[] maskingKey) {
	input = streamToMask;

	if (maskingKey == null || maskingKey.length != maskingKeyLength) {
	    throw new IllegalArgumentException("invalid masking key");
	}

	// Convert the mask to unsigned integer
	for(int i = 0; i < maskingKeyLength; ++i) {
	    mask[i] = (maskingKey[i] & 0xff);
	}
    }

    /**
     * @see FilterInputStream
     */
    @Override
    public int read() throws IOException {
	// Read the next byte and check for end of stream
	int nextByte = input.read();
	if (nextByte == -1) {
	    return -1;
	}

	// Advance to the next place in the mask
	position = (position + 1) & maskingKeyBits;

	return (nextByte ^ mask[position]);
    }
}
