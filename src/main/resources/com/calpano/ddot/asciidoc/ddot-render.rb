# frozen_string_literal: true
#
# Pure-Ruby ddot.it preview renderer. NO Asciidoctor / IntelliJ dependency.
#
# CANONICAL source of the golden corpus's `expected.body.html` files
# (`../ddot.it/test-data/regenerate-html.rb` runs this file). Two mirrors must
# produce byte-identical output for every corpus case:
#   - Java:  it.ddot.core.html.DdotHtmlRenderer#body
#   - JS:    src/main/resources/preview/ddot-preview.js (renderBody)
# When you change this file, regenerate the corpus and update both mirrors.
#
# The line/slot model is a direct port of the corpus-pinned positional
# tokenizer `it.ddot.core.token.DdotTokenizer` (which itself
# mirrors the TextMate grammar): tokenize each line into
# {start, end, token} spans, then wrap each span in a role-classed <span>.
# Normative rules: site/spec/ddot-it-parse.adoc (token counting, the gate,
# off/on, !!block) and site/spec/ddot-it-highlight.adoc (roles D1-D9).
#
# Two callers consume this file:
#   1. `ddot-extension.rb` — the AsciidoctorJ BlockProcessor that runs inside
#      the JetBrains AsciiDoc plugin (loaded from `<root>/.asciidoctor/lib/`).
#   2. `../ddot.it/test-data/regenerate-html.rb` — the golden-test
#      corpus regeneration script.

module DdotIt
  module Render
    # --- counted tokens (Parse Spec, "Token Sequences") ---------------------
    # Runs of EXACTLY n, neither preceded nor followed by the same character.
    # The lookarounds carry the rule: `\.{2}` alone would match the first two
    # dots of `...`, which the tokenization rule forbids.
    # WS per the Parse Spec: TAB plus any Unicode space separator (Zs). Ruby's
    # /\s/ is ASCII-only, so a NBSP between slots would survive; the canonical
    # TextMate tokens strip it exactly like a space.
    WS_SRC   = '[\t\p{Zs}]'
    DT2_SRC  = '(?<!\.)\.{2}(?!\.)'
    QUAD_SRC = "(?<!\\.)(?:\\.{4}|\\.{2}#{WS_SRC}+\\.{2})(?!\\.)"
    CM2_SRC  = '(?<!,),,(?!,)'
    SC2_SRC  = '(?<!;);;(?!;)'

    # A command is recognised by SHAPE (Highlight Spec D6): `!!name`,
    # `ddot.it/name`, `http(s)://ddot.it/name` with a non-empty name. `!!` is
    # an EM2 (exactly two `!`), so `!!!off` is not a command prefix. `?` and
    # `#` do not terminate a command; they introduce its query/fragment.
    CMD_PREFIX_SRC = '(?:(?:https?://)?ddot\.it/|(?<!!)!!(?!!))'
    CMD_SRC        = "#{CMD_PREFIX_SRC}[^\\s?#]+(?:\\?[^\\s#]*)?(?:#\\S*)?"

    CMD_ONLY = /\A#{CMD_SRC}\z/
    # A command nested in field text: preceded by start-of-slot or whitespace.
    NESTED_CMD = /(?<!\S)#{CMD_SRC}/

    # `!!off` / `!!on` are recognised ANYWHERE on a line (Parse Spec
    # "pre-parse-recognition") — the common way to write them is inside a host
    # comment (`<!-- ddot.it/off -->`, `# !!on`). The name is matched exactly:
    # `(?![^\s?#])` pins it, so `!!office` is not `!!off`.
    OFF_LINE = /(#{CMD_PREFIX_SRC}off)(?![^\s?#])/
    ON_LINE  = /(#{CMD_PREFIX_SRC}on)(?![^\s?#])/

    # A `!!block` opener must END its physical line (block-as-field).
    BLOCK_OPENER = /(#{CMD_PREFIX_SRC}block)(?:(\?end=)(\S+))?#{WS_SRC}*$/

    # A line-initial `,,` or `;;` followed by a meta pair continues the
    # previous logical line as metadata (Parse Spec "block-continuation").
    META_CONTINUATION = /\A#{WS_SRC}*(?:(#{CM2_SRC})|(#{SC2_SRC}))(?=#{WS_SRC}+#{DT2_SRC})/

    # QUAD before DT2 so `.. ..` is ONE operator, not two.
    SIGNIFICANT = /#{QUAD_SRC}|#{DT2_SRC}|#{CM2_SRC}|#{SC2_SRC}/

    # `;;` alone, for finding where an inline meta entry ends.
    SC2_ONLY = /#{SC2_SRC}/

    # THE GATE (How-To-Highlight D5): a line is ddot.it only when it carries a
    # complete operator skeleton — two DT2, or one DT4 / `.. ..`. A lone `..`
    # or an ellipsis in prose stays entirely plain (23-not-a-triple).
    GATE = /(?:(?<!\.)\.{4}(?!\.)|(?<!\.)\.{2}(?!\.).*(?<!\.)\.{2}(?!\.))/

    # --- token type → CSS class --------------------------------------------
    TOKEN_CLASS = {
      'subject'        => 'ddot-subject',
      'relation'       => 'ddot-predicate',
      'object'         => 'ddot-object',
      'doubledot'      => 'ddot-sep',
      'meta-doubledot' => 'ddot-sep',
      'meta-delim'     => 'ddot-meta-sep',
      'meta-separator' => 'ddot-meta-sep',
      'meta-relation'  => 'ddot-meta-relation',
      'meta-object'    => 'ddot-meta-object',
      'meta-text'      => 'ddot-meta-text',
      'command'        => 'ddot-command',
      'command-param'  => 'ddot-command-param',
      'block-end'      => 'ddot-block-end',
      'excluded'       => 'ddot-inactive',
      'verbatim'       => 'ddot-verbatim'
    }.freeze

    # Slots that may carry a nested in-text command span (Highlight Spec D6:
    # "the command rule is nested inside every field capture").
    SLOT_TOKENS = %w[subject relation object meta-relation meta-object meta-text].freeze

    # Default colour palette mirroring the plugin's `ddot-preview.css`.
    # Metadata is FIRST-CLASS CONTENT (Highlight Spec: entity.name.* roots),
    # not comment-styled: meta content uses content colours, only the markers
    # (`,,`, `;;`, `..`) are subdued. Override per-document via
    # :ddot-color-* attributes from inside .adoc.
    DEFAULT_COLORS = {
      subject:   '#871094',
      predicate: '#1750EB',
      object:    '#067D17',
      meta:      '#067D17',   # meta content — content colour, NOT grey/italic
      command:   '#871094',
      separator: '#707070',
      verbatim:  '#8F5902',
      inactive:  '#8C8C8C'
    }.freeze

    class << self
      # Full HTML for a fenced/listing block: <style> + <pre><code>body</code></pre>.
      def html(raw, colors: DEFAULT_COLORS)
        "#{style_block(colors)}<pre class=\"ddot-fence\"><code>#{body(raw)}</code></pre>"
      end

      # Body-only HTML — the role-coloured span markup, no <style> / <pre>
      # wrapper. This is what the golden corpus's `expected.body.html` files
      # capture, because the wrapper differs per call-site (Markdown vs
      # AsciiDoc) and the <style> block is colour-scheme-dependent.
      def body(raw)
        out = +''
        render_body(raw.to_s, out)
        out
      end

      def style_block(colors)
        colors = DEFAULT_COLORS.merge(colors)
        <<~CSS.chomp
          <style>
            .ddot-fence{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
            .ddot-fence .ddot-subject{color:#{colors[:subject]};}
            .ddot-fence .ddot-predicate,.ddot-fence .ddot-meta-relation{color:#{colors[:predicate]};}
            .ddot-fence .ddot-object{color:#{colors[:object]};}
            .ddot-fence .ddot-meta-object,.ddot-fence .ddot-meta-text{color:#{colors[:meta]};}
            .ddot-fence .ddot-command{color:#{colors[:command]};font-weight:bold;}
            .ddot-fence .ddot-command-param{color:#{colors[:command]};}
            .ddot-fence .ddot-sep,.ddot-fence .ddot-meta-sep{color:#{colors[:separator]};}
            .ddot-fence .ddot-verbatim{color:#{colors[:verbatim]};}
            .ddot-fence .ddot-block-end{color:#{colors[:verbatim]};font-weight:bold;}
            .ddot-fence .ddot-inactive{color:#{colors[:inactive]};font-style:italic;opacity:0.7;}
          </style>
        CSS
      end

      private

      def render_body(raw, out)
        body  = raw.end_with?("\n") ? raw[0...-1] : raw
        lines = body.split("\n", -1)
        tokens = tokenize(lines)
        lines.each_with_index do |line, i|
          out << "\n" if i > 0
          render_line(line, tokens[i] || [], out)
        end
      end

      # --- tokenizer: port of DdotTokenizer#tokenize -----------------------

      def tok(s, e, t)
        { s: s, e: e, t: t }
      end

      # WS per the Parse Spec: TAB plus any Unicode space separator (Zs).
      # Ruby's /\s/ and String#strip are ASCII-only, so NBSP would survive
      # inside a slot; the canonical TextMate tokens strip it like a space.
      WS_RE = /[\s\p{Zs}]/

      def trim_ws(s)
        s.gsub(/\A[\s\p{Zs}]+|[\s\p{Zs}]+\z/, '')
      end

      def add_trimmed(list, line, type)
        s = 0
        e = line.length
        s += 1 while s < e && line[s] =~ WS_RE
        e -= 1 while e > s && line[e - 1] =~ WS_RE
        list << tok(s, e, type) if s < e
      end

      def starts_with_operator(line)
        m = SIGNIFICANT.match(line)
        m && trim_ws(line[0...m.begin(0)]).empty?
      end

      def tokenize(lines)
        toks = Array.new(lines.length) { [] }
        off       = false   # inside !!off … !!on
        verbatim  = false   # inside a !!block body
        block_end = nil     # custom ?end= marker, nil = blank-line form
        in_meta   = false   # inside a ,, … ,, block

        lines.each_with_index do |line, i|
          trimmed = trim_ws(line)

          # --- excluded region: inactive, NO role spans --------------------
          if off
            if (on = ON_LINE.match(line))
              toks[i] << tok(on.begin(1), on.end(1), 'command')
              off = false
            elsif !trimmed.empty?
              add_trimmed(toks[i], line, 'excluded')
            end
            next
          end
          # --- verbatim (!!block body) --------------------------------------
          if verbatim
            if block_end
              if trimmed == block_end
                toks_add_block_end(toks[i], line)
                verbatim = false
                block_end = nil
              elsif !trimmed.empty?
                add_trimmed(toks[i], line, 'verbatim')
              end
            elsif trimmed.empty?
              verbatim = false   # blank line ends the unmarked form
            else
              add_trimmed(toks[i], line, 'verbatim')
            end
            next
          end
          if (offm = OFF_LINE.match(line))
            toks[i] << tok(offm.begin(1), offm.end(1), 'command')
            off = true
            in_meta = false
            next
          end

          # --- inside a ,, block --------------------------------------------
          if in_meta
            if trimmed == ',,'
              add_trimmed(toks[i], line, 'meta-delim')
              in_meta = false
            elsif !trimmed.empty?
              if SIGNIFICANT.match?(line) && starts_with_operator(line)
                emit_line(toks[i], line, :meta_start, true)
              else
                add_trimmed(toks[i], line, 'meta-text')
              end
            end
            next
          end
          next if trimmed.empty?

          # --- a standalone `,,` opens a block for the previous triple ------
          if trimmed == ',,'
            add_trimmed(toks[i], line, 'meta-delim')
            in_meta = true
            next
          end

          # --- `,, ..mr.. mo` continues the previous logical line -----------
          if (mc = META_CONTINUATION.match(line))
            g = mc[1] ? 1 : 2
            toks[i] << tok(mc.begin(g), mc.end(g), g == 1 ? 'meta-delim' : 'meta-separator')
            masked = line[0...mc.end(g)].gsub(/[^ \t]/, ' ') + line[mc.end(g)..]
            emit_line(toks[i], masked, :meta_start, false)
            next
          end

          # --- `!!block` alone on a line = a SUBJECT opener ------------------
          if (bo = BLOCK_OPENER.match(line)) && trim_ws(line[0...bo.begin(0)]).empty?
            emit_block_opener(toks[i], bo)
            verbatim = true
            block_end = bo[3]
            next
          end

          # --- the gate ------------------------------------------------------
          next unless GATE.match?(line)   # NotATriple: no spans at all

          st = emit_line(toks[i], line, :subject, false)
          if st[:opens_block]
            verbatim = true
            block_end = st[:block_end]
          elsif st[:opens_meta]
            in_meta = true
          end
        end
        toks
      end

      def toks_add_block_end(list, line)
        add_trimmed(list, line, 'block-end')
      end

      # A `!!block` opener is valid ONLY in a value position. The spec names
      # four (Parse Spec "block-as-field"): Subject, Object, MetaObject, and
      # the free meta text after `,,` (:meta_start). Relation and MetaRelation
      # hold names, so a `!!block` there is ordinary in-text command text.
      def opens_block_in?(phase)
        phase == :subject || phase == :object ||
          phase == :meta_obj || phase == :meta_start
      end

      # Emit one line as a sequence of slots and operators, starting in
      # `phase`. Mirrors the parse automaton: the object may contain `..`,
      # `;;` separates inline meta entries, and a `!!block` that ends the
      # line opens a verbatim region.
      def emit_line(list, line, phase, in_block)
        st = { opens_block: false, opens_meta: false, block_end: nil }
        last = 0
        scan = 0
        # Inside a MetaTextInline / MetaTextBlockLine: the current entry is a note, so every
        # token up to the next `;;` (inline) or the end of the line (block) is its content.
        note = false

        while (m = SIGNIFICANT.match(line, scan))
          sep       = m[0]
          sep_start = m.begin(0)
          sep_end   = m.end(0)
          scan      = m.end(0)   # scanning always resumes after the FULL match

          is_quad  = sep.length == 4 || sep.include?(' ') || sep.include?("\t")
          is_comma = sep == ',,'
          is_semi  = sep == ';;'

          # Once in an object-ish phase, `..` is content, not a separator.
          next if !is_comma && !is_semi && (phase == :object || phase == :meta_obj)
          # `;;` separates inline meta ENTRIES, unconditionally: it ends a free-text
          # note (:meta_start) exactly as it ends a pair's object (:meta_obj), with no
          # rule about what follows (36-inline-meta-notes, 37-inline-meta-mixed).
          # Outside the meta part, and in a :meta_rel, it is plain text; inside a `,,`
          # block newlines separate the entries, so it is ordinary content there
          # (28-semicolon-in-meta-block).
          next if is_semi && (in_block || !%i[meta_start meta_next meta_obj].include?(phase))

          # :meta_start decides per ENTRY whether it is a pair or a note. A leading `..`
          # opens a pair only if `[a MetaTripleInline derives]` — the automaton's one
          # lookahead, bounded by the entry — otherwise that same `..` is the note's first
          # character (`,, ..bla` is the note `..bla`, not a dangling relation). Anything
          # else — text, `,,`, `!!` — starts a note too, and once a note has started only
          # `;;` leaves it: MetaTextInline self-loops on `DT2 | DT4 | CM2 | EM2 | TX`.
          if !is_semi && !note && %i[meta_start meta_next].include?(phase)
            note = is_comma ||
                   !trim_ws(line[last...sep_start]).empty? ||
                   !meta_pair_derives?(line, sep_end, is_quad, entry_end(line, sep_start, in_block))
          end
          next if note && !is_semi
          next if is_comma && phase != :object

          # The relation-closing operator is always a plain `..`, never the
          # untyped `.. ..` form — otherwise an object starting with `..`
          # (a relative path) merges with the closing marker (20-object-dotdot).
          if is_quad && sep.length != 4 && (phase == :relation || phase == :meta_rel)
            is_quad = false
            sep     = line[sep_start, 2]
            sep_end = sep_start + 2
          end

          # A `!!block` ending the line makes the pending slot a block opener —
          # but only when that slot is a value position (block-as-field).
          if opens_block_in?(phase)
            bo = BLOCK_OPENER.match(line, last)
            if bo && trim_ws(line[last...bo.begin(0)]).empty? && bo.begin(0) < m.begin(0)
              emit_block_opener(list, bo)
              st[:opens_block] = true
              st[:block_end]   = bo[3]
              return st
            end
          end

          emit_slot(list, line, last, sep_start, phase)
          sep_type = if is_comma then 'meta-delim'
                     elsif is_semi then 'meta-separator'
                     elsif %i[meta_start meta_next meta_rel meta_obj].include?(phase) || in_block
                       'meta-doubledot'
                     else
                       'doubledot'
                     end
          list << tok(sep_start, sep_end, sep_type)
          last = sep_end

          phase = case phase
                  when :subject    then is_quad ? :object : :relation
                  when :relation   then :object
                  when :object     then is_comma ? :meta_start : :object
                  # `;;` starts the next entry in :meta_next, NOT :meta_start: the two admit
                  # the same things, but only :meta_start can still turn into a block opener, so
                  # a line ending in `,, ;;` must not be mistaken for one.
                  when :meta_start, :meta_next
                    then is_semi ? :meta_next : (is_quad ? :meta_obj : :meta_rel)
                  when :meta_rel   then :meta_obj
                  when :meta_obj   then is_semi ? :meta_next : :meta_obj
                  end
          note = false if is_semi   # a fresh entry begins after every `;;`
        end

        # Trailing slot, or a block opener with no separator after it. The
        # meta-text position lands here: `… ,, !!block` leaves phase
        # :meta_start with nothing but the opener left on the line.
        if opens_block_in?(phase)
          bo = BLOCK_OPENER.match(line, last)
          if bo && trim_ws(line[last...bo.begin(0)]).empty?
            emit_block_opener(list, bo)
            st[:opens_block] = true
            st[:block_end]   = bo[3]
            return st
          end
        end
        if trim_ws(line[last..]).empty? && phase == :meta_start
          st[:opens_meta] = true   # trailing `,,` opened a block
        else
          emit_slot(list, line, last, line.length, phase)
        end
        st
      end

      # Where the inline meta entry starting at `from` ends: at the next `;;`, or at the end
      # of the line if there is none. In a `,,` block NL is the separator and `;;` is content,
      # so the entry is the whole line. This is the bound on the lookahead below — an entry
      # ends at the next SC2 or NL, so the guard never has to look past one entry.
      def entry_end(line, from, in_block)
        return line.length if in_block

        m = SC2_ONLY.match(line, from)
        m ? m.begin(0) : line.length
      end

      # The automaton's one guard, `[a MetaTripleInline derives]`: does the marker just
      # consumed — ending at `marker_end`, within the entry ending at `entry_end_pos` —
      # actually open a meta pair? A QuadDot needs only a non-empty object; a DoubleDot needs
      # a non-empty MetaRelation, a closing `..`, and a non-empty object after it. When this
      # is false the marker is the first character of a note — `MetaEntryInline` is an ORDERED
      # choice with MetaTextInline last.
      def meta_pair_derives?(line, marker_end, quad, entry_end_pos)
        return false if marker_end > entry_end_pos
        return !trim_ws(line[marker_end...entry_end_pos]).empty? if quad

        scan = marker_end
        while (m = SIGNIFICANT.match(line, scan))
          return false if m.begin(0) >= entry_end_pos

          s = m[0]
          # `,,` and `;;` are ordinary content in a MetaRelation (`Name`) and, in a block, in
          # the object too; only a `..` can close the relation.
          if s == ',,' || s == ';;'
            scan = m.end(0)
            next
          end
          # A spaced `.. ..` closes with its LEADING `..`, the same demotion the caller applies
          # in the relation slot (20-object-dotdot); a literal `....` does not.
          spaced_quad = s.length != 4 && (s.include?(' ') || s.include?("\t"))
          close_end = spaced_quad ? m.begin(0) + 2 : m.end(0)
          return !trim_ws(line[marker_end...m.begin(0)]).empty? &&
                 !trim_ws(line[close_end...entry_end_pos]).empty?
        end
        false
      end

      def emit_block_opener(list, bo)
        list << tok(bo.begin(1), bo.end(1), 'command')
        if bo[2]
          list << tok(bo.begin(2), bo.end(2), 'command-param')
          list << tok(bo.begin(3), bo.end(3), 'block-end')
        end
      end

      def emit_slot(list, line, lo, hi, phase)
        s = lo
        e = hi
        s += 1 while s < e && line[s] =~ WS_RE
        e -= 1 while e > s && line[e - 1] =~ WS_RE
        return if s >= e

        text = line[s...e]
        type = if CMD_ONLY.match?(text)
                 'command'   # a slot whose whole text is a command IS a command
               else
                 case phase
                 when :subject    then 'subject'
                 when :relation   then 'relation'
                 when :object     then 'object'
                 when :meta_rel   then 'meta-relation'
                 when :meta_obj   then 'meta-object'
                 else                  'meta-text'   # :meta_start/:meta_next — entry text with no `..`
                 end
               end
        list << tok(s, e, type)
      end

      # --- HTML emission ----------------------------------------------------

      def render_line(line, toks, out)
        pos = 0
        toks.each do |t|
          out << escape(line[pos...t[:s]]) if t[:s] > pos
          render_token(line[t[:s]...t[:e]], t[:t], out)
          pos = t[:e]
        end
        out << escape(line[pos..]) if pos < line.length
      end

      def render_token(text, type, out)
        if type == 'command'
          command_span(text, out)
        elsif SLOT_TOKENS.include?(type)
          out << %(<span class="#{TOKEN_CLASS[type]}">)
          slot_inner(text, out)
          out << '</span>'
        else
          out << %(<span class="#{TOKEN_CLASS[type]}">#{escape(text)}</span>)
        end
      end

      # `?query` / `#fragment` render as a nested command-param span.
      def command_span(text, out)
        idx = text =~ /[?#]/
        if idx
          out << %(<span class="ddot-command">#{escape(text[0...idx])}) \
              << %(<span class="ddot-command-param">#{escape(text[idx..])}</span></span>)
        else
          out << %(<span class="ddot-command">#{escape(text)}</span>)
        end
      end

      # Slot content with nested in-text command spans (Highlight Spec D6).
      def slot_inner(text, out)
        pos = 0
        while (m = NESTED_CMD.match(text, pos))
          out << escape(text[pos...m.begin(0)])
          command_span(m[0], out)
          pos = m.end(0)
        end
        out << escape(text[pos..])
      end

      def escape(s)
        s.to_s.gsub('&', '&amp;')
               .gsub('<', '&lt;')
               .gsub('>', '&gt;')
               .gsub('"', '&quot;')
               .gsub("'", '&#39;')
      end
    end
  end
end
