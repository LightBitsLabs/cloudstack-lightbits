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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Messages end up in logs, API answers and terminals of unknown encoding: a typographic dash or
 * arrow in one of them arrives as garbage. String literals of the plugin are plain ASCII.
 */
class MessagesAreAsciiTest {

    /**
     * The string literals of a Java source with their line numbers ("12: text"). A small lexer
     * rather than a regular expression: a quote inside a comment or a character literal must
     * not pair with the quote of a real literal, or the literal goes unchecked.
     */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                line += count(src, i, end);
                i = end;
            } else if (src.startsWith("\"\"\"", i)) {             // text block
                int end = src.indexOf("\"\"\"", i + 3);
                while (end > 0 && src.charAt(end - 1) == '\\') {
                    end = src.indexOf("\"\"\"", end + 1);
                }
                end = end < 0 ? n : end + 3;
                out.add(line + ": " + src.substring(i, end));
                line += count(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                if (c == '"') {
                    out.add(line + ": " + src.substring(i, Math.min(j + 1, n)));
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static int count(String s, int from, int to) {
        int k = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') {
                k++;
            }
        }
        return k;
    }

    private static boolean ascii(String s) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(s);
    }

    @Test
    void theLexerDoesNotPairAQuoteInACommentWithALiteral() {
        String dash = "\u2014";
        List<String> found = literals("class A {\n String m = /* \" */ \"bad " + dash + "\";\n"
                + " char q = '\"'; String n = \"ok\"; // \" " + dash + "\n"
                + " String t = \"\"\"\n  block " + dash + "\n  \"\"\";\n}\n");
        assertTrue(found.stream().anyMatch(l -> l.startsWith("2: ") && !ascii(l)), found.toString());
        assertTrue(found.stream().anyMatch(l -> l.equals("3: \"ok\"")), found.toString());
        assertTrue(found.stream().anyMatch(l -> l.startsWith("4: ") && !ascii(l)), found.toString());
        assertTrue(found.stream().noneMatch(l -> l.startsWith("3: ") && !ascii(l)), "a comment is not a literal: " + found);
    }

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
        int checked = 0;
        for (Path source : sources) {
            for (String literal : literals(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))) {
                checked++;
                if (!ascii(literal)) {
                    offenders.add(source.getFileName() + ":" + literal);
                }
            }
        }
        assertTrue(checked > 500, "string literals checked: " + checked);
        assertTrue(offenders.isEmpty(), "non-ASCII characters in string literals: " + offenders);
    }
}
