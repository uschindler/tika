/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.detect;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

/**
 * Content type detection based on magic bytes, i.e. type-specific patterns
 * near the beginning of the document input stream.
 *
 * @since Apache Tika 0.3
 */
public class MagicDetector implements Detector {

    /**
     * The matching media type. Returned by the
     * {@link #detect(InputStream, Metadata)} method if a match is found.
     */
    private final MediaType type;

    /**
     * Length of the comparison window. All the byte arrays here are this long.
     */
    private final int length;

    /**
     * The magic match pattern. If this byte pattern is equal to the
     * possibly bit-masked bytes in the comparison window, then the type
     * detection succeeds and the configured {@link #type} is returned.
     */
    private final byte[] pattern;

    /**
     * Bit mask that is applied to the source bytes in the comparison window
     * before pattern matching. This mask may be <code>null</code>, in which
     * case the source bytes are compared as-is against the configured pattern.
     */
    private final byte[] mask;

    /**
     * Byte buffer that contains the raw input bytes in the current comparison
     * window. This buffer is first filled with the byte sequence starting at
     * the beginning of the configured offset range. Then the buffer is moved
     * forward one byte at a time until a match is found or the entire offset
     * range has been covered.
     */
    private final byte[] sourceBuffer;

    /**
     * The comparison buffer that contains the result of combining the raw
     * input bytes in the current comparison window with the configured
     * {@link #mask bit mask}. If a bit mask is not configured, then this
     * reference points to the {@link #sourceBuffer raw source buffer} to
     * avoid extra logic or copying when doing the pattern match.
     */
    private final byte[] compareBuffer;

    /**
     * First offset (inclusive) of the comparison window within the
     * document input stream. Greater than or equal to zero.
     */
    private final long offsetRangeBegin;

    /**
     * Last offset (inclusive) of the comparison window within the document
     * input stream. Greater than or equal to the
     * {@link #offsetRangeBegin first offset}.
     * <p>
     * Note that this is <em>not</em> the offset of the last byte read from
     * the document stream. Instead, the last window of bytes to be compared
     * starts at this offset.
     */
    private final long offsetRangeEnd;

    /**
     * Creates a detector for input documents that have the exact given byte
     * pattern at the beginning of the document stream.
     *
     * @param type matching media type
     * @param pattern magic match pattern
     */
    public MagicDetector(MediaType type, byte[] pattern) {
        this(type, pattern, 0);
    }

    /**
     * Creates a detector for input documents that have the exact given byte
     * pattern at the given offset of the document stream.
     *
     * @param type matching media type
     * @param pattern magic match pattern
     * @param offset offset of the pattern match
     */
    public MagicDetector(MediaType type, byte[] pattern, long offset) {
        this(type, pattern, null, offset, offset);
    }

    /**
     * Creates a detector for input documents that meet the specified
     * magic match.
     *
     */
    public MagicDetector(
            MediaType type, byte[] pattern, byte[] mask,
            long offsetRangeBegin, long offsetRangeEnd) {
        if (type == null) {
            throw new IllegalArgumentException("Matching media type is null");
        } else if (pattern == null) {
            throw new IllegalArgumentException("Magic match pattern is null");
        } else if (mask != null && mask.length != pattern.length) {
            throw new IllegalArgumentException(
                    "Different pattern and mask lengths: "
                    + pattern.length + " != " + mask.length);
        } else if (offsetRangeBegin < 0
                || offsetRangeEnd < offsetRangeBegin) {
            throw new IllegalArgumentException(
                    "Invalid offset range: ["
                    + offsetRangeBegin + "," + offsetRangeEnd + "]");
        } else {
            this.type = type;
            this.length = pattern.length;
            this.pattern = pattern;
            this.mask = mask;
            this.sourceBuffer = new byte[length];
            if (mask != null) {
                this.compareBuffer = new byte[length];
            } else {
                this.compareBuffer = this.sourceBuffer;
            }
            this.offsetRangeBegin = offsetRangeBegin;
            this.offsetRangeEnd = offsetRangeEnd;
        }
    }

    public MediaType detect(InputStream input, Metadata metadata)
            throws IOException {
        long offset = 0;

        // Skip bytes at the beginning, using skip() or read()
        while (offset < offsetRangeBegin) {
            long n = input.skip(offsetRangeBegin - offset);
            if (n > 0) {
                offset += n;
            } else if (input.read() != -1) {
                offset += 1;
            } else {
                return MediaType.OCTET_STREAM;
            }
        }

        // Fill in the comparison window
        while (offset < offsetRangeBegin + sourceBuffer.length) {
            int i = (int) (offset - offsetRangeBegin);
            int n = input.read(sourceBuffer, i, sourceBuffer.length - i);
            if (n == -1) {
                return MediaType.OCTET_STREAM;
            }
            offset += n;
        }

        // Loop until we've covered the entire offset range
        while (true) {
            // Apply the mask, if any
            if (mask != null) {
                for (int i = 0; i < length; i++) {
                    compareBuffer[i] = (byte) (sourceBuffer[i] & mask[i]);
                }
            }

            if (Arrays.equals(pattern, compareBuffer)) {
                // We have a match, so return the matching media type
                return type;
            } else if (offset < offsetRangeEnd + sourceBuffer.length) {
                // No match, move the comparison window forward and try again
                int c = input.read();
                if (c == -1) {
                    return MediaType.OCTET_STREAM;
                }
                System.arraycopy(sourceBuffer, 1, sourceBuffer, 0, length - 1);
                sourceBuffer[length - 1] = (byte) c;
                offset += 1;
            } else {
                // We have reached the end of the offset range, no match.
                return MediaType.OCTET_STREAM;
            }
        }
    }

}
