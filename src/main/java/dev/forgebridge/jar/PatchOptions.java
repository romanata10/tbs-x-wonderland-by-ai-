package dev.forgebridge.jar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Knobs that control how a jar is translated. */
public final class PatchOptions {

    private String bridgeModId = "forgebridge";
    private String bridgeVersion = "1.0.0";
    private String neoforgeDependencyRange = "[21.1.0,)";
    private String loaderVersionRange = "[4,)";
    private String minecraftVersionRange = "[1.21.1,1.22)";
    private boolean rewriteTagsToCommonNamespace = true;
    private boolean rewriteRecipesConditions = true;
    private boolean upgradeMixinCompatibilityLevel = true;
    private boolean dropSignatures = true;
    private boolean addOptionalBridgeDependency = true;
    private boolean dryRun = false;
    private List<String> extraLegacyNamespaces = new ArrayList<String>(Arrays.asList("forge"));
    private java.util.Map<String, byte[]> injectedEntries = new java.util.LinkedHashMap<String, byte[]>();

    public String getBridgeModId() {
        return bridgeModId;
    }

    public PatchOptions setBridgeModId(String value) {
        this.bridgeModId = value;
        return this;
    }

    public String getBridgeVersion() {
        return bridgeVersion;
    }

    public PatchOptions setBridgeVersion(String value) {
        this.bridgeVersion = value;
        return this;
    }

    public String getNeoforgeDependencyRange() {
        return neoforgeDependencyRange;
    }

    public PatchOptions setNeoforgeDependencyRange(String value) {
        this.neoforgeDependencyRange = value;
        return this;
    }

    public String getLoaderVersionRange() {
        return loaderVersionRange;
    }

    public PatchOptions setLoaderVersionRange(String value) {
        this.loaderVersionRange = value;
        return this;
    }

    /** The {@code minecraft} dependency range a translated descriptor declares. */
    public String getMinecraftVersionRange() {
        return minecraftVersionRange;
    }

    public PatchOptions setMinecraftVersionRange(String value) {
        this.minecraftVersionRange = value;
        return this;
    }

    public boolean isRewriteTagsToCommonNamespace() {
        return rewriteTagsToCommonNamespace;
    }

    public PatchOptions setRewriteTagsToCommonNamespace(boolean value) {
        this.rewriteTagsToCommonNamespace = value;
        return this;
    }

    public boolean isRewriteRecipesConditions() {
        return rewriteRecipesConditions;
    }

    public PatchOptions setRewriteRecipesConditions(boolean value) {
        this.rewriteRecipesConditions = value;
        return this;
    }

    public boolean isUpgradeMixinCompatibilityLevel() {
        return upgradeMixinCompatibilityLevel;
    }

    public PatchOptions setUpgradeMixinCompatibilityLevel(boolean value) {
        this.upgradeMixinCompatibilityLevel = value;
        return this;
    }

    public boolean isDropSignatures() {
        return dropSignatures;
    }

    public PatchOptions setDropSignatures(boolean value) {
        this.dropSignatures = value;
        return this;
    }

    public boolean isAddOptionalBridgeDependency() {
        return addOptionalBridgeDependency;
    }

    public PatchOptions setAddOptionalBridgeDependency(boolean value) {
        this.addOptionalBridgeDependency = value;
        return this;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public PatchOptions setDryRun(boolean value) {
        this.dryRun = value;
        return this;
    }

    /** Extra class entries (placeholder stubs) to add to the translated jar. */
    public java.util.Map<String, byte[]> getInjectedEntries() {
        return injectedEntries;
    }

    public PatchOptions setInjectedEntries(java.util.Map<String, byte[]> value) {
        this.injectedEntries = value == null ? new java.util.LinkedHashMap<String, byte[]>() : value;
        return this;
    }

    public List<String> getExtraLegacyNamespaces() {
        return extraLegacyNamespaces;
    }

    public PatchOptions setExtraLegacyNamespaces(List<String> value) {
        this.extraLegacyNamespaces = value == null ? new ArrayList<String>() : value;
        return this;
    }
}
