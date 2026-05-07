# frozen_string_literal: true
#
# Pure-Ruby ddot.it preview renderer. NO Asciidoctor / IntelliJ dependency.
#
# Mirrors the role-assignment logic in
# `com.calpano.ddot.preview.DdotMarkdownPreviewRenderer` so the AsciiDoc
# preview matches the editor and the Markdown preview.
#
# Two callers consume this file:
#   1. `ddot-extension.rb` — the AsciidoctorJ BlockProcessor that runs inside
#      the JetBrains AsciiDoc plugin (loaded from `<root>/.asciidoctor/lib/`).
#   2. `../ddot.it/test-data/regenerate-html.rb` — the golden-test
#      corpus regeneration script (canonical source of `expected.body.html`
#      files used by both Java and Ruby renderer tests).
#
# Keep the role-assignment rules in sync with DdotMarkdownPreviewRenderer.java.
# When you change either side, regenerate the corpus and run both test suites.

module DdotIt
  module Render
    SEP_RE        = /\.{4}|\.{2}/
    OFF           = 'ddot.it/off'
    ON            = 'ddot.it/on'
    # `!!` is shorthand for `ddot.it/`; both directive forms are equivalent.
    OFF_SHORT     = '!!off'
    ON_SHORT      = '!!on'
    META_TOGGLE   = ',,'
    COMMAND_TOKEN = '!!'

    # Default colour palette mirroring DdotSyntaxHighlighter's IntelliJ
    # defaults. The editor renderer pulls from the active EditorColorsScheme;
    # here we have no scheme available (Asciidoctor runs headless inside
    # JRuby), so we ship sensible Light-theme defaults. Override per-document
    # via :ddot-color-* attributes from inside .adoc.
    DEFAULT_COLORS = {
      subject:   '#871094',
      predicate: '#1750EB',
      object:    '#067D17',
      meta:      '#8C8C8C',
      command:   '#871094',
      separator: '#707070',
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
        <<~CSS.chomp
          <style>
            .ddot-fence{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
            .ddot-fence .ddot-subject{color:#{colors[:subject]};}
            .ddot-fence .ddot-predicate{color:#{colors[:predicate]};}
            .ddot-fence .ddot-object{color:#{colors[:object]};}
            .ddot-fence .ddot-meta,.ddot-fence .ddot-meta-sep{color:#{colors[:meta]};font-style:italic;}
            .ddot-fence .ddot-command{color:#{colors[:command]};font-weight:bold;}
            .ddot-fence .ddot-sep{color:#{colors[:separator]};}
            .ddot-fence .ddot-inactive,.ddot-fence .ddot-directive{color:#{colors[:inactive]};font-style:italic;opacity:0.7;}
          </style>
        CSS
      end

      private

      def render_body(raw, out)
        body  = raw.end_with?("\n") ? raw[0...-1] : raw
        lines = body.split("\n", -1)

        in_meta_block = false
        off           = false
        first_line    = true

        lines.each do |line|
          out << "\n" unless first_line
          first_line = false

          trimmed = line.strip
          if trimmed == OFF || trimmed == OFF_SHORT
            render_inactive(line, out, directive: true)
            off = true
            next
          end
          if trimmed == ON || trimmed == ON_SHORT
            render_inactive(line, out, directive: true)
            off = false
            next
          end
          if off
            render_inactive(line, out, directive: false)
            next
          end
          if trimmed == META_TOGGLE
            render_inline(line, out, 'ddot-meta-sep')
            in_meta_block = !in_meta_block
            next
          end
          if in_meta_block
            render_inline(line, out, 'ddot-meta')
            next
          end

          render_triple_line(line, out)
        end
      end

      def render_triple_line(line, out)
        meta_start = line.index(',,')
        body, tail = if meta_start
                       [line[0...meta_start], line[meta_start..]]
                     else
                       [line, '']
                     end

        lead_end = 0
        lead_end += 1 while lead_end < body.length && body[lead_end] =~ /\s/
        out << escape(body[0...lead_end])
        content = body[lead_end..]

        # Slot starts at 0 (subject); a leading separator on a continuation
        # line advances it to 1, naturally classing the next segment as the
        # predicate. Don't pre-set slot=1 — that double-counts the leading
        # `..` and mis-classes continuation predicates as objects.
        slot = 0
        last = 0
        any  = false

        content.scan(SEP_RE) do |sep|
          any = true
          m_start = Regexp.last_match.begin(0)
          m_end   = Regexp.last_match.end(0)
          render_segment(content[last...m_start], slot, out)
          out << %(<span class="ddot-sep">#{escape(sep)}</span>)
          slot = [slot + (sep.length == 4 ? 2 : 1), 2].min
          last = m_end
        end

        trailing = content[last..]
        if any
          render_segment(trailing, slot, out)
        else
          render_free_form(trailing, out)
        end

        out << %(<span class="ddot-meta">#{escape(tail)}</span>) unless tail.empty?
      end

      def render_segment(segment, slot, out)
        s = 0
        e = segment.length
        s += 1 while s < e && segment[s] =~ /\s/
        e -= 1 while e > s && segment[e - 1] =~ /\s/
        out << escape(segment[0...s]) if s > 0
        text = segment[s...e]
        unless text.empty?
          out << %(<span class="#{role_class(slot, text)}">#{escape(text)}</span>)
        end
        out << escape(segment[e..]) if e < segment.length
      end

      def role_class(slot, text)
        return 'ddot-command' if text.start_with?('ddot.it') || text == COMMAND_TOKEN
        case [slot, 2].min
        when 0 then 'ddot-subject'
        when 1 then 'ddot-predicate'
        else        'ddot-object'
        end
      end

      def render_free_form(text, out)
        trimmed = text.strip
        if trimmed.start_with?('ddot.it') || trimmed == COMMAND_TOKEN
          s = 0
          s += 1 while s < text.length && text[s] =~ /\s/
          e = text.length
          e -= 1 while e > s && text[e - 1] =~ /\s/
          out << escape(text[0...s]) if s > 0
          out << %(<span class="ddot-command">#{escape(text[s...e])}</span>)
          out << escape(text[e..]) if e < text.length
        else
          out << escape(text)
        end
      end

      def render_inline(line, out, css_class)
        out << %(<span class="#{css_class}">#{escape(line)}</span>)
      end

      def render_inactive(line, out, directive:)
        css_class = directive ? 'ddot-directive' : 'ddot-inactive'
        out << %(<span class="#{css_class}">#{escape(line)}</span>)
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
