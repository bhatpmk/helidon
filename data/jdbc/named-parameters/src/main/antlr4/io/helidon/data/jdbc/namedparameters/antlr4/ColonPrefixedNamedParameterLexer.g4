/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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
lexer grammar ColonPrefixedNamedParameterLexer;

tokens { BLOCK_COMMENT, DOLLAR_QUOTED_STRING, ORACLE_QUOTED_STRING }

@members {

    private int blockCommentDepth = 0;

    private String dollarQuoteTag = "";

    private char oracleQuoteTerminator = 0;

    private void startBlockComment() {
        this.blockCommentDepth = 1;
    }

    private void openBlockComment() {
        this.blockCommentDepth++;
    }

    private boolean closeBlockComment() {
        return --this.blockCommentDepth == 0;
    }

    private void setDollarQuoteTag() {
        String text = this.getText();
        this.dollarQuoteTag = text.substring(1, text.length() - 1);
    }

    private boolean isMatchingDollarQuoteTerminator() {
        String text = this.getText();
        return text.substring(1, text.length() - 1).equals(this.dollarQuoteTag);
    }

    private void clearDollarQuoteTag() {
        this.dollarQuoteTag = "";
    }

    private void setOracleQuoteTerminator() {
        char delimiter = this.getText().charAt(2);
        this.oracleQuoteTerminator = switch (delimiter) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> delimiter;
        };
    }

    private boolean isMatchingOracleQuoteTerminator() {
        return this.getText().charAt(0) == this.oracleQuoteTerminator;
    }

    private void clearOracleQuoteTerminator() {
        this.oracleQuoteTerminator = 0;
    }

}

// Order is significant and relies on Antlr's "maximal munch" semantics. Early productions "win" over later ones with
// "longer" tokens "beating" shorter ones.

// PostgreSQL has a cast operator that uses double colons.
POSTGRESQL_CAST : '::' ;

// Some databases use Pascal-style assignment operators.
COLON_EQUALS_ASSIGNMENT : ':=' ;

// JDBC parameters are denoted by a single question mark, so we need to make sure we don't do anything with double
// question marks.
DOUBLE_QUESTION_MARK : '??' ;

// PostgreSQL JSONB operators that begin with a question mark. These must appear before PARAMETER_MARKER.
POSTGRESQL_JSONB_EXISTS_ANY : '?|' ;
POSTGRESQL_JSONB_EXISTS_ALL : '?&' ;

// A "named parameter", e.g. :frob. See the IDENTIFIER_START and IDENTIFIER_PART fragments at the end of the file for
// details. Must appear after the POSTGRESQL_CAST and COLON_EQUALS_ASSIGNMENT productions (as it does here).
PARAMETER_NAME : ':' IDENTIFIER_START IDENTIFIER_PART* ('.' IDENTIFIER_START IDENTIFIER_PART*)* ;

// A reserved character in JDBC-flavored SQL where prepared statement arguments go. Must appear after the
// DOUBLE_QUESTION_MARK production (as it does here).
PARAMETER_MARKER : '?' ;

// A line comment.
//
//   -- this is a comment
//   // this is also a comment
//
LINE_COMMENT : ('--' | '//') ~[\r\n]* -> channel(HIDDEN) ;

// A C-style block comment.
//
//   /*
//     This is a comment.
//    */
//
// PostgreSQL allows these to nest, so we handle them in a dedicated mode.
BLOCK_COMMENT_START
    : '/*' {this.startBlockComment();} -> more, pushMode(BLOCK_COMMENT_MODE)
    ;

// Single-quoted string, or identifier; whatever it is, we don't want to find named parameters in it. (Non-standard, but
// many databases support this)
SINGLE_QUOTED_STRING : '\'' ( '\'\'' | '\\' . | ~'\'' )* '\'' -> channel(HIDDEN) ;

// Double-quoted string, or identifier; whatever it is, we don't want to find named parameters in it. ("Standard" SQL)
DOUBLE_QUOTED_STRING : '"' ( '""' | ~'"' )* '"' -> channel(HIDDEN) ;

// Double-quoted string, or identifier; whatever it is, we don't want to find named parameters in it. (MySQL syntax
// permits this)
BACKTICK_QUOTED_STRING : '`' ( '``' | ~'`' )* '`' -> channel(HIDDEN) ;

// Bracket-quoted identifier; whatever it is, we don't want to find named parameters in it. (SQL Server syntax (TSQL)
// uses this). This rule is intentionally narrow so expression-like bracket contents remain visible.
BRACKET_QUOTED_IDENTIFIER : '[' ( ']]' | [A-Za-z0-9_ ] )+ ']' -> channel(HIDDEN) ;

// A very strange quoting mechanism used by PostgreSQL like this:
//
//   $foo$My identifier$foo$
//   $$another identifier$$
//
DOLLAR_QUOTED_STRING_START
    : '$' TAG? '$' {this.setDollarQuoteTag();} -> more, pushMode(DOLLAR_QUOTED_STRING_MODE)
    ;

// A very strange quoting mechanism used by Oracle like this:
//
//   q'[My identifier]'
//   q'!My identifier!'
//
ORACLE_QUOTED_STRING_START
    : [qQ] '\'' ORACLE_QUOTE_DELIMITER {this.setOracleQuoteTerminator();} -> more, pushMode(ORACLE_QUOTED_STRING_MODE)
    ;

WHITESPACE : [ \t\r\n]+ -> channel(HIDDEN) ;

// Any other SQL gunk we don't care about for our purposes. Won't be comments, quoted identifiers, '?', etc.
//
// Note the first occurence of the right bracket character (]). It is preceded by a backslash (\) so it does not
// terminate the initial character set, which continues to the first occurence of the plus character (+).
//
// Note the second occurrence of the left bracket character([). Because the character set has already been "opened",
// this does not get treated specially, so it is a simple literal character in the character set.
CHUNK : ~[\]:?'"`[ \t\r\n/-]+ | '-' ~[-:?] | '/' ~[*:?] ;

// Very important this comes last in the DEFAULT_MODE; this categorizes anything left over as a single-character OTHER
// token.
OTHER : . ;

mode BLOCK_COMMENT_MODE;

BLOCK_COMMENT_MODE_OPEN
    : '/*' {this.openBlockComment();} -> more
    ;

BLOCK_COMMENT_MODE_CLOSE
    : '*/' {this.closeBlockComment()}? -> type(BLOCK_COMMENT), channel(HIDDEN), popMode
    ;

BLOCK_COMMENT_MODE_NESTED_CLOSE : '*/' -> more ;

BLOCK_COMMENT_MODE_CHUNK : . -> more ;

mode DOLLAR_QUOTED_STRING_MODE;

DOLLAR_QUOTED_STRING_CLOSE
    : '$' TAG? '$' {this.isMatchingDollarQuoteTerminator()}? {this.clearDollarQuoteTag();}
      -> type(DOLLAR_QUOTED_STRING), channel(HIDDEN), popMode
    ;

DOLLAR_QUOTED_STRING_CHUNK : ~'$'+ -> more ;

DOLLAR_QUOTED_STRING_DOLLAR : '$' -> more ;

mode ORACLE_QUOTED_STRING_MODE;

ORACLE_QUOTED_STRING_CLOSE
    : . '\'' {this.isMatchingOracleQuoteTerminator()}? {this.clearOracleQuoteTerminator();}
      -> type(ORACLE_QUOTED_STRING), channel(HIDDEN), popMode
    ;

ORACLE_QUOTED_STRING_CHUNK : . -> more ;

// Fragments referred to above.
fragment IDENTIFIER_START : [A-Za-z_] ;
fragment IDENTIFIER_PART : [A-Za-z_0-9] ;
fragment ORACLE_QUOTE_DELIMITER : ~[' \t\r\n] ;
fragment TAG : [A-Za-z_0-9]+ ;
