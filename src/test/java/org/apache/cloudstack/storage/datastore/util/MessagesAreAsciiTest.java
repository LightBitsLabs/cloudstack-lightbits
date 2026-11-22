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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                while (end > 0 && backslashesBefore(src, end) % 2 == 1) {   // an escaped quote, not the end
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

    private static int backslashesBefore(String s, int at) {
        int k = 0;
        while (at - k - 1 >= 0 && s.charAt(at - k - 1) == '\\') {
            k++;
        }
        return k;
    }

    /**
     * Unicode escapes (backslash, u, four hex digits) outside an escaped backslash. Java
     * translates them before it reads the source, so one can hide a non-ASCII character or a
     * quote from the lexer above; the plugin uses none, and this keeps it that way.
     */
    static List<String> unicodeEscapes(String src) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\u+[0-9a-fA-F]{4}").matcher(src);
        while (m.find()) {
            if (backslashesBefore(src, m.start()) % 2 == 0) {
                out.add((count(src, 0, m.start()) + 1) + ": " + m.group());
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
    void aTextBlockEndsAfterAnEscapedBackslashButNotAfterAnEscapedQuote() {
        String dash = "\u2014";
        String bs = "\\";
        // even: path\\""" closes the block, so the comment after it is not part of it
        List<String> even = literals("String s = \"\"\"\n path" + bs + bs + "\"\"\";\n// " + dash + "\n");
        assertEquals(1, even.size(), even.toString());
        assertTrue(ascii(even.get(0)), even.toString());
        // odd: \""" is an escaped quote, the block goes on to the next delimiter
        List<String> odd = literals("String s = \"\"\"\n a" + bs + "\"\"\" b " + dash + "\n \"\"\";\n");
        assertEquals(1, odd.size(), odd.toString());
        assertTrue(!ascii(odd.get(0)), "the rest of the block is checked: " + odd);
    }

    @Test
    void unicodeEscapesAreFoundButAnEscapedBackslashIsNot() {
        String bs = "\\";
        assertEquals(List.of("1: " + bs + "u2014"), unicodeEscapes("String s = \"bad " + bs + "u2014\";"));
        assertEquals(List.of("1: " + bs + "u0022"), unicodeEscapes("String s = " + bs + "u0022x" + bs + "u0022;").subList(0, 1));
        assertTrue(unicodeEscapes("String s = \"" + bs + bs + "u2014\";").isEmpty(), "two backslashes then u is no escape");
    }

    @Test
    void thePluginUsesNoUnicodeEscapes() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get("src", "main", "java"))) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                for (String e : unicodeEscapes(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))) {
                    offenders.add(source.getFileName() + ":" + e);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Unicode escapes in the plugin sources (write the character, or plain ASCII): " + offenders);
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
