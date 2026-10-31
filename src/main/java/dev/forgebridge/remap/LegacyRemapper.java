package dev.forgebridge.remap;

import org.objectweb.asm.commons.Remapper;

/**
 * {@link Remapper} implementation driving ASM's {@code ClassRemapper}.
 *
 * <p>Class names, descriptors, signatures, annotations, inner classes, catch types,
 * invokedynamic bootstrap arguments and constant pool handles are all translated by
 * ASM once these three methods are correct, which is why the bridge prefers this path
 * whenever {@code asm-commons} is on the class path.</p>
 */
public final class LegacyRemapper extends Remapper {

    private final MappingTable table;
    private int mappedClasses;

    public LegacyRemapper(MappingTable table) {
        this.table = table;
    }

    public int getMappedClassCount() {
        return mappedClasses;
    }

    @Override
    public String map(String internalName) {
        String mapped = table.mapClass(internalName);
        if (!mapped.equals(internalName)) {
            mappedClasses++;
        }
        return mapped;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        // ASM hands us the *original* owner here; the table stores post-rename owners
        // for the BuiltInRegistries flattening, so check both forms.
        String renamed = table.mapFieldName(owner, name, descriptor);
        if (!renamed.equals(name)) {
            return renamed;
        }
        return table.mapFieldName(table.mapClass(owner), name, descriptor);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        String renamed = table.mapMethodName(owner, name, descriptor);
        if (!renamed.equals(name)) {
            return renamed;
        }
        return table.mapMethodName(table.mapClass(owner), name, descriptor);
    }

    /**
     * Kept for completeness: annotation attribute names are never renamed by the
     * built-in table, but user mapping files may add such rules later.
     */
    public MappingTable getTable() {
        return table;
    }
}
