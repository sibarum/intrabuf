package sibarum.intrabuf.endpoint;

/** A schema version, {@code major.minor}, ordered by major and then minor. */
public record Version(int major, int minor) implements Comparable<Version> {

    public Version {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("version " + major + "." + minor + " is negative");
        }
    }

    /** Parses {@code "major.minor"}. */
    public static Version parse(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1) {
            throw new IllegalArgumentException("'" + s + "' is not a version (major.minor)");
        }
        try {
            return new Version(Integer.parseInt(s.substring(0, dot)), Integer.parseInt(s.substring(dot + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a version (major.minor)");
        }
    }

    @Override
    public int compareTo(Version o) {
        return major != o.major ? Integer.compare(major, o.major) : Integer.compare(minor, o.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
