package io.genesis.sandbox;

import java.util.ArrayList;
import java.util.List;

/** Public, implementation-neutral description of the environment selected by the service. */
public final class EffectiveEnvironment {
    public String profileName;
    public String profileRevision;
    public String selectionMode;
    public List<String> selectionReason = new ArrayList<>();
    public List<String> capabilities = new ArrayList<>();
    public boolean degraded;

    public EffectiveEnvironment() {}

    @Override
    public String toString() {
        return "EffectiveEnvironment{profileName='" + profileName + "', selectionMode='"
                + selectionMode + "', degraded=" + degraded + ", capabilities=" + capabilities + "}";
    }
}
