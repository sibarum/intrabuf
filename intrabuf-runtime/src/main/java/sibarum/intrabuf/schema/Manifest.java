package sibarum.intrabuf.schema;

import java.util.List;

/**
 * Every schema one compilation generated a codec for, plus every schema those reach in other modules, so a single
 * manifest is enough to decode any payload its build writes. Written by the processor to
 * {@code META-INF/intrabuf/<module>.json}.
 *
 * @param format       manifest format version
 * @param wireVersion  the payload wire format the build writes
 * @param module       name the build gave itself ({@code -Aintrabuf.module})
 * @param buildVersion version of the build ({@code -Aintrabuf.buildVersion})
 * @param snapshot     whether the build was a snapshot
 * @param headers      the build's own {@code @IntrabufHeader} formats
 */
public record Manifest(int format, int wireVersion, String module, String buildVersion, boolean snapshot,
                       List<MessageDef> messages, List<EnumDef> enums, List<HeaderDef> headers) {

    public static final int FORMAT = 1;
    public static final String RESOURCE_DIR = "META-INF/intrabuf/";

    public Manifest {
        messages = List.copyOf(messages);
        enums = List.copyOf(enums);
        headers = List.copyOf(headers);
    }
}
