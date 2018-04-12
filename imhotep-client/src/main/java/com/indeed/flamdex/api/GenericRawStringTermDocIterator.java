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
 package com.indeed.flamdex.api;

import javax.annotation.WillCloseWhenClosed;

/**
 * @author jplaisance
 */
public final class GenericRawStringTermDocIterator extends GenericTermDocIterator<RawStringTermIterator> implements RawStringTermDocIterator {

    public GenericRawStringTermDocIterator(
            @WillCloseWhenClosed final RawStringTermIterator termIterator,
            @WillCloseWhenClosed final DocIdStream docIdStream) {
        super(termIterator, docIdStream);
    }

    @Override
    public String term() {
        return termIterator.term();
    }

    @Override
    public byte[] termStringBytes() {
        return termIterator.termStringBytes();
    }

    @Override
    public int termStringLength() {
        return termIterator.termStringLength();
    }
}
