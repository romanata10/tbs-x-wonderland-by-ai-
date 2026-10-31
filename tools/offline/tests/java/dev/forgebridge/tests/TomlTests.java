package dev.forgebridge.tests;

import dev.forgebridge.jar.ModDescriptor;
import dev.forgebridge.jar.ModsTomlRewriter;
import dev.forgebridge.jar.PatchOptions;
import dev.forgebridge.jar.TomlDocument;

import java.util.List;

/** Descriptor parsing and rewriting: the part that most often breaks a translated mod. */
public final class TomlTests {

    private TomlTests() {
    }

    private static PatchOptions options() {
        return new PatchOptions()
                .setBridgeVersion("1.0.0")
                .setBridgeModId("forgebridge")
                .setLoaderVersionRange("[4,)")
                .setNeoforgeDependencyRange("[21.1.0,)");
    }

    public static void testBlocksAreSplitOnHeaders() {
        TomlDocument document = TomlDocument.of(Fixtures.FORGE_MODS_TOML);
        Assert.equalInt(4, document.getBlocks().size(), "root, mods and two dependency blocks");
        TomlDocument.Block modsEntry = (TomlDocument.Block) document.getBlocks().get(1);
        Assert.isTrue(modsEntry.isModsEntry(), "second block is the mods entry");
        TomlDocument.Block root = document.root();
        Assert.notNull(root, "root block");
        Assert.isTrue(root.isRoot(), "first block is the root block");
        Assert.equal("javafml", root.getValues().get("modLoader"), "root key parsed");
        List<TomlDocument.Block> dependencies = document.dependencyBlocks();
        Assert.equalInt(2, dependencies.size(), "dependency blocks");
        TomlDocument.Block firstDependency = (TomlDocument.Block) dependencies.get(0);
        Assert.equal("dependencies.examplemod", firstDependency.getHeader(), "dependency header");
        Assert.isTrue(firstDependency.isDependency(), "block marked as dependency");
    }

    public static void testLineIndexTracksKeysThroughCommentsAndMultilineStrings() {
        TomlDocument document = TomlDocument.of(Fixtures.FORGE_MODS_TOML);
        TomlDocument.Block root = document.root();
        Integer loaderLine = root.lineOf("loaderVersion");
        Assert.notNull(loaderLine, "loaderVersion line number");
        Assert.contains(document.rawLine(loaderLine.intValue()), "loaderVersion=", "line lookup is exact");
        // The single-quoted description contains a period and a digit; it must not be parsed as a key.
        Assert.equal(null, root.lineOf("A mod"), "multiline string body is not a key");
    }

    public static void testDescriptorParsesForgeMetadata() {
        ModDescriptor descriptor = ModDescriptor.parse(Fixtures.FORGE_MODS_TOML);
        Assert.equal("examplemod", descriptor.getModId(), "mod id");
        Assert.equal("1.2.3", descriptor.getVersion(), "version");
        Assert.equal("Example Legacy Mod", descriptor.getDisplayName(), "display name");
        Assert.equal("javafml", descriptor.getModLoader(), "mod loader");
        Assert.equal("[47,)", descriptor.getLoaderVersion(), "loader version");
        Assert.isTrue(descriptor.dependsOnForge(), "depends on forge");
        Assert.isTrue(descriptor.dependsOn("minecraft"), "depends on minecraft");
        Assert.isTrue(descriptor.looksLikeForge(), "descriptor looks like a Forge descriptor");
        Assert.isFalse(descriptor.dependsOn("forgebridge"), "bridge dependency not present yet");
    }

    public static void testVersionRangeParsing() {
        Assert.equalInt(47, ModDescriptor.firstVersionNumber("[47,1.22)"), "lower bound");
        Assert.equalInt(1, ModDescriptor.firstVersionNumber("[1.21,1.22)"), "minecraft bound");
        Assert.equalInt(-1, ModDescriptor.firstVersionNumber(""), "empty range");
        Assert.equalInt(-1, ModDescriptor.firstVersionNumber((String) null), "null range");
    }

    public static void testRewriteMovesTheDescriptorToNeoForge() {
        ModsTomlRewriter.Result result = ModsTomlRewriter.rewrite(Fixtures.FORGE_MODS_TOML, options());
        Assert.isTrue(result.isChanged(), "descriptor changed");
        Assert.isTrue(result.isLegacyForge(), "descriptor detected as legacy");
        String text = result.getText();

        Assert.contains(text, "loaderVersion=\"[4,)\"", "loader version widened for FML 4");
        Assert.notContains(text, "loaderVersion=\"[47,)\"", "old loader version gone");
        Assert.contains(text, "modId=\"neoforge\"", "forge dependency renamed to neoforge");
        Assert.contains(text, "versionRange=\"[21.1.0,)\"", "neoforge dependency range");
        Assert.notContains(text, "modId=\"forge\"", "forge dependency gone");
        Assert.contains(text, "modId=\"minecraft\"", "unrelated dependency preserved");
        Assert.contains(text, "versionRange=\"[1.21,1.22)\"", "unrelated range preserved");
        Assert.contains(text, "modId=\"forgebridge\"", "optional bridge dependency added");
        Assert.contains(text, "type=\"optional\"", "bridge dependency is optional");
        Assert.contains(text, "displayName=\"Example Legacy Mod\"", "metadata preserved");
    }

    public static void testRewriteAddsAMissingLicense() {
        String withoutLicense = "modLoader=\"javafml\"\n"
                + "loaderVersion=\"[47,)\"\n"
                + "\n[[mods]]\n"
                + "    modId=\"examplemod\"\n";
        ModsTomlRewriter.Result result = ModsTomlRewriter.rewrite(withoutLicense, options());
        Assert.contains(result.getText(), "license=\"All-Rights-Reserved\"", "license inserted");
        Assert.contains(result.getText(), "inserted by ForgeBridge", "insertion is marked");
    }

    public static void testRewriteKeepsCommentsAndIsIdempotent() {
        String withComments = "# top comment\n"
                + "modLoader=\"javafml\"   # inline comment\n"
                + "loaderVersion=\"[47,)\"\n"
                + "license=\"MIT\"\n"
                + "\n[[mods]]\n"
                + "    modId=\"examplemod\"\n"
                + "    version=\"1.0.0\"\n";
        ModsTomlRewriter.Result first = ModsTomlRewriter.rewrite(withComments, options());
        Assert.contains(first.getText(), "# top comment", "leading comment kept");
        Assert.contains(first.getText(), "# inline comment", "inline comment kept");
        ModsTomlRewriter.Result second = ModsTomlRewriter.rewrite(first.getText(), options());
        Assert.isFalse(second.isChanged(), "second pass has nothing left to do");
    }

    public static void testRewriteLeavesModernDescriptorsAlone() {
        String modern = "modLoader=\"javafml\"\n"
                + "loaderVersion=\"[4,)\"\n"
                + "license=\"MIT\"\n"
                + "\n[[mods]]\n"
                + "    modId=\"native_mod\"\n"
                + "\n[[dependencies.native_mod]]\n"
                + "    modId=\"neoforge\"\n"
                + "    type=\"required\"\n"
                + "    versionRange=\"[21.1.0,)\"\n";
        ModsTomlRewriter.Result result = ModsTomlRewriter.rewrite(modern, options());
        Assert.isFalse(result.isLegacyForge(), "not a legacy descriptor");
        Assert.isFalse(result.isChanged(), "already modern descriptor is untouched");
        Assert.contains(result.getText(), "modId=\"neoforge\"", "neoforge dependency kept");
    }

    public static void testRenderRoundTripsByteForByte() {
        TomlDocument document = TomlDocument.of(Fixtures.FORGE_MODS_TOML);
        Assert.equal(Fixtures.FORGE_MODS_TOML, document.render(), "render is lossless for untouched input");
    }
}
