# Revv's version from a release tag: v1.2.3 is version name 1.2.3 and version code 1002003, so
# codes always grow with the version, as Google Play requires. A suffix (v1.2.3-beta.1) stays in
# the name only.
module RevvVersion
  TAG = /\Av(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.-]+)?\z/

  module_function

  # { name:, code:, prerelease: } for [tag], or nil if it isn't vMAJOR.MINOR.PATCH.
  def from_tag(tag)
    parts = TAG.match(tag.to_s) or return nil
    major, minor, patch = parts.captures.map(&:to_i)
    return nil if minor > 999 || patch > 999
    name = tag.delete_prefix("v")
    { name: name, code: major * 1_000_000 + minor * 1_000 + patch, prerelease: name.include?("-") }
  end
end
