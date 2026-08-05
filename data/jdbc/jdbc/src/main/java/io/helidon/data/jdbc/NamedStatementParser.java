/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.data.jdbc;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Named SQL parameter parser adapted from {@code io.helidon.dbclient.jdbc.NamedStatementParser}.
 * <p>
 * It converts named bind variables to JDBC positional markers and records the bind names in statement order. This
 * source is intentionally duplicated during the Data JDBC transition so the new provider does not depend on the
 * DbClient module.
 */
final class NamedStatementParser {

    @FunctionalInterface
    private interface Action extends Consumer<NamedStatementParser> {
    }

    private enum CharClass {
        IDENTIFIER_START,
        IDENTIFIER_PART,
        LF,
        CR,
        APOSTROPHE,
        STAR,
        DASH,
        SLASH,
        COLON,
        OTHER;

        private static CharClass charClass(char c) {
            return switch (c) {
                case '\r' -> CR;
                case '\n' -> LF;
                case '\'' -> APOSTROPHE;
                case '*' -> STAR;
                case '-' -> DASH;
                case '/' -> SLASH;
                case ':' -> COLON;
                case '_' -> IDENTIFIER_START;
                default -> Character.isJavaIdentifierStart(c)
                        ? IDENTIFIER_START
                        : (Character.isJavaIdentifierPart(c) ? IDENTIFIER_PART : OTHER);
            };
        }
    }

    private enum State {
        STATEMENT,
        STRING,
        COLON,
        PARAMETER,
        MULTILN_COMMENT_BG,
        MULTILN_COMMENT_END,
        MULTILN_COMMENT,
        SINGLELN_COMMENT_BG,
        SINGLELN_COMMENT_END,
        SINGLELN_COMMENT;

        private static final State[][] TRANSITION = {
                {
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STRING,
                        STATEMENT,
                        SINGLELN_COMMENT_BG,
                        MULTILN_COMMENT_BG,
                        COLON,
                        STATEMENT
                },
                {
                        STRING,
                        STRING,
                        STRING,
                        STRING,
                        STATEMENT,
                        STRING,
                        STRING,
                        STRING,
                        STRING,
                        STRING
                },
                {
                        PARAMETER,
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STRING,
                        STATEMENT,
                        SINGLELN_COMMENT_BG,
                        MULTILN_COMMENT_BG,
                        COLON,
                        STATEMENT
                },
                {
                        PARAMETER,
                        PARAMETER,
                        STATEMENT,
                        STATEMENT,
                        STRING,
                        STATEMENT,
                        SINGLELN_COMMENT_BG,
                        MULTILN_COMMENT_BG,
                        COLON,
                        STATEMENT
                },
                {
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STRING,
                        MULTILN_COMMENT,
                        SINGLELN_COMMENT_BG,
                        MULTILN_COMMENT_BG,
                        COLON,
                        STATEMENT
                },
                {
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT_END,
                        MULTILN_COMMENT,
                        STATEMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT
                },
                {
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT_END,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT,
                        MULTILN_COMMENT
                },
                {
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STATEMENT,
                        STRING,
                        STATEMENT,
                        SINGLELN_COMMENT,
                        MULTILN_COMMENT_BG,
                        COLON,
                        STATEMENT
                },
                {
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        STATEMENT,
                        SINGLELN_COMMENT_END,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT
                },
                {
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        STATEMENT,
                        SINGLELN_COMMENT_END,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT,
                        SINGLELN_COMMENT
                }
        };
    }

    private static final Action[][] ACTION = {
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::doNothing,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::setFirstParamChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColonAndCopyChar,
                    NamedStatementParser::addColon,
                    NamedStatementParser::addColonAndCopyChar
            },
            {
                    NamedStatementParser::setNextParamChar,
                    NamedStatementParser::setNextParamChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParamAndCopyChar,
                    NamedStatementParser::finishParam,
                    NamedStatementParser::finishParamAndCopyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::doNothing,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::doNothing,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar
            },
            {
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar,
                    NamedStatementParser::copyChar
            }
    };

    private final String statement;
    private final StringBuilder sb;
    private final StringBuilder nap;
    private final List<String> names;
    private char c;

    NamedStatementParser(String statement) {
        this.sb = new StringBuilder(statement.length());
        this.nap = new StringBuilder(32);
        this.names = new LinkedList<>();
        this.statement = statement;
        this.c = '\0';
    }

    String convert() {
        State state = State.STATEMENT;
        int len = statement.length();
        for (int i = 0; i < len; i++) {
            c = statement.charAt(i);
            CharClass cl = CharClass.charClass(c);
            ACTION[state.ordinal()][cl.ordinal()].accept(this);
            state = State.TRANSITION[state.ordinal()][cl.ordinal()];
        }
        if (state == State.PARAMETER) {
            names.add(nap.toString());
            sb.append('?');
        }
        return sb.toString();
    }

    List<String> namesOrder() {
        return names;
    }

    private static void doNothing(NamedStatementParser parser) {
    }

    private static void copyChar(NamedStatementParser parser) {
        parser.sb.append(parser.c);
    }

    private static void addColon(NamedStatementParser parser) {
        parser.sb.append(':');
    }

    private static void addColonAndCopyChar(NamedStatementParser parser) {
        parser.sb.append(':');
        parser.sb.append(parser.c);
    }

    private static void setFirstParamChar(NamedStatementParser parser) {
        parser.nap.setLength(0);
        parser.nap.append(parser.c);
    }

    private static void setNextParamChar(NamedStatementParser parser) {
        parser.nap.append(parser.c);
    }

    private static void finishParamAndCopyChar(NamedStatementParser parser) {
        parser.names.add(parser.nap.toString());
        parser.sb.append('?');
        parser.sb.append(parser.c);
    }

    private static void finishParam(NamedStatementParser parser) {
        parser.names.add(parser.nap.toString());
        parser.sb.append('?');
    }
}
