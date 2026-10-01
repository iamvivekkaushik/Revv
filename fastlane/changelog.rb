require "digest"

# Release notes for a GitHub release: every commit since the previous v* tag, with the bullet
# points from its message, then the downloads and their checksums. Git is run without a shell,
# so a tag's name can't inject anything.
module RevvChangelog
  # Lines git adds to messages that aren't part of the change itself.
  TRAILER = /\A(Co-Authored-By|Signed-off-by|Reviewed-by):/i

  module_function

  # The v* tag before [ref], or nil for the first release.
  def previous_tag(ref)
    out = IO.popen(["git", "describe", "--tags", "--abbrev=0", "--match", "v*", "#{ref}^"], err: File::NULL, &:read).to_s.strip
    $?.success? && !out.empty? ? out : nil
  end

  # [sha, subject, body] for each commit in [range], newest first, merges left out.
  def commits(range)
    raw = IO.popen(["git", "log", "--no-merges", "--format=%x1e%h%x1f%s%x1f%b", range], &:read).to_s
    raw.split("\x1e").map(&:strip).reject(&:empty?).map { |commit| commit.split("\x1f", 3) }
  end

  # Markdown for the release of [tag]; [assets] are the files attached to it.
  def notes(tag, repository: nil, assets: [])
    previous = previous_tag(tag)
    lines = ["## What's changed", ""]
    commits(previous ? "#{previous}..#{tag}" : tag).each do |sha, subject, body|
      lines << "- **#{subject.strip}** (#{sha})"
      in_bullet = false
      body.to_s.lines.map(&:rstrip).reject { |line| line.strip.empty? || line.strip =~ TRAILER }.each do |line|
        # The message's own bullets become sub-points; a wrapped line continues the point above it.
        if line =~ /\A\s*[-*] /
          in_bullet = true
          lines << "  - #{line.sub(/\A\s*[-*] /, '')}"
        else
          lines << "#{in_bullet ? '    ' : '  '}#{line.strip}"
        end
      end
    end
    lines << "- First release" if lines.size == 2

    if repository && previous
      lines += ["", "**Full changelog:** https://github.com/#{repository}/compare/#{previous}...#{tag}"]
    end

    unless assets.empty?
      lines += ["", "## Downloads", ""]
      assets.each do |path|
        name = File.basename(path)
        what = name.end_with?(".apk") ? "install this on the head unit" : "app bundle, for app stores"
        lines << "- `#{name}`: #{what}"
      end
      lines += ["", "SHA-256 checksums:", "", "```"]
      assets.each { |path| lines << "#{Digest::SHA256.file(path).hexdigest}  #{File.basename(path)}" }
      lines << "```"
    end
    lines.join("\n") + "\n"
  end
end
