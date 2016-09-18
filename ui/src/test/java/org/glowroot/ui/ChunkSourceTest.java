/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.Writer;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.ui.ChunkSource.ChunkCopier;

import static org.assertj.core.api.Assertions.assertThat;

public class ChunkSourceTest {

    @Test
    public void testConcatWithEmptyChunks() throws IOException {
        // given
        ChunkSource one = ChunkSource.wrap("1");
        ChunkSource two = ChunkSource.wrap("2");
        ChunkSource three = ChunkSource.wrap("3");
        ChunkSource four = ChunkSource.wrap("4");
        ChunkSource five = ChunkSource.wrap("5");
        ChunkSource six = ChunkSource.wrap("6");
        ChunkSource seven = ChunkSource.wrap("7");
        ChunkSource eight = ChunkSource.wrap("8");
        ChunkSource nine = ChunkSource.wrap("9");

        ChunkSource firstThree = ChunkSource.concat(ImmutableList.of(EmptyChunkSource.INSTANCE, one,
                EmptyChunkSource.INSTANCE, two, three, EmptyChunkSource.INSTANCE));
        ChunkSource secondThree = ChunkSource.concat(ImmutableList.of(EmptyChunkSource.INSTANCE,
                EmptyChunkSource.INSTANCE, EmptyChunkSource.INSTANCE, four, five, six));
        ChunkSource lastThree = ChunkSource.concat(ImmutableList.of(seven, eight, nine));

        // when
        ChunkSource concat = ChunkSource.concat(ImmutableList.of(EmptyChunkSource.INSTANCE,
                EmptyChunkSource.INSTANCE, EmptyChunkSource.INSTANCE, firstThree,
                EmptyChunkSource.INSTANCE, EmptyChunkSource.INSTANCE, EmptyChunkSource.INSTANCE,
                secondThree, lastThree, EmptyChunkSource.INSTANCE, EmptyChunkSource.INSTANCE,
                EmptyChunkSource.INSTANCE));

        // then
        CharArrayWriter writer = new CharArrayWriter();
        ChunkCopier copier = concat.getCopier(writer);
        while (copier.copyNext()) {
        }
        assertThat(writer.toString()).isEqualTo("123456789");
    }

    @Test
    public void testConcatWithEmptyStringChunks() throws IOException {
        // given
        ChunkSource one = ChunkSource.wrap("1");
        ChunkSource two = ChunkSource.wrap("2");
        ChunkSource three = ChunkSource.wrap("3");
        ChunkSource four = ChunkSource.wrap("4");
        ChunkSource five = ChunkSource.wrap("5");
        ChunkSource six = ChunkSource.wrap("6");
        ChunkSource seven = ChunkSource.wrap("7");
        ChunkSource eight = ChunkSource.wrap("8");
        ChunkSource nine = ChunkSource.wrap("9");

        ChunkSource firstThree = ChunkSource.concat(ImmutableList.of(ChunkSource.wrap(""), one,
                ChunkSource.wrap(""), two, three, ChunkSource.wrap("")));
        ChunkSource secondThree = ChunkSource.concat(ImmutableList.of(ChunkSource.wrap(""),
                ChunkSource.wrap(""), ChunkSource.wrap(""), four, five, six));
        ChunkSource lastThree = ChunkSource.concat(ImmutableList.of(seven, eight, nine));

        // when
        ChunkSource concat = ChunkSource.concat(ImmutableList.of(ChunkSource.wrap(""),
                ChunkSource.wrap(""), ChunkSource.wrap(""), firstThree, ChunkSource.wrap(""),
                ChunkSource.wrap(""), ChunkSource.wrap(""), secondThree, lastThree,
                ChunkSource.wrap(""), ChunkSource.wrap(""), ChunkSource.wrap("")));

        // then
        CharArrayWriter writer = new CharArrayWriter();
        ChunkCopier copier = concat.getCopier(writer);
        while (copier.copyNext()) {
        }
        assertThat(writer.toString()).isEqualTo("123456789");
    }

    private static class EmptyChunkSource extends ChunkSource {

        private static final EmptyChunkSource INSTANCE = new EmptyChunkSource();

        @Override
        public ChunkCopier getCopier(Writer writer) throws IOException {
            return new ChunkCopier() {
                @Override
                public boolean copyNext() throws IOException {
                    return false;
                }
            };
        }
    }
}
