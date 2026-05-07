# frozen_string_literal: true
#
# DO NOT EDIT THIS COPY. Canonical source:
#   ../../ddot.it-syntax-tools/rouge/lib/rouge/lexers/ddot.rb
# Conformance-tested against ../../ddot.it/test-data/cases/. Refresh this
# copy after changes to the canonical lexer.
#
# Rouge lexer for ddot.it (https://ddot.it). Emits tokens that — after the
# scope-name mapping in `tools/conformance-rouge.rb` — equal the canonical
# token streams in `../../ddot.it/test-data/cases/*/expected.tokens.json`.
#
# Token mapping (Rouge → canonical, see ../../../ddot.it/test-data/tokens.md):
#   Name::Class                  → subject
#   Name::Function               → relation
#   Literal::String::Symbol      → object
#   Operator                     → operator
#   Keyword::Pseudo              → command
#   Comment::Preproc             → meta-delim
#   Comment::Hashbang            → meta-operator
#   Comment::Doc                 → meta-relation
#   Comment::Special             → meta-object
#   Comment::Multiline           → meta-text
#   Comment::Single              → disabled
#
# State machine is flat by design: we use `goto` exclusively (no `push`), so
# the stack is always one deep and `\n` simply transitions back to `:root`.
# This keeps line-by-line dispatch clean — every line starts fresh.
#
# Rouge gotcha: `rule pat, Token do |m| ... end` IGNORES the Token arg when
# a block is given. To both emit a token AND transition, use a block that
# calls `token Token, m[0]` then `goto :next`.

require 'rouge'

module Rouge
  module Lexers
    class Ddot < RegexLexer
      title 'ddot.it'
      desc 'Knowledge graph notation (https://ddot.it)'
      tag 'ddot'
      aliases 'ddotit', 'ddot.it'
      filenames '*.ddot'
      mimetypes 'text/x-ddot'

      OFF_RE = /(?:ddot\.it\/off|!!off)/.freeze
      ON_RE  = /(?:ddot\.it\/on|!!on)/.freeze
      CMD_RE = /(?:ddot\.it(?:\/[\w.-]+)?|!![\w.-]*)/.freeze

      # ─────────── line-dispatch root ───────────
      state :root do
        # Standalone ,, opens a free-form meta block.
        rule(%r/^[ \t]*,,[ \t]*(?=\n|\z)/) do |m|
          token Comment::Preproc, m[0]
          goto :meta_block
        end
        # Off-marker enters disabled span.
        rule(%r/^[ \t]*#{OFF_RE}[ \t]*(?=\n|\z)/) do |m|
          token Keyword::Pseudo, m[0]
          goto :disabled
        end
        # Empty / whitespace-only line.
        rule %r/^[ \t]*\n/, Text
        # Continuation line (starts with `..` or `....`).
        rule(/^(?=[ \t]*\.{2,4})/) { goto :after_subject }
        # Triple line — start with subject parsing.
        rule(/^/) { goto :subject }
      end

      # ─────────── disabled span ───────────
      state :disabled do
        rule(%r/^[ \t]*#{ON_RE}[ \t]*(?=\n|\z)/) do |m|
          token Keyword::Pseudo, m[0]
          goto :root
        end
        rule %r/^[ \t]*\n/, Text
        rule %r/[^\n]+/, Comment::Single
        rule %r/\n/, Text
      end

      # ─────────── multi-line meta block (free-form) ───────────
      state :meta_block do
        rule(%r/^[ \t]*,,[ \t]*(?=\n|\z)/) do |m|
          token Comment::Preproc, m[0]
          goto :root
        end
        rule %r/^[ \t]*\n/, Text
        rule %r/[^\n]+/, Comment::Multiline
        rule %r/\n/, Text
      end

      # ─────────── triple line: subject phase ───────────
      state :subject do
        rule %r/[ \t]+/, Text
        rule(/#{CMD_RE}(?=[ \t]+\.{2,4}|[ \t]*,,|[ \t]*$|\n|\z)/) do |m|
          token Keyword::Pseudo, m[0]
          goto :after_subject
        end
        rule(/[^\s.,][^\n,]*?(?=[ \t]+\.{2,4}|[ \t]*,,|[ \t]*$|\n|\z)/) do |m|
          token Name::Class, m[0]
          goto :after_subject
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      # ─────────── after subject (or continuation entry) ───────────
      state :after_subject do
        rule %r/[ \t]+/, Text
        # `.. ..` (spaced operator) — emit as one operator token covering the
        # whole sequence, matching the canonical corpus.
        rule(%r/\.{2}[ \t]+\.{2}/) do |m|
          token Operator, m[0]
          goto :object
        end
        rule(%r/\.{4}/) do |m|
          token Operator, m[0]
          goto :object
        end
        rule(%r/\.{2}/) do |m|
          token Operator, m[0]
          goto :relation
        end
        rule(%r/,,/) do |m|
          token Comment::Preproc, m[0]
          goto :inline_meta
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      # ─────────── relation phase ───────────
      state :relation do
        rule(/[^\s.,][^\n.,]*?(?=[ \t]*\.{2}|[ \t]*$|\n|\z)/) do |m|
          token Name::Function, m[0]
          goto :after_relation
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      state :after_relation do
        rule %r/[ \t]+/, Text
        rule(%r/\.{2}/) do |m|
          token Operator, m[0]
          goto :object
        end
        rule(%r/,,/) do |m|
          token Comment::Preproc, m[0]
          goto :inline_meta
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      # ─────────── object phase ───────────
      state :object do
        rule(/[^\s,][^\n,]*?(?=[ \t]*,,|[ \t]*$|\n|\z)/) do |m|
          token Literal::String::Symbol, m[0]
          goto :after_object
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      state :after_object do
        rule %r/[ \t]+/, Text
        # Trailing `,,` at end-of-line opens a typed multi-line meta block:
        # subsequent lines are `..key.. value` triples emitted as meta-*
        # until a closing standalone `,,`.
        rule(%r/,,(?=[ \t]*(?:\n|\z))/) do |m|
          token Comment::Preproc, m[0]
          goto :typed_meta_open
        end
        # Inline `,,` followed by content on the same line.
        rule(%r/,,/) do |m|
          token Comment::Preproc, m[0]
          goto :inline_meta
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      # Right after trailing `,,` — wait for the newline, then enter
      # :typed_meta_block for subsequent lines.
      state :typed_meta_open do
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      # Typed meta block: each line is `..relation.. object`, closed by `,,`.
      state :typed_meta_block do
        rule(%r/^[ \t]*,,[ \t]*(?=\n|\z)/) do |m|
          token Comment::Preproc, m[0]
          goto :root
        end
        rule %r/^[ \t]*\n/, Text
        rule(/^(?=[ \t]*\.{2,4})/) { goto :typed_meta_op1 }
        rule %r/[^\n]+/, Comment::Multiline   # free-form fallback
        rule %r/\n/, Text
      end

      state :typed_meta_op1 do
        rule %r/[ \t]+/, Text
        rule(%r/\.{2}/) do |m|
          token Comment::Hashbang, m[0]
          goto :typed_meta_relation
        end
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      state :typed_meta_relation do
        rule(/[^\s.,][^\n.,]*?(?=[ \t]*\.{2}|[ \t]*$|\n|\z)/) do |m|
          token Comment::Doc, m[0]
          goto :typed_meta_after_relation
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      state :typed_meta_after_relation do
        rule %r/[ \t]+/, Text
        rule(%r/\.{2}/) do |m|
          token Comment::Hashbang, m[0]
          goto :typed_meta_object
        end
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      state :typed_meta_object do
        rule(/[^\s,][^\n,]*?(?=[ \t]*$|\n|\z)/) do |m|
          token Comment::Special, m[0]
          goto :typed_meta_after_object
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      state :typed_meta_after_object do
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :typed_meta_block }
      end

      # ─────────── inline metadata (after `,,` on a triple line) ───────────
      state :inline_meta do
        rule %r/[ \t]+/, Text
        rule(%r/\.{2}/) do |m|
          token Comment::Hashbang, m[0]
          goto :inline_meta_relation
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      state :inline_meta_relation do
        rule(/[^\s.,][^\n.,]*?(?=[ \t]*\.{2}|[ \t]*$|\n|\z)/) do |m|
          token Comment::Doc, m[0]
          goto :inline_meta_after_relation
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      state :inline_meta_after_relation do
        rule %r/[ \t]+/, Text
        rule(%r/\.{2}/) do |m|
          token Comment::Hashbang, m[0]
          goto :inline_meta_object
        end
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end

      state :inline_meta_object do
        rule(/[^\s,][^\n,]*?(?=[ \t]*$|\n|\z)/) do |m|
          token Comment::Special, m[0]
          goto :after_object
        end
        rule %r/[ \t]+/, Text
        rule(%r/\n/) { token Text, "\n"; goto :root }
      end
    end
  end
end
