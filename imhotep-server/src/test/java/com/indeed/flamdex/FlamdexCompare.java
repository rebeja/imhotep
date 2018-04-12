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
 package com.indeed.flamdex;

import com.google.common.base.Function;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.indeed.flamdex.api.FlamdexReader;
import com.indeed.flamdex.utils.FlamdexReinverter;
import com.indeed.flamdex.writer.FlamdexDocument;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author jsgroth
 */
class FlamdexCompare {
    private FlamdexCompare() {
    }

    static boolean unorderedEquals(final FlamdexReader r1, final FlamdexReader r2) {
        return unorderedEquals(FlamdexReinverter.reinvertInMemory(r1), FlamdexReinverter.reinvertInMemory(r2));
    }

    static boolean unorderedEquals(final List<FlamdexDocument> l1, final List<FlamdexDocument> l2) {
        if (l1.size() != l2.size()) {
            return false;
        }

        final Multiset<FlamdexDocumentWrapper> s1 = HashMultiset.create(Lists.transform(l1, new Function<FlamdexDocument, FlamdexDocumentWrapper>() {
            @Override
            public FlamdexDocumentWrapper apply(final FlamdexDocument input) {
                return new FlamdexDocumentWrapper(input);
            }
        }));
        for (final FlamdexDocument doc : l2) {
            final FlamdexDocumentWrapper w = new FlamdexDocumentWrapper(doc);
            if (!s1.remove(w)) {
                return false;
            }
        }
        return s1.isEmpty();
    }

    private static class FlamdexDocumentWrapper {
        private final Map<String, LongSet> intFields;
        private final Map<String, Set<String>> stringFields;

        private FlamdexDocumentWrapper(final FlamdexDocument doc) {
            intFields = rewriteIntFields(doc.getIntFields());
            stringFields = rewriteStringFields(doc.getStringFields());
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final FlamdexDocumentWrapper that = (FlamdexDocumentWrapper) o;

            if (intFields != null ? !intFields.equals(that.intFields) : that.intFields != null) {
                return false;
            }
            if (stringFields != null ? !stringFields.equals(that.stringFields) : that.stringFields != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = intFields != null ? intFields.hashCode() : 0;
            result = 31 * result + (stringFields != null ? stringFields.hashCode() : 0);
            return result;
        }

        public String toString() {
            return "FlamdexDocumentWrapper{" +
                    "intFields=" + intFields +
                    ", stringFields=" + stringFields +
                    '}';
        }
    }

    private static Map<String, LongSet> rewriteIntFields(final Map<String, LongList> map) {
        final Map<String, LongSet> ret = Maps.newHashMapWithExpectedSize(map.size());
        for (final Map.Entry<String, LongList> mapEntry : map.entrySet()) {
            ret.put(mapEntry.getKey(), new LongOpenHashSet(mapEntry.getValue()));
        }
        return ret;
    }

    private static Map<String, Set<String>> rewriteStringFields(final Map<String, List<String>> map) {
        final Map<String, Set<String>> ret = Maps.newHashMapWithExpectedSize(map.size());
        for (final Map.Entry<String, List<String>> mapEntry : map.entrySet()) {
            ret.put(mapEntry.getKey(), new HashSet<>(mapEntry.getValue()));
        }
        return ret;
    }
}
