/*
 * Copyright (C) 2018 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.indeed.flamdex.dynamic;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.indeed.flamdex.MemoryFlamdex;
import com.indeed.flamdex.api.DocIdStream;
import com.indeed.flamdex.api.FlamdexOutOfMemoryException;
import com.indeed.flamdex.api.FlamdexReader;
import com.indeed.flamdex.api.IntTermIterator;
import com.indeed.flamdex.api.StringTermIterator;
import com.indeed.flamdex.api.TermIterator;
import com.indeed.flamdex.writer.FlamdexDocument;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author michihiko
 */

public class TestMergedIterators {

    @Nonnull
    private List<FlamdexDocument> buildDocuments(final int n) {
        final ImmutableList.Builder<FlamdexDocument> documentsBuilder = ImmutableList.builder();
        for (int i = 0; i < n; ++i) {
            documentsBuilder.add(DynamicFlamdexTestUtils.makeDocument(i));
        }
        return documentsBuilder.build();
    }

    @Nonnull
    private FlamdexReader buildReader(@Nonnull final List<FlamdexDocument> documents) {
        final MemoryFlamdex memoryFlamdex = new MemoryFlamdex();
        for (final FlamdexDocument document : documents) {
            memoryFlamdex.addDocument(document);
        }
        return memoryFlamdex;
    }

    @Nonnull
    private List<FlamdexReader> buildSegments(@Nonnull final List<FlamdexDocument> documents, final int n) {
        final ImmutableList.Builder<FlamdexReader> resultBuilder = ImmutableList.builder();
        final Random random = new Random(0);
        int offset = 0;
        for (int segmentId = 0; segmentId < n; ++segmentId) {
            final int numDocs;
            if (segmentId == (n - 1)) {
                numDocs = documents.size() - offset;
            } else {
                numDocs = Math.min(documents.size() - offset, random.nextInt(documents.size() / n));
            }
            resultBuilder.add(buildReader(documents.subList(offset, offset + numDocs)));
            offset += numDocs;
        }
        return resultBuilder.build();
    }

    private static final int NUM_DOCS = 10000;
    private static final int NUM_SEGMENTS = 20;

    @Test
    public void testMergedIntTermIterator() throws IOException, FlamdexOutOfMemoryException {
        final List<FlamdexDocument> documents = buildDocuments(NUM_DOCS);
        final FlamdexReader expectedReader = buildReader(documents);
        final List<FlamdexReader> segmentReaders = buildSegments(documents, NUM_SEGMENTS);
        for (final String field : expectedReader.getIntFields()) {
            final IntTermIterator expectedIntTermIterator = expectedReader.getIntTermIterator(field);
            final MergedIntTermIterator actualIntTermIterator = new MergedIntTermIterator(
                    FluentIterable.from(segmentReaders).transform(new Function<FlamdexReader, IntTermIterator>() {
                        @Override
                        public IntTermIterator apply(final FlamdexReader flamdexReader) {
                            return flamdexReader.getIntTermIterator(field);
                        }
                    }).toList()
            );

            while (expectedIntTermIterator.next()) {
                assertTrue(actualIntTermIterator.next());
                assertEquals(expectedIntTermIterator.term(), actualIntTermIterator.term());
            }

            for (final int term : new int[]{0, 1, 2, -1, 9999}) {
                expectedIntTermIterator.reset(term);
                actualIntTermIterator.reset(term);
                while (expectedIntTermIterator.next()) {
                    assertTrue(actualIntTermIterator.next());
                    assertEquals(expectedIntTermIterator.term(), actualIntTermIterator.term());
                }
                assertFalse(actualIntTermIterator.next());
            }
            assertFalse(actualIntTermIterator.next());
            expectedIntTermIterator.close();
            actualIntTermIterator.close();
        }
    }

    @Test
    public void testMergedStringTermIterator() throws IOException, FlamdexOutOfMemoryException {
        final List<FlamdexDocument> documents = buildDocuments(NUM_DOCS);
        final FlamdexReader expectedReader = buildReader(documents);
        final List<FlamdexReader> segmentReaders = buildSegments(documents, NUM_SEGMENTS);
        for (final String field : expectedReader.getStringFields()) {
            final StringTermIterator expectedStringTermIterator = expectedReader.getStringTermIterator(field);
            final MergedStringTermIterator actualStringTermIterator = new MergedStringTermIterator(
                    FluentIterable.from(segmentReaders).transform(new Function<FlamdexReader, StringTermIterator>() {
                        @Override
                        public StringTermIterator apply(final FlamdexReader flamdexReader) {
                            return flamdexReader.getStringTermIterator(field);
                        }
                    }).toList()
            );

            while (expectedStringTermIterator.next()) {
                assertTrue(actualStringTermIterator.next());
                assertEquals(expectedStringTermIterator.term(), actualStringTermIterator.term());
            }
            assertFalse(actualStringTermIterator.next());

            for (final String term : new String[]{"0", "1", "2", "-", "9999"}) {
                expectedStringTermIterator.reset(term);
                actualStringTermIterator.reset(term);
                while (expectedStringTermIterator.next()) {
                    assertTrue(actualStringTermIterator.next());
                    assertEquals(expectedStringTermIterator.term(), actualStringTermIterator.term());
                }
                assertFalse(actualStringTermIterator.next());
            }
            expectedStringTermIterator.close();
            actualStringTermIterator.close();
        }
    }

    @SuppressWarnings("JUnitTestMethodWithNoAssertions")
    @Test
    public void testMergedDocIdStream() throws IOException, FlamdexOutOfMemoryException {
        final List<FlamdexDocument> documents = buildDocuments(NUM_DOCS);
        final FlamdexReader expectedReader = buildReader(documents);
        final List<FlamdexReader> segmentReaders = buildSegments(documents, NUM_SEGMENTS);
        final int[] offset = new int[segmentReaders.size() + 1];
        for (int i = 0; i < segmentReaders.size(); i++) {
            offset[i + 1] = offset[i] + segmentReaders.get(i).getNumDocs();
        }

        final DocIdStream expectedDocIdStream = expectedReader.getDocIdStream();
        final MergedDocIdStream actualDocIdStream = new MergedDocIdStream(
                FluentIterable.from(segmentReaders).transform(new Function<FlamdexReader, DocIdStream>() {
                    @Override
                    public DocIdStream apply(final FlamdexReader flamdexReader) {
                        return flamdexReader.getDocIdStream();
                    }
                }).toList(),
                offset
        );

        for (final String field : expectedReader.getIntFields()) {
            final IntTermIterator expectedIntTermIterator = expectedReader.getIntTermIterator(field);
            final MergedIntTermIterator actualIntTermIterator = new MergedIntTermIterator(
                    FluentIterable.from(segmentReaders).transform(new Function<FlamdexReader, IntTermIterator>() {
                        @Override
                        public IntTermIterator apply(final FlamdexReader flamdexReader) {
                            return flamdexReader.getIntTermIterator(field);
                        }
                    }).toList()
            );
            checkEqualityOfDocIdStream(
                    expectedDocIdStream, expectedIntTermIterator,
                    actualDocIdStream, actualIntTermIterator);
        }
        for (final String field : expectedReader.getStringFields()) {
            final StringTermIterator expectedStringTermIterator = expectedReader.getStringTermIterator(field);
            final MergedStringTermIterator actualStringTermIterator = new MergedStringTermIterator(
                    FluentIterable.from(segmentReaders).transform(new Function<FlamdexReader, StringTermIterator>() {
                        @Override
                        public StringTermIterator apply(final FlamdexReader flamdexReader) {
                            return flamdexReader.getStringTermIterator(field);
                        }
                    }).toList()
            );
            checkEqualityOfDocIdStream(
                    expectedDocIdStream, expectedStringTermIterator,
                    actualDocIdStream, actualStringTermIterator);
        }
    }

    private void checkEqualityOfDocIdStream(
            @Nonnull final DocIdStream expectedDocIdStream, @Nonnull final TermIterator expectedStringTermIterator,
            @Nonnull final DocIdStream actualDocIdStream, @Nonnull final TermIterator actualStringTermIterator) {
        final int[] expectedBuf = new int[10];
        final int[] actualBuf = new int[10];
        while (expectedStringTermIterator.next()) {
            assertTrue(actualStringTermIterator.next());

            expectedDocIdStream.reset(expectedStringTermIterator);
            actualDocIdStream.reset(actualStringTermIterator);

            while (true) {
                final int expectedNumFilled = expectedDocIdStream.fillDocIdBuffer(expectedBuf);
                final int actualNumFilled = actualDocIdStream.fillDocIdBuffer(actualBuf);
                assertEquals(expectedNumFilled, actualNumFilled);
                for (int i = 0; i < expectedNumFilled; ++i) {
                    assertEquals(actualBuf[i], expectedBuf[i]);
                }
                if (expectedNumFilled < expectedBuf.length) {
                    break;
                }
            }
        }
        assertFalse(actualStringTermIterator.next());
    }
}
