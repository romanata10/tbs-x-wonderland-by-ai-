package dev.forgebridge.jar;

import dev.forgebridge.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Accumulates everything that happened while translating one jar. */
public final class PatchReport {

    private final String sourceName;
    private String outputName;
    private int classesTotal;
    private int classesRemapped;
    private int classesBlocked;
    private int resourcesRewritten;
    private int entriesSkipped;
    private int redirectCount;
    private boolean legacyMod;
    private String detectedModId;
    private String detectedVersion;
    private String pipeline = "unknown";
    private final Set<String> residualReferences = new LinkedHashSet<String>();
    private final Set<String> blockedTypes = new LinkedHashSet<String>();
    private final List<String> rewrites = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();
    private final Map<String, Integer> counters = new LinkedHashMap<String, Integer>();

    public PatchReport(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getOutputName() {
        return outputName;
    }

    public void setOutputName(String outputName) {
        this.outputName = outputName;
    }

    public void setLegacyMod(boolean legacyMod) {
        this.legacyMod = legacyMod;
    }

    public boolean isLegacyMod() {
        return legacyMod;
    }

    public void setDetectedMod(String modId, String version) {
        this.detectedModId = modId;
        this.detectedVersion = version;
    }

    public String getDetectedModId() {
        return detectedModId;
    }

    public String getDetectedVersion() {
        return detectedVersion;
    }

    public void setPipeline(String pipeline) {
        this.pipeline = pipeline;
    }

    public String getPipeline() {
        return pipeline;
    }

    public void countClass(String entryName, boolean remapped) {
        classesTotal++;
        if (remapped) {
            classesRemapped++;
        }
    }

    public void blockType(String internalName) {
        if (blockedTypes.add(internalName)) {
            classesBlocked++;
        }
    }

    public Set<String> getBlockedTypes() {
        return blockedTypes;
    }

    public void countResource(String entryName, String what) {
        resourcesRewritten++;
        rewrites.add(entryName + " -> " + what);
    }

    public void skipEntry(String entryName, String reason) {
        entriesSkipped++;
        rewrites.add(entryName + " skipped (" + reason + ")");
    }

    public void addRedirect(String description) {
        redirectCount++;
        rewrites.add("redirect " + description);
    }

    public int getRedirectCount() {
        return redirectCount;
    }

    public void addResidualReferences(List<String> references) {
        residualReferences.addAll(references);
    }

    public Set<String> getResidualReferences() {
        return residualReferences;
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void increment(String key) {
        Integer current = (Integer) counters.get(key);
        counters.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    public Map<String, Integer> getCounters() {
        return counters;
    }

    public List<String> getRewrites() {
        return rewrites;
    }

    public int getClassesTotal() {
        return classesTotal;
    }

    public int getClassesRemapped() {
        return classesRemapped;
    }

    public int getResourcesRewritten() {
        return resourcesRewritten;
    }

    public boolean isModified() {
        return classesRemapped > 0 || resourcesRewritten > 0 || redirectCount > 0;
    }

    public String summary() {
        return sourceName + ": " + classesTotal + " classes (" + classesRemapped + " remapped, "
                + classesBlocked + " blocked), " + redirectCount + " call-site redirects, "
                + resourcesRewritten + " resources rewritten, " + residualReferences.size() + " residual refs, "
                + warnings.size() + " warnings";
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ForgeBridge patch report ===\n");
        sb.append("source          : ").append(sourceName).append('\n');
        if (outputName != null) {
            sb.append("output          : ").append(outputName).append('\n');
        }
        sb.append("legacy forge mod: ").append(legacyMod).append('\n');
        if (detectedModId != null) {
            sb.append("detected mod    : ").append(detectedModId)
                    .append(detectedVersion == null ? "" : " " + detectedVersion).append('\n');
        }
        sb.append("pipeline        : ").append(pipeline).append('\n');
        sb.append("classes         : ").append(classesTotal).append(" total, ")
                .append(classesRemapped).append(" remapped, ").append(classesBlocked).append(" blocked\n");
        sb.append("redirects       : ").append(redirectCount).append('\n');
        sb.append("resources       : ").append(resourcesRewritten).append(" rewritten, ")
                .append(entriesSkipped).append(" skipped\n");
        if (!blockedTypes.isEmpty()) {
            sb.append("blocked types   :\n");
            for (String type : blockedTypes) {
                sb.append("  - ").append(type).append('\n');
            }
        }
        if (!residualReferences.isEmpty()) {
            sb.append("residual refs   : ").append(StringUtil.join(", ", residualReferences)).append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append("warnings        :\n");
            for (int i = 0; i < warnings.size(); i++) {
                sb.append("  ! ").append(String.valueOf(warnings.get(i))).append('\n');
            }
        }
        return sb.toString();
    }
}
