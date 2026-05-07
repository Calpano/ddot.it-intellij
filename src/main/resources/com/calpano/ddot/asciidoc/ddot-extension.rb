# frozen_string_literal: true
#
# ddot.it AsciidoctorJ extension — role-aware preview rendering for ddot
# listing blocks. Mirrors the role assignment in
# `com.calpano.ddot.preview.DdotMarkdownPreviewRenderer` (subject / predicate /
# object / metadata / command) so the AsciiDoc preview matches the editor.
#
# Supported block forms (matching `DdotAsciiDocInjector`):
#   [ddot]       …   ----  …  ----   # via DdotBlockProcessor
#   [ddotit]     …   ----  …  ----   # via DdotBlockProcessor
#   [source,ddot]      …  ----  …    # via DdotSourceTreeprocessor
#   [source,ddot.it]   …  ----  …    # via DdotSourceTreeprocessor
#   [source,ddotit]    …  ----  …    # via DdotSourceTreeprocessor
#
# `[ddot.it]` (with a dot in the bare block style) isn't supported here:
# Asciidoctor's block-style grammar doesn't accept dots, so a BlockProcessor
# named :'ddot.it' wouldn't match anyway. Use `[source,ddot.it]` instead.
#
# Modeled after the kroki-extension.rb / mermaid-extension.rb that ship inside
# the asciidoctor-intellij-plugin jar. We can't load this from the IntelliJ
# plugin's classpath because the asciidoc plugin doesn't expose an extension
# point for third-party plugins to register AsciidoctorJ extensions — only two
# EPs (`html.panel.provider`, `asciidocRunner`) are public. Instead, the
# asciidoc plugin's `AsciiDocExtensionService` scans `<contentRoot>/.asciidoctor/lib/`
# for `.rb` and `.jar` files and registers them with its embedded Asciidoctor.
# Our IntelliJ plugin ships this file (and its sibling `ddot-render.rb`) as
# resources and provides an action that copies both into that directory; the
# asciidoc plugin then prompts the user to trust+enable them via its standard
# banner.
#
# LONG-TERM (Option C in the design discussion): file an upstream PR adding an
# `org.asciidoc.intellij.asciidoctorExtensions` extension point so we can
# register this directly from `plugin.xml` instead of touching the user's repo.

require 'asciidoctor/extensions' unless RUBY_ENGINE == 'opal'
require_relative 'ddot-render'
# Register the Rouge lexer for `[source,ddot]` blocks. With
# `:source-highlighter: rouge` set in the .adoc, Asciidoctor dispatches
# matching source blocks through Rouge → our lexer → standard token spans
# (`tok-nc`, `tok-o` …) styled by the AsciiDoc plugin's Rouge theme. Without
# Rouge selected, the Treeprocessor below catches the same blocks instead.
begin
  require 'rouge'
  require_relative 'ddot'
rescue LoadError
  # Rouge unavailable in this environment — Treeprocessor fallback only.
end

module DdotIt
  module AsciidoctorExtensions
    DDOT_LANGUAGES = %w[ddot ddotit ddot.it].freeze

    # Resolve per-document colour overrides (`:ddot-color-subject:` etc.) on
    # top of the defaults from ddot-render.rb.
    def self.resolve_colors(document)
      DdotIt::Render::DEFAULT_COLORS.each_with_object({}) do |(role, fallback), acc|
        override = document && document.attr("ddot-color-#{role}")
        acc[role] = (override && !override.empty?) ? override : fallback
      end
    end

    # Emit raw HTML mirroring Asciidoctor's HTML5 converter output for a
    # `listingblock`, so titles, ids, roles AND the AsciiDoc plugin's source-
    # block CSS (font, padding, sizing) all apply to our rendered output.
    #
    # Asciidoctor's native source block emits:
    #   <div class="listingblock">[<div class="title">…</div>]
    #     <div class="content">
    #       <pre class="highlight"><code class="language-X" data-lang="X">…</code></pre>
    #     </div>
    #   </div>
    #
    # The CSS rule `.listingblock pre.highlight > code { font-size: 0.85em; }`
    # only fires when both `pre.highlight` and the inner `<code>` are present,
    # which is why a generic `<pre><code>` wrapper rendered slightly larger.
    # Mirroring the native shape exactly keeps font-size, padding and family
    # identical to other listing blocks.
    def self.render_pass_block(parent, body, title: nil, id: nil, roles: nil)
      inner_html = %(<pre class="highlight"><code class="language-ddot" data-lang="ddot">#{DdotIt::Render.body(body)}</code></pre>)
      full = "#{style_block_for(parent.document)}#{inner_html}"
      ::Asciidoctor::Block.new(parent, :pass,
                               source: wrap_listingblock(full, title: title, id: id, roles: roles),
                               content_model: :raw)
    end

    # Per-block <style> tag (browsers de-dupe the rules cheaply). Selectors are
    # unprefixed so they match our spans regardless of the AsciiDoc-imposed
    # wrapper hierarchy.
    def self.style_block_for(document)
      colors = resolve_colors(document)
      <<~CSS.chomp
        <style>
          .ddot-subject{color:#{colors[:subject]};}
          .ddot-predicate{color:#{colors[:predicate]};}
          .ddot-object{color:#{colors[:object]};}
          .ddot-meta,.ddot-meta-sep{color:#{colors[:meta]};font-style:italic;}
          .ddot-command{color:#{colors[:command]};font-weight:bold;}
          .ddot-sep{color:#{colors[:separator]};}
          .ddot-inactive,.ddot-directive{color:#{colors[:inactive]};font-style:italic;opacity:0.7;}
        </style>
      CSS
    end

    def self.wrap_listingblock(content_html, title:, id:, roles:)
      classes = ['listingblock']
      if roles && !roles.to_s.empty?
        classes.concat(roles.to_s.split)
      end
      id_attr = (id && !id.to_s.empty?) ? %( id="#{escape_html(id)}") : ''
      title_div = (title && !title.to_s.empty?) ? %(<div class="title">#{escape_html(title)}</div>) : ''
      %(<div#{id_attr} class="#{classes.join(' ')}">#{title_div}<div class="content">#{content_html}</div></div>)
    end

    def self.escape_html(s)
      s.to_s.gsub('&', '&amp;').gsub('<', '&lt;').gsub('>', '&gt;').gsub('"', '&quot;').gsub("'", '&#39;')
    end

    # `[ddot]` / `[ddotit]` — bare block-style dispatch, the simplest form.
    class DdotBlockProcessor < ::Asciidoctor::Extensions::BlockProcessor
      use_dsl
      on_context :listing
      content_model :raw

      def process(parent, reader, attrs)
        DdotIt::AsciidoctorExtensions.render_pass_block(
          parent, reader.read,
          title: attrs['title'], id: attrs['id'], roles: attrs['role'])
      end
    end

    # `[source,ddot]` family — Asciidoctor dispatches source blocks on style
    # `:source` regardless of language, so a BlockProcessor named `:ddot`
    # never sees them. The Rouge lexer (registered above when Rouge loads)
    # handles them natively when `:source-highlighter: rouge` is set; this
    # Treeprocessor is the fallback for documents that select a different
    # highlighter or none at all. We deliberately *skip* matching blocks
    # when Rouge is the active highlighter, otherwise the Treeprocessor
    # would pre-empt Rouge by replacing the block before conversion runs.
    class DdotSourceTreeprocessor < ::Asciidoctor::Extensions::Treeprocessor
      def process(document)
        @rouge_active = (document.attr('source-highlighter') == 'rouge')
        replace_in(document)
        nil
      end

      private

      def replace_in(parent)
        return unless parent.respond_to?(:blocks) && parent.blocks
        parent.blocks.each_with_index do |block, idx|
          if ddot_source_block?(block) && !@rouge_active
            parent.blocks[idx] = DdotIt::AsciidoctorExtensions.render_pass_block(
              parent, block.source,
              title: block.title, id: block.id, roles: block.roles ? block.roles.join(' ') : nil)
          else
            replace_in(block)
          end
        end
      end

      def ddot_source_block?(block)
        block.context == :listing &&
          block.style == 'source' &&
          DDOT_LANGUAGES.include?(block.attr('language'))
      end
    end
  end
end

::Asciidoctor::Extensions.register do
  block DdotIt::AsciidoctorExtensions::DdotBlockProcessor, :ddot
  block DdotIt::AsciidoctorExtensions::DdotBlockProcessor, :ddotit
  treeprocessor DdotIt::AsciidoctorExtensions::DdotSourceTreeprocessor
end
