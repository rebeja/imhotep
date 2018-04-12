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
 package com.indeed.imhotep.archive.compression;

import com.indeed.util.compress.CompressionInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author jsgroth
 */
public class NoCompressionInputStream extends CompressionInputStream {
    public NoCompressionInputStream(final InputStream in) throws IOException {
        super(in);
    }

    @Override
    public int read(final byte[] bytes, final int off, final int len) throws IOException {
        return in.read(bytes, off, len);
    }

    @Override
    public void resetState() throws IOException {
        // no-op
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }
}
