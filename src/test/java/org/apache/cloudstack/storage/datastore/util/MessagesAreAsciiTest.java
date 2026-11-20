/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.datastore.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Messages end up in logs, API answers and terminals of unknown encoding: a typographic dash or
 * arrow in one of them arrives as garbage. String literals of the plugin are plain ASCII.
 */
class MessagesAreAsciiTest {
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    void stringLiteralsOfThePluginArePlainAscii() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(root), "run from the project directory");
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue(sources.size() > 10, "sources found: " + sources.size());
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            int lineNo = 0;
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                lineNo++;
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(m.group())) {
                        offenders.add(source.getFileName() + ":" + lineNo + " " + m.group());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "non-ASCII characters in string literals: " + offenders);
    }
}
